"""Validated, atomic, idempotent import of frozen Android activity archives."""

from __future__ import annotations

import csv
import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import zipfile
import zlib
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from pathlib import Path

from . import __version__
from .archive_limits import check_zip_directory
from .health_raw_v2 import OutputBudget, decode_to_csv, read_header
from .phone_time import add_phone_time
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


class ResearchStoreIntegrityError(ValidationError):
    """An admitted research artifact is damaged; incoming archives remain retryable."""


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


def is_link_like(path: Path):
    """Reject symlinks and Windows junctions before following a research-store path."""
    if path.is_symlink():
        return True
    junction = getattr(path, "is_junction", None)
    if junction is not None and junction():
        return True
    try:
        metadata = os.lstat(path)
    except FileNotFoundError:
        return False
    attributes = getattr(metadata, "st_file_attributes", 0)
    return bool(attributes & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0))


def regular_tree_entries(directory: Path, limits: Limits):
    require(directory.is_dir() and not is_link_like(directory),
            "session entry is not a regular directory")
    entries, scanners, total_bytes = [], [os.scandir(directory)], 0
    maximum_entries = limits.entries * 4 + 32
    maximum_bytes = limits.archive_bytes + limits.expanded_bytes + limits.decoded_bytes + limits.json_bytes * 4
    try:
        while scanners:
            try:
                item = next(scanners[-1])
            except StopIteration:
                scanners.pop().close()
                continue
            path = Path(item.path)
            require(not is_link_like(path) and (path.is_file() or path.is_dir()),
                    "existing import artifact is not a regular file")
            entries.append(path)
            require(len(entries) <= maximum_entries, "existing import tree entry quota exceeded")
            if path.is_dir():
                scanners.append(os.scandir(path))
            else:
                total_bytes += path.stat().st_size
                require(total_bytes <= maximum_bytes, "existing import tree size quota exceeded")
    finally:
        for scanner in scanners:
            scanner.close()
    return entries


def bounded_file_bytes(path, maximum, label):
    require(path.is_file() and not is_link_like(path), f"{label} is not a regular file")
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    require(0 < len(data) <= maximum, f"{label} size exceeds quota")
    return data


@contextmanager
def verified_archive_snapshot(path, maximum, expected_digest, label):
    require(path.is_file() and not is_link_like(path), f"{label} is not a regular file")
    with tempfile.TemporaryDirectory(prefix="ringfitness-owner-") as temporary:
        snapshot = Path(temporary) / "source.zip"
        digest, count = hashlib.sha256(), 0
        with path.open("rb") as source, snapshot.open("xb") as target:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                count += len(chunk)
                require(count <= maximum, f"{label} size exceeds quota")
                digest.update(chunk)
                target.write(chunk)
        require(count > 0, f"{label} is empty")
        require(digest.hexdigest() == expected_digest, f"{label} checksum mismatch")
        yield snapshot


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
    require(not is_link_like(lock) and (not lock.exists() or lock.is_file()),
            "import lock is not a regular file")
    # Keep one stable inode: unlinking a released lock can split concurrent owners across files.
    # The OS releases this lock on process exit, including an unexpected termination.
    with lock.open("a+b") as stream:
        try:
            if os.name == "nt":
                import msvcrt
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise ValidationError("import is locked by an active importer; retry after it finishes") from error
        try:
            stream.seek(0)
            marker = stream.read(128)
            require(marker in (b"", b"ringfitness-os-lock-v1\n"),
                    "legacy import is locked; stop old importers and explicitly remove their .import.lock before upgrading")
            if not marker:
                stream.write(b"ringfitness-os-lock-v1\n")
                stream.flush()
                os.fsync(stream.fileno())
            yield
        finally:
            if os.name == "nt":
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


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
    phone_alignment = add_phone_time(manifest, reports, stage / "derived", limits.decoded_bytes)
    reasons = ["sample_clock_uncalibrated", "sample_coverage_not_assessed"]
    if manifest["capture_boundary_status"] != "confirmed":
        reasons.append("capture_boundaries_unknown")
    if manifest["ground_truth_status"] != "valid":
        reasons.append("reference_" + manifest["ground_truth_status"])
    if manifest["timing_warnings"]:
        reasons.append("phone_or_device_timing_warning")
    if manifest.get("stop_origin") == "device_observed":
        reasons.append("device_observed_stop")
    elif manifest.get("stop_origin") == "legacy_unspecified":
        reasons.append("legacy_stop_origin_unspecified")
    if not any(r["channels"]["imu"]["samples"] for r in reports):
        reasons.append("imu_signal_missing")
    if len(raw_entries) > 1:
        reasons.append("multiple_raw_files_require_overlap_review")
    if len({x["sha256"] for x in raw_entries}) != len(raw_entries):
        reasons.append("duplicate_raw_content")
    packet_timing_issue = any(
        c[k] for r in reports for c in r["channels"].values() for k in ("gaps", "overlaps", "rollbacks"))
    sequence_issue = any(
        r["ppg_vitals_sequence"][k]
        for r in reports for k in ("gap_events", "duplicates", "rollbacks"))
    if packet_timing_issue or sequence_issue:
        reasons.append("raw_timing_discontinuity")
    quality = {"rules_version": 2, "analysis_status": "pending_review", "analysis_reasons": reasons,
               "daily_aggregation_eligible": False, "absolute_sample_time_status": "unknown",
               "phone_time_alignment": phone_alignment,
               "sample_coverage_status": "not_assessed", "files": reports,
               "reference_rows": 1, "reference_applies_to": "all_raw_files_in_session",
               "limits": asdict(limits)}
    write_json(stage / "quality.json", quality)
    fields = ["session_id", "participant_id", "installation_id", "ring_placement_schema", "ring_placement",
              "ring_hand", "ring_finger", "app_version", "created_at", "time_zone_id",
              "utc_offset_seconds", "started_at_ms", "ended_at_ms", "capture_boundary_status",
              "start_requested_at_ms", "start_confirmed_at_ms", "stop_origin", "stop_requested_at_ms",
              "stop_observed_at_ms", "stop_confirmed_at_ms",
              "ground_truth_source", "ground_truth_steps", "ground_truth_status", "ground_truth_recorded_at_ms",
              "reference_saved_at_ms", "download_completed_at_ms", "activity_code", "activity_label_status",
              "activity_label_source"]
    with (stage / "reference.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerow({key: manifest.get(key, "") for key in fields})
        stream.flush()
        os.fsync(stream.fileno())
    return quality


def read_import_receipt(destination, maximum_bytes=Limits().json_bytes):
    path = destination / "import.json"
    data = bounded_file_bytes(path, maximum_bytes, "existing import receipt")
    receipt = object_value(strict_json(data), "existing import receipt")
    require({"session_id", "zip_sha256", "artifacts"} <= set(receipt), "existing import receipt fields missing")
    require(type(receipt["session_id"]) is str and receipt["session_id"], "existing import identity is invalid")
    require(type(receipt["zip_sha256"]) is str and re.fullmatch(r"[0-9a-f]{64}", receipt["zip_sha256"]),
            "existing archive hash is invalid")
    artifacts = object_value(receipt["artifacts"], "existing artifact hashes")
    require(all(type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value) for value in artifacts.values()),
            "existing artifact hash is invalid")
    return receipt


def existing_result(destination, session_id, digest, limits=None):
    limits = limits or Limits()
    require(destination.is_dir() and not is_link_like(destination),
            "session entry is not a regular directory")
    receipt = read_import_receipt(destination, limits.json_bytes)
    require(receipt.get("session_id") == session_id, "existing import identity is inconsistent")
    source = destination / "source.zip"
    require(source.is_file() and not is_link_like(source), "existing archive is not a regular file")
    require(source.stat().st_size <= limits.archive_bytes, "existing archive size exceeds quota")
    require(sha256(source) == receipt.get("zip_sha256"), "existing archive checksum mismatch")
    artifacts = object_value(receipt.get("artifacts"), "existing artifact hashes")
    entries = regular_tree_entries(destination, limits)
    actual = {p.relative_to(destination).as_posix() for p in entries
              if p.is_file() and p != destination / "import.json"}
    require(actual == set(artifacts), "existing import file inventory changed")
    for name, expected in artifacts.items():
        artifact = destination / name
        require(artifact.is_file() and not is_link_like(artifact),
                "existing import artifact is not a regular file")
        require(sha256(artifact) == expected, "existing import artifact checksum mismatch")
    return receipt.get("zip_sha256") == digest


def canonical_ownership_manifest(directory, limits):
    receipt = read_import_receipt(directory, limits.json_bytes)
    require(receipt["session_id"] == directory.name, "existing import identity is inconsistent")
    require(receipt.get("status") == "imported", "existing canonical import status is invalid")
    artifacts = object_value(receipt["artifacts"], "existing artifact hashes")
    source_digest = artifacts.get("source.zip")
    require(type(source_digest) is str and re.fullmatch(r"[0-9a-f]{64}", source_digest) and
            receipt["zip_sha256"] == source_digest, "existing ownership receipt is inconsistent")

    with verified_archive_snapshot(
        directory / "source.zip", limits.archive_bytes, source_digest, "frozen source archive"
    ) as source:
        check_zip_directory(source, limits.entries)
        with zipfile.ZipFile(source) as archive:
            entries = inspect_zip(archive, limits)
            manifest_bytes = archive.read(entries["manifest.json"])
    manifest = validate_manifest(strict_json(manifest_bytes))
    require(manifest["session_id"] == directory.name, "session directory identity differs from frozen manifest")
    return manifest


def canonical_raw_duplicate(root, session_id, raw_entries, limits):
    incoming = {entry["sha256"] for entry in raw_entries}
    sessions = root / "sessions"
    try:
        require(not is_link_like(sessions) and (not sessions.exists() or sessions.is_dir()),
                "session storage is not a regular directory")
    except (OSError, ValidationError) as error:
        raise ResearchStoreIntegrityError(f"existing research store integrity error: {error}") from error
    for directory in sorted(sessions.glob("*")):
        try:
            require(directory.is_dir() and not is_link_like(directory), "session entry is not a regular directory")
            manifest = canonical_ownership_manifest(directory, limits)
        except (OSError, ValidationError, zipfile.BadZipFile, zlib.error, EOFError, RuntimeError, UnicodeError) as error:
            raise ResearchStoreIntegrityError(
                f"existing canonical session integrity error ({directory.name}): {error}"
            ) from error
        if manifest["session_id"] == session_id:
            continue
        shared = sorted(incoming & {entry["sha256"] for entry in manifest["files"] if entry["role"] == "raw"})
        if shared:
            return {"session_id": manifest["session_id"], "raw_sha256": shared}
    return None


def publish(stage, destination):
    require(not is_link_like(destination), "import destination is not a regular path")
    require(not destination.parent.exists() or
            (destination.parent.is_dir() and not is_link_like(destination.parent)),
            "import destination parent is not a regular directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    require(not destination.exists(), "import destination already exists")
    sync_tree(stage)
    stage.rename(destination)
    sync_directory(destination.parent)


def import_archive(source: Path, root: Path, limits: Limits | None = None, *, expected_archive=None) -> ImportResult:
    limits = limits or Limits()
    source, root = local_path(source), local_path(root)
    require(source.is_file() and 0 < source.stat().st_size <= limits.archive_bytes, "archive size quota exceeded")
    with import_lock(root):
        staging_root = root / ".staging"
        for name in (".staging", "sessions", "conflicts", "rejected"):
            path = root / name
            require(not is_link_like(path) and (not path.exists() or path.is_dir()),
                    f"{name} storage is not a regular directory")
        staging_root.mkdir(exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix="import-", dir=staging_root))
        digest = None
        try:
            frozen = stage / "source.zip"
            count, h = 0, hashlib.sha256()
            with source.open("rb") as inp, frozen.open("xb") as out:
                for chunk in iter(lambda: inp.read(1024 * 1024), b""):
                    count += len(chunk)
                    if expected_archive is not None and count > expected_archive[1]:
                        raise ResearchStoreIntegrityError(
                            "registered cloud archive changed before import; restore or download it again"
                        )
                    require(count <= limits.archive_bytes, "archive grew beyond size quota")
                    h.update(chunk)
                    out.write(chunk)
                out.flush()
                os.fsync(out.fileno())
            digest = h.hexdigest()
            if expected_archive is not None:
                expected_digest, expected_bytes = expected_archive
                if count != expected_bytes or digest != expected_digest:
                    raise ResearchStoreIntegrityError(
                        "registered cloud archive changed before import; restore or download it again"
                    )
            check_zip_directory(frozen, limits.entries)
            with zipfile.ZipFile(frozen) as archive:
                entries = inspect_zip(archive, limits)
                manifest_bytes = archive.read(entries["manifest.json"])
                manifest = validate_manifest(strict_json(manifest_bytes))
                extract_verified(archive, entries, manifest, stage / "raw", limits)
            (stage / "manifest.json").write_bytes(manifest_bytes)
            session_id = manifest["session_id"]
            destination = root / "sessions" / session_id
            require(not is_link_like(destination), "session destination is not a regular path")
            if destination.exists():
                try:
                    if existing_result(destination, session_id, digest, limits):
                        return ImportResult("already_imported", session_id, digest, str(destination))
                except (OSError, ValidationError) as error:
                    raise ResearchStoreIntegrityError(
                        f"existing canonical session integrity error ({session_id}): {error}"
                    ) from error
            raw_entries = [entry for entry in manifest["files"] if entry["role"] == "raw"]
            duplicate = canonical_raw_duplicate(root, session_id, raw_entries, limits)
            quality = decode_archive(stage, manifest, limits)
            conflict = destination.exists() or duplicate is not None
            status = "conflict" if conflict else "imported"
            if conflict:
                conflict_parent = root / "conflicts" / session_id
                require(not is_link_like(conflict_parent) and
                        (not conflict_parent.exists() or conflict_parent.is_dir()),
                        "conflict storage is not a regular directory")
                destination = conflict_parent / digest
                require(not is_link_like(destination), "conflict destination is not a regular path")
                if destination.exists():
                    try:
                        require(existing_result(destination, session_id, digest, limits),
                                "existing conflict archive is inconsistent")
                    except (OSError, ValidationError) as error:
                        raise ResearchStoreIntegrityError(
                            f"existing conflict artifact integrity error ({session_id}/{digest}): {error}"
                        ) from error
                    return ImportResult("conflict", session_id, digest, str(destination))
            artifacts = {p.relative_to(stage).as_posix(): sha256(p) for p in stage.rglob("*") if p.is_file()}
            receipt = {"importer_version": __version__, "session_id": session_id,
                       "zip_sha256": digest, "status": status, "analysis_status": quality["analysis_status"],
                       "artifacts": artifacts}
            if duplicate is not None:
                receipt.update(conflict_reason="raw_content_shared_across_sessions",
                               duplicate_of=duplicate["session_id"],
                               duplicate_raw_sha256=duplicate["raw_sha256"])
            write_json(stage / "import.json", receipt)
            publish(stage, destination)
            return ImportResult(status, session_id, digest, str(destination))
        except ResearchStoreIntegrityError:
            raise
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


def summarize(root: Path, output: Path, limits: Limits | None = None):
    """Session index only: unknown coverage never becomes a daily total."""
    limits = limits or Limits()
    lexical_output = Path(output).absolute()
    require(not is_link_like(lexical_output), "summary output is not a regular file")
    require(not lexical_output.parent.exists() or
            (lexical_output.parent.is_dir() and not is_link_like(lexical_output.parent)),
            "summary output parent is not a regular directory")
    root, output = local_path(root), local_path(lexical_output)
    reserved = [root / name for name in (".import.lock", "sessions", "conflicts", "rejected", ".staging", ".sync-staging")]
    require(output != root and all(output != path and path not in output.parents for path in reserved),
            "summary output cannot replace the import lock or preserved research artifacts")
    with import_lock(root):
        return _summarize_locked(root, output, limits)


def _summarize_locked(root: Path, output: Path, limits: Limits):
    rows, raw_owners = [], {}
    root = local_path(root)
    sessions = root / "sessions"
    require(not is_link_like(sessions) and (not sessions.exists() or sessions.is_dir()),
            "session storage is not a regular directory")
    for directory in sorted(sessions.glob("*")):
        require(directory.is_dir() and not is_link_like(directory), "session entry is not a regular directory")
        manifest = validate_manifest(strict_json(bounded_file_bytes(
            directory / "manifest.json", limits.json_bytes, "existing manifest"
        )))
        require(directory.name == manifest["session_id"], "session directory identity differs from manifest")
        quality = object_value(strict_json(bounded_file_bytes(
            directory / "quality.json", limits.json_bytes, "existing quality report"
        )), "existing quality report")
        require({"analysis_status", "analysis_reasons"} <= set(quality), "existing quality report fields missing")
        require(quality["analysis_status"] == "pending_review", "existing analysis status is invalid")
        require(type(quality["analysis_reasons"]) is list and
                all(type(reason) is str and reason for reason in quality["analysis_reasons"]),
                "existing analysis reasons are invalid")
        receipt = read_import_receipt(directory, limits.json_bytes)
        existing_result(directory, manifest["session_id"], receipt["zip_sha256"], limits)
        for entry in manifest["files"]:
            if entry["role"] == "raw":
                raw_owners.setdefault(entry["sha256"], set()).add(manifest["session_id"])
        rows.append({"session_id": manifest["session_id"], "participant_id": manifest["participant_id"],
                     "activity_code": manifest["activity_code"],
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
    fields = ["session_id", "participant_id", "activity_code", "ground_truth_steps", "ground_truth_status", "started_at_ms",
              "ended_at_ms", "analysis_status", "daily_aggregation_eligible", "analysis_reasons"]
    if output.exists():
        require(output.is_file() and not is_link_like(output), "existing summary is not a regular file")
        require(0 < output.stat().st_size <= limits.decoded_bytes,
                "existing summary size exceeds quota")
        current_ids = {row["session_id"] for row in rows}
        try:
            with output.open("r", encoding="utf-8", newline="") as stream:
                previous = csv.DictReader(stream)
                legacy_fields = [field for field in fields if field != "activity_code"]
                initial_fields = ["session_id", "participant_id", "ground_truth_steps"]
                require(previous.fieldnames in (fields, legacy_fields, initial_fields),
                        "existing summary fields are invalid")
                preserves_catalog = previous.fieldnames in (fields, legacy_fields)
                for row in previous:
                    session_id = row.get("session_id")
                    require(not preserves_catalog or session_id and session_id in current_ids,
                            "previously indexed session is missing from research store")
        except (UnicodeError, csv.Error) as error:
            raise ValidationError("existing summary is invalid") from error
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".session-index-", suffix=".tmp", dir=output.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, output)
        sync_directory(output.parent)
    finally:
        temporary.unlink(missing_ok=True)
    return rows
