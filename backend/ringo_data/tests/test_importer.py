import copy
import csv
import json
import struct
import zipfile
import zlib
from pathlib import Path
from uuid import UUID

import pytest

from backend.ringo_data.__main__ import main
from backend.ringo_data.health_raw_v2 import HEADER, MAGIC
from backend.ringo_data.importer import ArchiveRejected, Limits, import_archive, sha256, summarize
from backend.ringo_data.schema import ValidationError


def imu(uptime, count=2):
    return bytes((0x32, 0x12)) + struct.pack("<BI", count, uptime) + struct.pack("<hhh", 2048, -2048, 0) * count


def ppg(uptime=10040):
    return bytes((0x32, 0x11)) + struct.pack("<HBBBBI", 7, 0, 0, 2, 5, uptime) + struct.pack("<iiii", 100, 200, 101, 201)


def raw_bytes(packets=None, **overrides):
    packets = packets if packets is not None else [imu(10020), ppg()]
    payload = b"".join(packets)
    values = dict(session=7, records=len(packets), uptime=10000, anchor=1_800_000_000_000,
                  started=0, ended=0, length=len(payload), crc=zlib.crc32(payload))
    values.update(overrides)
    return HEADER.pack(MAGIC, 2, 64, values["session"], 0, values["records"], values["uptime"],
                       values["anchor"], values["started"], values["ended"], values["length"], values["crc"], 0) + payload


def manifest_for(raw, session_number=1):
    h = HEADER.unpack(raw[:64])
    status = dict(collecting=False, bytes=h[10], records=h[5], error_code=0, device_session_id=h[3])
    record = dict(device_session_id=h[3], bytes=h[10], records=h[5], uptime_ms=h[6], unix_ms=h[7])
    now = 1_801_000_000_000
    return dict(version=2, step_schema_version=1, rfbin_version=2, simulated=False,
                session_id=str(UUID(int=session_number)), participant_id="fixture001", participant_name="fixture001",
                installation_id=str(UUID(int=500)), ring_placement="left_index", ring_address="00:00:00:00:00:01",
                ring_name="Synthetic fixture", time_zone_id="UTC", utc_offset_seconds=0,
                capture_purpose="daily_activity", activity_schema="daily_activity_v2", activity_code="free_living",
                activity_label_status="unlabelled", activity_label_source="none", ground_truth_source="external_pedometer",
                ground_truth_status="valid", ground_truth_steps=0, ground_truth_recorded_at_ms=now + 5000,
                reference_saved_at_ms=now + 5000, ground_truth_reason=None, data_integrity_status="complete",
                download_completed_at_ms=now + 6000, start_requested_at_ms=now, start_confirmed_at_ms=now + 1000,
                stop_requested_at_ms=now + 3000, stop_confirmed_at_ms=now + 4000,
                started_at_ms=None, ended_at_ms=None, device_session_id=h[3], capture_boundary_status="uncertain",
                timing_warnings=[], start_status_evidence=dict(status, collecting=True), stop_status_evidence=status.copy(),
                start_boundary_evidence=None, end_boundary_evidence=None,
                start_baseline=dict(status=dict(collecting=False, bytes=0, records=0, error_code=0, device_session_id=0),
                                    records=[], observed_at_ms=now - 1000),
                device_record_evidence=dict(record=record, status=status.copy(), observed_at_ms=now + 4000),
                device_association_invalidated=False, files=[])


def archive_at(path, raw=None, manifest=None, mutate=None, extras=None, sidecar=False, compression=zipfile.ZIP_STORED):
    import hashlib
    raw = raw if raw is not None else raw_bytes()
    m = copy.deepcopy(manifest) if manifest is not None else manifest_for(raw)
    name = m["session_id"] + "-ring-7.rfbin"
    files = {name: raw}
    m["files"] = [dict(file_name=name, role="raw", device_session_id=7, bytes=len(raw),
                       sha256=hashlib.sha256(raw).hexdigest(), simulated=False)]
    if sidecar:
        h = HEADER.unpack(raw[:64])
        e = dict(schema_version=1, session_id=m["session_id"], ring_address=m["ring_address"], device_session_id=7,
                 bytes=h[10], records=h[5], anchor_uptime_ms=h[6], anchor_unix_ms=h[7], started_at_ms=0, ended_at_ms=0,
                 payload_crc32=h[11], file_sha256=m["files"][0]["sha256"], parsed_records=h[5], imu_samples=2,
                 ppg_samples=2, first_imu_uptime_ms=10020, last_imu_uptime_ms=10020,
                 first_ppg_uptime_ms=10040, last_ppg_uptime_ms=10040,
                 crc_source="phone_payload_and_container_reread", sample_coverage_status="not_assessed")
        data = json.dumps(e).encode()
        ename = name.removesuffix(".rfbin") + ".raw-evidence.json"
        files[ename] = data
        m["files"].append(dict(file_name=ename, role="evidence", device_session_id=7, bytes=len(data),
                               sha256=hashlib.sha256(data).hexdigest(), simulated=False))
    if mutate:
        mutate(m, files)
    with zipfile.ZipFile(path, "w", compression=compression) as archive:
        archive.writestr("manifest.json", json.dumps(m))
        for fname, content in files.items():
            archive.writestr(fname, content)
        for fname, content in (extras or {}).items():
            archive.writestr(fname, content)
    return m


def read_rows(path):
    with Path(path).open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))


def test_real_zero_saved_once_and_unknown_clock_never_becomes_1970(tmp_path):
    source = tmp_path / "input.zip"
    m = archive_at(source, sidecar=True)
    result = import_archive(source, tmp_path / "out")
    destination = Path(result.directory)
    assert result.status == "imported"
    assert sha256(destination / "source.zip") == sha256(source)
    assert json.loads((destination / "manifest.json").read_text()) == m
    reference = read_rows(destination / "reference.csv")
    assert len(reference) == 1 and reference[0]["ground_truth_steps"] == "0"
    rows = read_rows(next((destination / "derived").glob("*imu*.csv")))
    assert [r["ring_uptime_ms"] for r in rows] == ["10000", "10020"]
    assert [r["relative_sample_offset_ms"] for r in rows] == ["0", "20"]
    assert all(r["timestamp_unix_ms"] == r["timestamp_iso"] == r["activity_truth"] == "" for r in rows)
    assert rows[0]["accel_x_ms2"] == "9.806650" and rows[0]["accel_y_ms2"] == "-9.806650"
    assert rows[0]["device_anchor_estimated_unix_ms"] == "1800000000000"
    assert all(r["activity_label_status"] == "unlabelled" and r["activity_label_source"] == "none" for r in rows)
    ppg_rows = read_rows(next((destination / "derived").glob("*ppg*.csv")))
    assert ppg_rows[1]["infrared_raw"] == "201" and ppg_rows[1]["red_raw"] == ""
    quality = json.loads((destination / "quality.json").read_text())
    assert quality["analysis_status"] == "pending_review" and quality["daily_aggregation_eligible"] is False
    assert quality["files"][0]["normalization_applied"] is False


@pytest.mark.parametrize("status,steps", [("missing", None), ("unreliable", 0),
                                          ("unreliable", 562), ("valid", (1 << 63) - 1)])
def test_reference_states_preserve_null_zero_and_full_integer_range(tmp_path, status, steps):
    def change(m, _):
        m.update(ground_truth_status=status, ground_truth_steps=steps,
                 ground_truth_recorded_at_ms=m["reference_saved_at_ms"] if steps is not None else None,
                 ground_truth_reason=None if status == "valid" else "Device reading uncertain")
    source = tmp_path / "input.zip"
    archive_at(source, mutate=change)
    result = import_archive(source, tmp_path / "out")
    row = read_rows(Path(result.directory) / "reference.csv")[0]
    assert row["ground_truth_steps"] == ("" if steps is None else str(steps))
    assert row["ground_truth_status"] == status


def test_unreliable_reference_requires_a_numeric_reading(tmp_path):
    def change(m, _):
        m.update(ground_truth_status="unreliable", ground_truth_steps=None,
                 ground_truth_recorded_at_ms=None, ground_truth_reason="Reading uncertain")
    source = tmp_path / "input.zip"
    archive_at(source, mutate=change)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("key,value", [
    ("ground_truth_steps", True), ("ground_truth_steps", False), ("ground_truth_steps", -1),
    ("ground_truth_steps", 1.5), ("ground_truth_steps", "1"), ("ground_truth_steps", 1 << 63),
    ("version", True), ("version", 1), ("step_schema_version", 2), ("rfbin_version", 1),
    ("activity_schema", "daily_activity_v1"), ("activity_code", "walking"),
    ("activity_label_status", "labelled"), ("activity_label_source", "algorithm"),
    ("ground_truth_source", "oura"), ("simulated", True), ("device_association_invalidated", True),
    ("ground_truth_status", "missing"), ("ground_truth_reason", "Unexpected reason"),
    ("ground_truth_recorded_at_ms", None), ("capture_boundary_status", "confirmed"),
    ("stop_confirmed_at_ms", None), ("participant_name", "different"),
])
def test_invalid_schema_is_rejected_and_original_retained(tmp_path, key, value):
    source = tmp_path / "input.zip"
    archive_at(source, mutate=lambda m, _: m.update({key: value}))
    with pytest.raises(ArchiveRejected) as raised:
        import_archive(source, tmp_path / "out")
    assert sha256(Path(raised.value.directory) / "source.zip") == sha256(source)
    assert not list((tmp_path / "out" / "sessions").glob("*"))


@pytest.mark.parametrize("field,value", [("ground_truth_reason", None), ("ground_truth_reason", " "),
                                        ("ground_truth_recorded_at_ms", 9)])
def test_missing_reference_requires_reason_and_no_numeric_time(tmp_path, field, value):
    def change(m, _):
        m.update(ground_truth_status="missing", ground_truth_steps=None, ground_truth_recorded_at_ms=None,
                 ground_truth_reason="Reading unavailable")
        m[field] = value
    source = tmp_path / "input.zip"
    archive_at(source, mutate=change)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


def test_batch_duplicate_is_idempotent_and_conflict_keeps_both(tmp_path):
    source, changed = tmp_path / "one.zip", tmp_path / "changed.zip"
    m = archive_at(source)
    root = tmp_path / "out"
    assert main(["import", str(source), str(source), "--output", str(root)]) == 0
    assert len(list((root / "sessions").iterdir())) == 1
    first_hash = sha256(root / "sessions" / m["session_id"] / "reference.csv")
    archive_at(changed, mutate=lambda m, _: m.update(ground_truth_steps=562))
    result = import_archive(changed, root)
    assert result.status == "conflict"
    assert sha256(Path(result.directory) / "source.zip") == sha256(changed)
    assert sha256(root / "sessions" / m["session_id"] / "reference.csv") == first_hash
    assert import_archive(changed, root).directory == result.directory
    assert main(["import", str(changed), "--output", str(root)]) == 2


def test_duplicate_rechecks_existing_derived_files(tmp_path):
    source = tmp_path / "input.zip"
    archive_at(source)
    result = import_archive(source, tmp_path / "out")
    (Path(result.directory) / "reference.csv").write_text("corrupt")
    with pytest.raises(ArchiveRejected, match="artifact checksum"):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("name", ["../escape", "/absolute", "C:drive", "nested/file", "nested\\file", "CON", "other.txt"])
def test_zip_paths_and_unlisted_files_cannot_escape(tmp_path, name):
    source = tmp_path / "input.zip"
    archive_at(source, extras={name: "bad"})
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")
    assert not (tmp_path / "escape").exists()


def test_duplicate_zip_and_duplicate_json_keys_rejected(tmp_path):
    source = tmp_path / "input.zip"
    archive_at(source)
    with zipfile.ZipFile(source, "a") as archive, pytest.warns(UserWarning):
        archive.writestr("manifest.json", "{}")
    with pytest.raises(ArchiveRejected, match="duplicate"):
        import_archive(source, tmp_path / "out")
    from backend.ringo_data.schema import strict_json
    with pytest.raises(ValidationError, match="duplicate"):
        strict_json(b'{"version":2,"version":1}')


def test_zip_symlink_rejected(tmp_path):
    source = tmp_path / "input.zip"
    archive_at(source)
    with zipfile.ZipFile(source, "a") as archive:
        entry = zipfile.ZipInfo("link")
        entry.create_system = 3
        entry.external_attr = 0o120777 << 16
        archive.writestr(entry, "../other")
    with pytest.raises(ArchiveRejected, match="nonregular"):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("limits", [Limits(entries=1), Limits(json_bytes=1), Limits(expanded_bytes=1),
                                    Limits(entry_bytes=1), Limits(decoded_bytes=1)])
def test_quotas_fail_before_admitting_session(tmp_path, limits):
    source = tmp_path / "input.zip"
    archive_at(source)
    with pytest.raises(ArchiveRejected, match="quota"):
        import_archive(source, tmp_path / "out", limits)
    assert not list((tmp_path / "out" / "sessions").glob("*"))


@pytest.mark.parametrize("change,expected", [
    (lambda m, f: m["files"][0].update(sha256="0" * 64), "SHA"),
    (lambda m, f: m["device_record_evidence"]["record"].update(unix_ms=123), "fingerprint"),
    (lambda m, f: m["files"][0].update(simulated=True), "simulated"),
    (lambda m, f: m["files"][0].update(bytes=True), "integer"),
])
def test_manifest_and_binary_association_are_checked(tmp_path, change, expected):
    source = tmp_path / "input.zip"
    archive_at(source, mutate=change)
    with pytest.raises(ArchiveRejected, match=expected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("raw,expected", [(raw_bytes(crc=0), "CRC"), (raw_bytes(records=3), "record count"),
                                         (raw_bytes([b"\x32\x99"]), "unknown HEALTH"),
                                         (raw_bytes([b"\x32\x12\x01"]), "payload")])
def test_malformed_payload_is_never_published(tmp_path, raw, expected):
    source = tmp_path / "input.zip"
    archive_at(source, raw=raw)
    with pytest.raises(ArchiveRejected, match=expected):
        import_archive(source, tmp_path / "out")


def test_sidecar_must_match_data_and_session(tmp_path):
    import hashlib
    def corrupt(m, files):
        entry = m["files"][1]
        evidence = json.loads(files[entry["file_name"]])
        evidence["imu_samples"] = 999
        files[entry["file_name"]] = json.dumps(evidence).encode()
        entry.update(bytes=len(files[entry["file_name"]]), sha256=hashlib.sha256(files[entry["file_name"]]).hexdigest())
    source = tmp_path / "input.zip"
    archive_at(source, sidecar=True, mutate=corrupt)
    with pytest.raises(ArchiveRejected, match="imu_samples"):
        import_archive(source, tmp_path / "out")


def test_large_gap_overlap_rollback_are_preserved(tmp_path):
    raw = raw_bytes([imu(10020), imu(400060), imu(400060), imu(500)])
    source = tmp_path / "input.zip"
    archive_at(source, raw=raw)
    result = import_archive(source, tmp_path / "out")
    directory = Path(result.directory)
    rows = read_rows(next((directory / "derived").glob("*imu*.csv")))
    assert [int(r["packet_uptime_ms"]) for r in rows] == [10020, 10020, 400060, 400060, 400060, 400060, 500, 500]
    assert int(rows[2]["relative_sample_offset_ms"]) > 300000
    assert int(rows[-1]["relative_sample_offset_ms"]) < 0
    report = json.loads((directory / "quality.json").read_text())["files"][0]
    assert report["channels"]["imu"]["gaps"] == 1
    assert report["channels"]["imu"]["overlaps"] == 2
    assert report["channels"]["imu"]["rollbacks"] == 1


def test_multiple_files_share_one_reference_and_require_review(tmp_path):
    import hashlib
    def add_file(m, files):
        extra = raw_bytes([imu(10060)])
        name = m["session_id"] + "-ring-7-part2.rfbin"
        files[name] = extra
        m["files"].append(dict(file_name=name, role="raw", device_session_id=7, bytes=len(extra),
                               sha256=hashlib.sha256(extra).hexdigest(), simulated=False))
    source = tmp_path / "input.zip"
    archive_at(source, mutate=add_file)
    result = import_archive(source, tmp_path / "out")
    directory = Path(result.directory)
    assert len(read_rows(directory / "reference.csv")) == 1
    assert len(list((directory / "raw").glob("*.rfbin"))) == 2
    quality = json.loads((directory / "quality.json").read_text())
    assert "multiple_raw_files_require_overlap_review" in quality["analysis_reasons"]
    for report in quality["files"]:
        assert all(Path(report["source_file"]).stem in name for name in report["csv_files"])
    assert len(quality["files"][0]["csv_files"]) == 2
    assert len(quality["files"][1]["csv_files"]) == 1


def test_two_sessions_and_cross_midnight_remain_separate_with_no_daily_total(tmp_path):
    root = tmp_path / "out"
    for number, steps in [(1, 562), (2, 438)]:
        raw = raw_bytes()
        m = manifest_for(raw, session_number=number)
        m["ground_truth_steps"] = steps
        # Phone request timestamps may cross midnight; unknown device boundaries remain null.
        m["start_requested_at_ms"] = 1_801_094_340_000
        m["timing_warnings"] = ["phone_clock_order_uncertain"]
        source = tmp_path / f"input{number}.zip"
        archive_at(source, raw=raw, manifest=m)
        import_archive(source, root)
    output = tmp_path / "summary.csv"
    rows = summarize(root, output)
    assert len(rows) == 2 and {row["ground_truth_steps"] for row in rows} == {562, 438}
    assert all(row["daily_aggregation_eligible"] is False for row in rows)
    assert all(row["started_at_ms"] is None for row in rows)
    assert "1000" not in output.read_text()


def test_interrupted_publish_leaves_no_partial_session_and_retry_succeeds(tmp_path, monkeypatch):
    from backend.ringo_data import importer
    source = tmp_path / "input.zip"
    archive_at(source)
    original = importer.publish
    def interrupted(stage, destination):
        raise OSError("injected process interruption before rename")
    monkeypatch.setattr(importer, "publish", interrupted)
    with pytest.raises(OSError, match="interruption"):
        import_archive(source, tmp_path / "out")
    assert not list((tmp_path / "out" / "sessions").glob("*"))
    assert source.exists() and (tmp_path / "out" / ".import.lock").read_bytes() == b"ringfitness-os-lock-v1\n"
    monkeypatch.setattr(importer, "publish", original)
    assert import_archive(source, tmp_path / "out").status == "imported"


def test_existing_import_lock_prevents_concurrent_overwrite(tmp_path):
    source = tmp_path / "input.zip"
    archive_at(source)
    root = tmp_path / "out"
    root.mkdir()
    (root / ".import.lock").write_text("synthetic test owner")
    with pytest.raises(ValidationError, match="locked"):
        import_archive(source, root)
    assert (root / ".import.lock").read_text() == "synthetic test owner"


@pytest.mark.parametrize("mode", ["same_fingerprint", "single_changed_anchor", "empty_with_data",
                                  "status_not_listed", "duplicate_ids", "too_many_records"])
def test_invalid_start_baseline_rejected(tmp_path, mode):
    def change(m, _):
        record = copy.deepcopy(m["device_record_evidence"]["record"])
        baseline = m["start_baseline"]
        if mode == "empty_with_data":
            baseline["status"]["bytes"] = 1
        else:
            baseline["records"] = [record]
            baseline["status"] = copy.deepcopy(m["stop_status_evidence"])
            if mode == "single_changed_anchor":
                record["uptime_ms"] -= 1
            elif mode == "status_not_listed":
                baseline["status"]["device_session_id"] = 6
            elif mode == "duplicate_ids":
                baseline["records"].append(record.copy())
            elif mode == "too_many_records":
                baseline["records"] = [dict(record, device_session_id=i + 1) for i in range(256)]
    source = tmp_path / "input.zip"
    archive_at(source, mutate=change)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


def test_reused_id_with_two_changed_nonzero_anchors_is_admitted(tmp_path):
    def change(m, _):
        old = copy.deepcopy(m["device_record_evidence"]["record"])
        old["uptime_ms"] -= 100
        old["unix_ms"] -= 1000
        m["start_baseline"]["records"] = [old]
        m["start_baseline"]["status"] = copy.deepcopy(m["stop_status_evidence"])
    source = tmp_path / "input.zip"
    archive_at(source, mutate=change)
    assert import_archive(source, tmp_path / "out").status == "imported"


@pytest.mark.parametrize("mode", ["missing_stop", "still_collecting", "counter_rollback", "placement_list"])
def test_malformed_complete_evidence_is_rejected_and_batch_continues(tmp_path, mode):
    def change(m, _):
        m.update(ground_truth_status="missing", ground_truth_steps=None, ground_truth_recorded_at_ms=None,
                 ground_truth_reason="No display")
        if mode == "missing_stop":
            m.update(stop_requested_at_ms=None, stop_confirmed_at_ms=None, stop_status_evidence=None)
        elif mode == "still_collecting":
            m["device_record_evidence"]["status"]["collecting"] = True
        elif mode == "counter_rollback":
            m["start_status_evidence"]["bytes"] += 1
        else:
            m["ring_placement"] = []
    bad, good = tmp_path / "bad.zip", tmp_path / "good.zip"
    archive_at(bad, mutate=change)
    archive_at(good)
    assert main(["import", str(bad), str(good), "--output", str(tmp_path / "out")]) == 2
    assert len(list((tmp_path / "out" / "sessions").iterdir())) == 1


def test_missing_derived_artifact_fails_duplicate_check(tmp_path):
    source = tmp_path / "input.zip"
    archive_at(source)
    result = import_archive(source, tmp_path / "out")
    (Path(result.directory) / "quality.json").unlink()
    with pytest.raises(ArchiveRejected, match="inventory"):
        import_archive(source, tmp_path / "out")


def test_zip_compression_and_archive_size_quotas(tmp_path):
    source = tmp_path / "input.zip"
    archive_at(source, compression=zipfile.ZIP_DEFLATED)
    with pytest.raises(ArchiveRejected, match="ratio"):
        import_archive(source, tmp_path / "out", Limits(compression_ratio=1))
    with pytest.raises(ValidationError, match="archive size"):
        import_archive(source, tmp_path / "other", Limits(archive_bytes=1))


def test_same_raw_content_across_sessions_is_flagged(tmp_path):
    for number in (1, 2):
        source = tmp_path / f"input{number}.zip"
        raw = raw_bytes()
        archive_at(source, raw=raw, manifest=manifest_for(raw, session_number=number))
        import_archive(source, tmp_path / "out")
    rows = summarize(tmp_path / "out", tmp_path / "summary.csv")
    assert all("raw_content_shared_across_sessions" in row["analysis_reasons"] for row in rows)


def test_device_unix_zero_stays_unknown_and_uptime_wrap_is_not_repaired(tmp_path):
    raw = raw_bytes([imu(0xFFFFFFF0), imu(20)], uptime=0xFFFFFFDC, anchor=0)
    source = tmp_path / "input.zip"
    archive_at(source, raw=raw)
    result = import_archive(source, tmp_path / "out")
    directory = Path(result.directory)
    rows = read_rows(next((directory / "derived").glob("*imu*.csv")))
    assert all(row["device_anchor_estimated_unix_ms"] == "" for row in rows)
    assert [int(row["ring_uptime_ms"]) for row in rows] == [0xFFFFFFDC, 0xFFFFFFF0, 0, 20]
    quality = json.loads((directory / "quality.json").read_text())
    assert quality["files"][0]["channels"]["imu"]["rollbacks"] == 1


def test_corrupt_compressed_entry_is_quarantined_and_batch_continues(tmp_path):
    bad, good = tmp_path / "bad.zip", tmp_path / "good.zip"
    archive_at(bad, compression=zipfile.ZIP_DEFLATED)
    archive_at(good)
    with zipfile.ZipFile(bad) as archive:
        entry = archive.getinfo("manifest.json")
        offset = entry.header_offset
    data = bytearray(bad.read_bytes())
    name_len, extra_len = struct.unpack_from("<HH", data, offset + 26)
    data[offset + 30 + name_len + extra_len] = 0xFF
    bad.write_bytes(data)
    root = tmp_path / "out"
    assert main(["import", str(bad), str(good), "--output", str(root)]) == 2
    assert len(list((root / "sessions").iterdir())) == 1
    assert sha256(root / "rejected" / sha256(bad) / "source.zip") == sha256(bad)
