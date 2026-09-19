"""Validated, atomic, idempotent import of frozen Android activity archives."""

from __future__ import annotations

import csv
import hashlib
import json
import os
import shutil
import stat
import tempfile
import zipfile
import zlib
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from pathlib import Path

from .health_raw_v2 import OutputBudget, decode_to_csv, read_header
from .schema import ValidationError, integer, object_value, require, safe_name, strict_json, validate_manifest


@dataclass(frozen=True)
class Limits:
    archive_bytes: int = 512 * 1024 ** 2
    expanded_bytes: int = 1024 ** 3
    entry_bytes: int = 512 * 1024 ** 2
    json_bytes: int = 1024 ** 2
    entries: int = 256
    compression_ratio: int = 1000
    decoded_bytes: int = 2 * 1024 ** 3


@dataclass(frozen=True)
class ImportResult:
    status: str
    session_id: str
    zip_sha256: str
    directory: str


class ArchiveRejected(ValidationError):
    def __init__(self, message, directory=None):
        super().__init__(message)
        self.directory = directory


def local_path(path):
    path = Path(path).resolve()
    if os.name == "nt" and not str(path).startswith("\\\\?\\"):
        value = str(path)
        return Path("\\\\?\\UNC\\" + value[2:] if value.startswith("\\\\") else "\\\\?\\" + value)
    return path


def sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value):
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def sync_directory(path):
    # Windows has no portable directory fsync. File fsync + same-volume rename
    # provides process-interruption safety; power-loss durability needs OS testing.
    if os.name != "nt":
        fd = os.open(path, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def sync_tree(directory):
    for path in directory.rglob("*"):
        if path.is_file():
            with path.open("r+b") as stream:
                os.fsync(stream.fileno())
    for path in sorted((p for p in directory.rglob("*") if p.is_dir()), reverse=True):
        sync_directory(path)
    sync_directory(directory)


@contextmanager
def import_lock(root):
    root.mkdir(parents=True, exist_ok=True)
    lock = root / ".import.lock"
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError as error:
        raise ValidationError("import is locked; confirm no importer is running before removing a stale .import.lock") from error
    try:
        with os.fdopen(fd, "w") as stream:
            stream.write(str(os.getpid()))
            stream.flush()
            os.fsync(stream.fileno())
        yield
    finally:
        lock.unlink()


def inspect_zip(archive, limits):
    entries, folded, expanded = {}, set(), 0
    items = archive.infolist()
    require(0 < len(items) <= limits.entries, "ZIP entry quota exceeded")
    for entry in items:
        require(entry.orig_filename == entry.filename, "ZIP filename contains a null terminator")
        name = safe_name(entry.filename)
        require(not entry.is_dir() and name.casefold() not in folded, "duplicate or directory ZIP entry")
        folded.add(name.casefold())
        mode = entry.external_attr >> 16
        require(stat.S_IFMT(mode) in (0, stat.S_IFREG), "ZIP contains a nonregular file")
        require(entry.flag_bits & 1 == 0, "encrypted ZIP unsupported")
        require(entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), "unsupported ZIP compression")
        require(0 < entry.file_size <= limits.entry_bytes, "ZIP file size quota exceeded")
        require(entry.file_size <= max(1, entry.compress_size) * limits.compression_ratio,
                "ZIP compression ratio quota exceeded")
        expanded += entry.file_size
        require(expanded <= limits.expanded_bytes, "ZIP expanded size quota exceeded")
        entries[name] = entry
    require("manifest.json" in entries, "missing root manifest.json")
    require(entries["manifest.json"].file_size <= limits.json_bytes, "manifest size quota exceeded")
    return entries


def extract_verified(archive, entries, manifest, destination, limits):
    expected = {x["file_name"] for x in manifest["files"]} | {"manifest.json"}
    require(set(entries) == expected, "ZIP entries differ from manifest")
    destination.mkdir()
    for entry in manifest["files"]:
        name = entry["file_name"]
        require(entries[name].file_size == entry["bytes"], "manifest file length mismatch")
        if entry["role"] == "evidence":
            require(entry["bytes"] <= limits.json_bytes, "evidence JSON size quota exceeded")
        digest, written = hashlib.sha256(), 0
        with archive.open(entries[name]) as source, (destination / name).open("xb") as target:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                written += len(chunk)
                require(written <= entry["bytes"], "ZIP decoded file exceeds manifest length")
                digest.update(chunk)
                target.write(chunk)
            target.flush()
            os.fsync(target.fileno())
        require(written == entry["bytes"] and digest.hexdigest() == entry["sha256"], "manifest SHA-256 mismatch")


def validate_raw_header(path, manifest, single):
    header = read_header(path)
    record = manifest["device_record_evidence"]["record"]
    require(header.session_id == manifest["device_session_id"] == record["device_session_id"],
            "rfbin device association mismatch")
    require(header.anchor_uptime_ms == record["uptime_ms"] and header.anchor_unix_ms == record["unix_ms"],
            "rfbin device fingerprint mismatch")
    require(header.started_at_ms == (manifest["started_at_ms"] or 0) and
            header.ended_at_ms == (manifest["ended_at_ms"] or 0), "rfbin capture boundaries mismatch")
    if single:
        require(header.records == record["records"] and header.payload_bytes == record["bytes"],
                "rfbin counters differ from complete device record")
    return header


def validate_sidecar(path, raw_entry, report, manifest):
    e = object_value(strict_json(path.read_bytes()), "raw evidence")
    h = report["header"]
    expected = {
        "schema_version": 1, "session_id": manifest["session_id"], "ring_address": manifest["ring_address"],
        "device_session_id": h["session_id"], "bytes": h["payload_bytes"], "records": h["records"],
        "anchor_uptime_ms": h["anchor_uptime_ms"], "anchor_unix_ms": h["anchor_unix_ms"],
        "started_at_ms": h["started_at_ms"], "ended_at_ms": h["ended_at_ms"],
        "payload_crc32": h["payload_crc32"], "file_sha256": raw_entry["sha256"],
        "parsed_records": report["parsed_records"], "crc_source": "phone_payload_and_container_reread",
        "sample_coverage_status": "not_assessed",
    }
    for channel in ("imu", "ppg"):
        c = report["channels"][channel]
        expected[channel + "_samples"] = c["samples"]
        # Android sidecars retain first/last packet endpoints, not first sample.
        expected["first_" + channel + "_uptime_ms"] = c["first_packet_uptime_ms"]
        expected["last_" + channel + "_uptime_ms"] = c["last_packet_uptime_ms"]
    require(set(e) == set(expected), "raw evidence fields mismatch")
    for key, value in expected.items():
        require(type(e[key]) is type(value) and e[key] == value, f"raw evidence mismatch: {key}")


def decode_archive(stage, manifest, limits):
    raw_entries = [x for x in manifest["files"] if x["role"] == "raw"]
    evidence_entries = {x["file_name"]: x for x in manifest["files"] if x["role"] == "evidence"}
    reports, used_evidence = [], set()
    budget = OutputBudget(limits.decoded_bytes)
    for entry in raw_entries:
        path = stage / "raw" / entry["file_name"]
        validate_raw_header(path, manifest, len(raw_entries) == 1)
        report = decode_to_csv(path, stage / "derived", manifest, budget)
        reports.append(report)
        sidecar_name = path.stem + ".raw-evidence.json"
        if sidecar_name in evidence_entries:
            validate_sidecar(stage / "raw" / sidecar_name, entry, report, manifest)
            used_evidence.add(sidecar_name)
    require(used_evidence == set(evidence_entries), "orphan raw evidence file")
    reasons = ["sample_clock_uncalibrated", "sample_coverage_not_assessed"]
    if manifest["capture_boundary_status"] != "confirmed":
        reasons.append("capture_boundaries_unknown")
    if manifest["ground_truth_status"] != "valid":
        reasons.append("reference_" + manifest["ground_truth_status"])
    if manifest["timing_warnings"]:
        reasons.append("phone_or_device_timing_warning")
    if not any(r["channels"]["imu"]["samples"] for r in reports):
        reasons.append("imu_signal_missing")
    if len(raw_entries) > 1:
        reasons.append("multiple_raw_files_require_overlap_review")
    if len({x["sha256"] for x in raw_entries}) != len(raw_entries):
        reasons.append("duplicate_raw_content")
    if any(c[k] for r in reports for c in r["channels"].values() for k in ("gaps", "overlaps", "rollbacks")):
        reasons.append("raw_timing_discontinuity")
    quality = {"rules_version": 1, "analysis_status": "pending_review", "analysis_reasons": reasons,
               "daily_aggregation_eligible": False, "absolute_sample_time_status": "unknown",
               "sample_coverage_status": "not_assessed", "files": reports,
               "reference_rows": 1, "reference_applies_to": "all_raw_files_in_session",
               "limits": asdict(limits)}
    write_json(stage / "quality.json", quality)
    fields = ["session_id", "participant_id", "installation_id", "ring_placement", "time_zone_id",
              "utc_offset_seconds", "started_at_ms", "ended_at_ms", "capture_boundary_status",
              "start_requested_at_ms", "start_confirmed_at_ms", "stop_requested_at_ms", "stop_confirmed_at_ms",
              "ground_truth_source", "ground_truth_steps", "ground_truth_status", "ground_truth_recorded_at_ms",
              "reference_saved_at_ms", "download_completed_at_ms", "activity_code", "activity_label_status",
              "activity_label_source"]
    with (stage / "reference.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerow({key: manifest[key] for key in fields})
        stream.flush()
        os.fsync(stream.fileno())
    return quality


def existing_result(destination, session_id, digest):
    receipt = strict_json((destination / "import.json").read_bytes())
    require(receipt.get("session_id") == session_id, "existing import identity is inconsistent")
    require(sha256(destination / "source.zip") == receipt.get("zip_sha256"), "existing archive checksum mismatch")
    artifacts = object_value(receipt.get("artifacts"), "existing artifact hashes")
    actual = {p.relative_to(destination).as_posix() for p in destination.rglob("*")
              if p.is_file() and p.name != "import.json"}
    require(actual == set(artifacts), "existing import file inventory changed")
    for name, expected in artifacts.items():
        require(sha256(destination / name) == expected, "existing import artifact checksum mismatch")
    return receipt.get("zip_sha256") == digest


def publish(stage, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    require(not destination.exists(), "import destination already exists")
    sync_tree(stage)
    stage.rename(destination)
    sync_directory(destination.parent)


def import_archive(source: Path, root: Path, limits: Limits | None = None) -> ImportResult:
    limits = limits or Limits()
    source, root = local_path(source), local_path(root)
    require(source.is_file() and 0 < source.stat().st_size <= limits.archive_bytes, "archive size quota exceeded")
    with import_lock(root):
        staging_root = root / ".staging"
        staging_root.mkdir(exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix="import-", dir=staging_root))
        digest = None
        try:
            frozen = stage / "source.zip"
            count, h = 0, hashlib.sha256()
            with source.open("rb") as inp, frozen.open("xb") as out:
                for chunk in iter(lambda: inp.read(1024 * 1024), b""):
                    count += len(chunk)
                    require(count <= limits.archive_bytes, "archive grew beyond size quota")
                    h.update(chunk)
                    out.write(chunk)
                out.flush()
                os.fsync(out.fileno())
            digest = h.hexdigest()
            with zipfile.ZipFile(frozen) as archive:
                entries = inspect_zip(archive, limits)
                manifest_bytes = archive.read(entries["manifest.json"])
                manifest = validate_manifest(strict_json(manifest_bytes))
                extract_verified(archive, entries, manifest, stage / "raw", limits)
            (stage / "manifest.json").write_bytes(manifest_bytes)
            session_id = manifest["session_id"]
            destination = root / "sessions" / session_id
            if destination.exists() and existing_result(destination, session_id, digest):
                return ImportResult("already_imported", session_id, digest, str(destination))
            quality = decode_archive(stage, manifest, limits)
            conflict = destination.exists()
            status = "conflict" if conflict else "imported"
            if conflict:
                destination = root / "conflicts" / session_id / digest
                if destination.exists():
                    require(existing_result(destination, session_id, digest), "existing conflict archive is inconsistent")
                    return ImportResult("conflict", session_id, digest, str(destination))
            artifacts = {p.relative_to(stage).as_posix(): sha256(p) for p in stage.rglob("*") if p.is_file()}
            write_json(stage / "import.json", {"importer_version": "0.1.0", "session_id": session_id,
                       "zip_sha256": digest, "status": status, "analysis_status": quality["analysis_status"],
                       "artifacts": artifacts})
            publish(stage, destination)
            return ImportResult(status, session_id, digest, str(destination))
        except (ValidationError, zipfile.BadZipFile, zlib.error, EOFError, RuntimeError, UnicodeError) as error:
            rejected = None
            if digest is not None:
                rejected = root / "rejected" / digest
                if not rejected.exists():
                    # The frozen archive is sufficient for reproduction; partial derivatives
                    # remain isolated here and never appear among admitted sessions.
                    write_json(stage / "rejection.json", {"status": "rejected", "reason": str(error),
                               "zip_sha256": digest})
                    publish(stage, rejected)
            raise ArchiveRejected(str(error), str(rejected) if rejected else None) from error
        finally:
            if stage.exists():
                shutil.rmtree(stage)


def summarize(root: Path, output: Path):
    """Session index only: unknown coverage never becomes a daily total."""
    rows, raw_owners = [], {}
    root = local_path(root)
    for directory in sorted((root / "sessions").glob("*")):
        if not directory.is_dir():
            continue
        manifest = validate_manifest(strict_json((directory / "manifest.json").read_bytes()))
        quality = strict_json((directory / "quality.json").read_bytes())
        receipt = strict_json((directory / "import.json").read_bytes())
        existing_result(directory, manifest["session_id"], receipt["zip_sha256"])
        for entry in manifest["files"]:
            if entry["role"] == "raw":
                raw_owners.setdefault(entry["sha256"], set()).add(manifest["session_id"])
        rows.append({"session_id": manifest["session_id"], "participant_id": manifest["participant_id"],
                     "ground_truth_steps": manifest["ground_truth_steps"], "ground_truth_status": manifest["ground_truth_status"],
                     "started_at_ms": manifest["started_at_ms"], "ended_at_ms": manifest["ended_at_ms"],
                     "analysis_status": quality["analysis_status"], "daily_aggregation_eligible": False,
                     "analysis_reasons": ";".join(quality["analysis_reasons"])})
    duplicated = set().union(*(ids for ids in raw_owners.values() if len(ids) > 1)) if raw_owners else set()
    for row in rows:
        if row["session_id"] in duplicated:
            row["analysis_reasons"] += ";raw_content_shared_across_sessions"
        if any(other is not row and row["participant_id"] == other["participant_id"] and
               all(x is not None for x in (row["started_at_ms"], row["ended_at_ms"],
                                          other["started_at_ms"], other["ended_at_ms"])) and
               max(row["started_at_ms"], other["started_at_ms"]) < min(row["ended_at_ms"], other["ended_at_ms"])
               for other in rows):
            row["analysis_reasons"] += ";overlapping_session_intervals"
    output = local_path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    fields = ["session_id", "participant_id", "ground_truth_steps", "ground_truth_status", "started_at_ms",
              "ended_at_ms", "analysis_status", "daily_aggregation_eligible", "analysis_reasons"]
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    return rows
