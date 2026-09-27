"""v8 multisport sessions retain optional HR evidence and all legacy identities."""

import copy
import csv
import hashlib
import io
import json
from pathlib import Path

import pytest

from backend.ringo_data.heart_rate import FIELDS, MAX_LINE_CHARACTERS
from backend.ringo_data.health_raw_v2 import iso
from backend.ringo_data.importer import ArchiveRejected, Limits, import_archive, sha256, summarize
from backend.ringo_data.schema import NON_STEP_ACTIVITIES, STEP_ACTIVITIES
from backend.ringo_data.tests.test_importer import archive_at, imu, ppg, raw_bytes, read_rows
from backend.ringo_data.tests.test_manifest_provenance import stop_provenance_manifest


def disabled_heart_rate():
    return dict(enabled=False, device_id=None, device_name=None, status="not_requested",
                timestamp_source="phone_receipt", started_at_ms=None, ended_at_ms=None,
                first_sample_at_ms=None, last_sample_at_ms=None, sample_count=0, gaps=[])


def multisport_manifest(activity="walking", *, enabled=False, base_version=4):
    value = stop_provenance_manifest(base_version=base_version)
    value.update(version=8, app_version="0.9.0", heart_rate=disabled_heart_rate())
    if activity != "free_living":
        value.update(activity_schema="daily_activity_v3", activity_code=activity,
                     activity_selection_source="participant")
    if activity in NON_STEP_ACTIVITIES:
        value.update(ground_truth_source="none", ground_truth_status="not_applicable",
                     ground_truth_steps=None, ground_truth_recorded_at_ms=None, ground_truth_reason=None)
    if enabled:
        start = value["start_requested_at_ms"]
        value["heart_rate"].update(enabled=True, device_id="A1B2C3D4", device_name="Polar H10 A1B2C3D4",
                                   status="recorded", started_at_ms=start, ended_at_ms=start + 4000,
                                   first_sample_at_ms=start + 500, last_sample_at_ms=start + 2000,
                                   sample_count=3)
    return value


def sample_rows(manifest):
    heart = manifest["heart_rate"]
    if not heart["enabled"] or heart["sample_count"] == 0:
        return []
    start = heart["started_at_ms"]
    return [[iso(epoch), str(epoch), str(index), "80", "0", "0", "true", "true", "true", "750|751", "768|769"]
            for index, epoch in enumerate((start + 500, start + 500, start + 2000), 1)]


def csv_bytes(rows, header=FIELDS):
    stream = io.StringIO(newline="")
    writer = csv.writer(stream, lineterminator="\n")
    writer.writerow(header)
    writer.writerows(rows)
    return stream.getvalue().encode("utf-8")


def capture_at(path, manifest=None, *, rows=None, content=None, include_csv=None, mutate=None, raw=None):
    manifest = copy.deepcopy(manifest if manifest is not None else multisport_manifest(enabled=True))
    if include_csv is None:
        include_csv = manifest["heart_rate"]["enabled"]
    if content is None:
        content = csv_bytes(sample_rows(manifest) if rows is None else rows)

    def add(m, files):
        if include_csv:
            name = m["session_id"] + "_polar_hr_rr.csv"
            files[name] = content
            m["files"].append(dict(file_name=name, role="polar_hr_rr", device_session_id=None,
                                  bytes=len(content), sha256=hashlib.sha256(content).hexdigest(), simulated=False))
        if mutate:
            mutate(m, files)
    return archive_at(path, manifest=manifest, mutate=add, raw=raw)


def quality_at(result):
    return json.loads((Path(result.directory) / "quality.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("activity", STEP_ACTIVITIES + NON_STEP_ACTIVITIES)
@pytest.mark.parametrize("enabled", [False, True])
def test_nine_sports_import_optional_hr_and_keep_reference_meaning(tmp_path, activity, enabled):
    source = tmp_path / "session.zip"
    manifest = capture_at(source, multisport_manifest(activity, enabled=enabled))
    original_digest = sha256(source)
    result = import_archive(source, tmp_path / "out")
    directory = Path(result.directory)
    assert result.status == "imported"
    assert sha256(directory / "source.zip") == original_digest
    assert json.loads((directory / "manifest.json").read_text()) == manifest
    reference = read_rows(directory / "reference.csv")[0]
    assert reference["activity_code"] == activity
    assert reference["ground_truth_steps"] == ("" if activity in NON_STEP_ACTIVITIES else "0")
    assert reference["ground_truth_status"] == ("not_applicable" if activity in NON_STEP_ACTIVITIES else "valid")
    quality = quality_at(result)
    assert "reference_not_applicable" not in quality["analysis_reasons"]
    assert "reference_missing" not in quality["analysis_reasons"]
    assert quality["heart_rate"]["enabled"] is enabled
    assert quality["heart_rate"]["timestamp_source"] == "phone_receipt"
    assert quality["heart_rate"]["sample_coverage_status"] == "not_assessed"
    assert quality["heart_rate"]["physiological_sample_time_known"] is False
    if enabled:
        original = directory / "raw" / (manifest["session_id"] + "_polar_hr_rr.csv")
        assert original.read_bytes() == csv_bytes(sample_rows(manifest))
        normalized = read_rows(directory / "derived" / quality["heart_rate"]["normalized_csv"])
        assert len(normalized) == 3
        assert all(row["participant_id"] == manifest["participant_id"] and row["activity_code"] == activity and
                   row["timestamp_source"] == "phone_receipt" for row in normalized)
        assert [row["timestamp_unix_ms"] for row in normalized] == [row[1] for row in sample_rows(manifest)]
        assert quality["heart_rate"]["same_receipt_timestamp_rows"] == 1
        assert quality["heart_rate"]["rr_interval_count"] == 6
    else:
        assert not list((directory / "derived").glob("*polar*.csv"))
    before = {p.relative_to(directory).as_posix(): sha256(p) for p in directory.rglob("*") if p.is_file()}
    assert import_archive(source, tmp_path / "out").status == "already_imported"
    assert before == {p.relative_to(directory).as_posix(): sha256(p) for p in directory.rglob("*") if p.is_file()}
    index = summarize(tmp_path / "out", tmp_path / "index.csv")
    assert len(index) == 1
    assert index[0]["ground_truth_steps"] is None if activity in NON_STEP_ACTIVITIES else index[0]["ground_truth_steps"] == 0
    assert index[0]["heart_rate_enabled"] is enabled
    assert index[0]["heart_rate_sample_count"] == (3 if enabled else 0)


@pytest.mark.parametrize("base_version", [2, 3, 4, 5])
def test_v8_retains_legacy_free_living_and_recovery_evidence(tmp_path, base_version):
    manifest = multisport_manifest("free_living", base_version=base_version)
    source = tmp_path / "session.zip"
    capture_at(source, manifest)
    result = import_archive(source, tmp_path / "out")
    assert result.status == "imported"
    assert read_rows(Path(result.directory) / "reference.csv")[0]["ground_truth_steps"] == "0"


@pytest.mark.parametrize("activity", NON_STEP_ACTIVITIES)
@pytest.mark.parametrize("key,value", [
    ("ground_truth_source", "external_pedometer"), ("ground_truth_status", "valid"),
    ("ground_truth_status", "missing"), ("ground_truth_steps", 0),
    ("ground_truth_recorded_at_ms", 1), ("ground_truth_reason", "none"),
])
def test_non_step_reference_never_becomes_zero_or_missing(tmp_path, activity, key, value):
    manifest = multisport_manifest(activity)
    manifest[key] = value
    source = tmp_path / "bad.zip"
    capture_at(source, manifest)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("key,value", [
    ("enabled", 1), ("enabled", "true"), ("device_id", None), ("device_name", " "),
    ("device_id", "H10\nspoof"), ("timestamp_source", "device_time"),
    ("status", "not_requested"), ("status", "partial"), ("status", "no_samples"),
    ("started_at_ms", None), ("ended_at_ms", -1), ("first_sample_at_ms", None),
    ("last_sample_at_ms", True), ("sample_count", -1), ("sample_count", 3.0),
    ("gaps", {}),
])
def test_invalid_heart_metadata_rejected(tmp_path, key, value):
    manifest = multisport_manifest(enabled=True)
    manifest["heart_rate"][key] = value
    source = tmp_path / "bad.zip"
    capture_at(source, manifest, content=csv_bytes(sample_rows(multisport_manifest(enabled=True))))
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("mutation", ["missing", "extra", "null"])
def test_v8_heart_metadata_exact_fields_required(tmp_path, mutation):
    def change(m, _):
        if mutation == "missing":
            del m["heart_rate"]["device_name"]
        elif mutation == "extra":
            m["heart_rate"]["automatic_fill"] = True
        else:
            m["heart_rate"] = None
    source = tmp_path / "bad.zip"
    capture_at(source, mutate=change)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("key,value", [
    ("device_id", "A1B2"), ("device_name", "Polar H10"), ("status", "recorded"),
    ("sample_count", 1), ("started_at_ms", 1), ("ended_at_ms", 1),
    ("first_sample_at_ms", 1), ("last_sample_at_ms", 1),
    ("gaps", [{"started_at_ms": 1, "ended_at_ms": 1, "reason": "no_data"}]),
])
def test_disabled_heart_rate_is_canonical_empty_state(tmp_path, key, value):
    manifest = multisport_manifest()
    manifest["heart_rate"][key] = value
    source = tmp_path / "bad.zip"
    capture_at(source, manifest)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("include_csv", [False, True])
def test_no_samples_preserved_without_fabricated_values(tmp_path, include_csv):
    manifest = multisport_manifest("football", enabled=True)
    heart = manifest["heart_rate"]
    heart.update(status="no_samples", sample_count=0, first_sample_at_ms=None, last_sample_at_ms=None)
    if not include_csv:
        heart["gaps"] = [dict(started_at_ms=heart["started_at_ms"], ended_at_ms=heart["ended_at_ms"], reason="storage_error")]
    source = tmp_path / "none.zip"
    capture_at(source, manifest, include_csv=include_csv)
    result = import_archive(source, tmp_path / "out")
    quality = quality_at(result)
    assert "heart_rate_no_samples" in quality["analysis_reasons"]
    assert quality["heart_rate"]["sample_count"] == 0
    if include_csv:
        assert read_rows(Path(result.directory) / "derived" / quality["heart_rate"]["normalized_csv"]) == []


@pytest.mark.parametrize("mode", ["missing_with_samples", "missing_without_storage_error", "disabled_with_csv"])
def test_hr_file_presence_matches_opt_in_and_storage_evidence(tmp_path, mode):
    manifest = multisport_manifest(enabled=mode != "disabled_with_csv")
    if mode == "missing_without_storage_error":
        manifest["heart_rate"].update(status="no_samples", sample_count=0,
                                     first_sample_at_ms=None, last_sample_at_ms=None)
    source = tmp_path / "bad.zip"
    capture_at(source, manifest, include_csv=mode == "disabled_with_csv")
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("reason", ["disconnected", "process_restart", "stream_error", "storage_error", "no_data"])
def test_partial_capture_preserves_each_gap_reason(tmp_path, reason):
    manifest = multisport_manifest(enabled=True)
    heart = manifest["heart_rate"]
    start = heart["started_at_ms"]
    heart.update(status="partial", gaps=[dict(started_at_ms=start + 700, ended_at_ms=start + 1500, reason=reason)])
    source = tmp_path / "partial.zip"
    capture_at(source, manifest)
    result = import_archive(source, tmp_path / "out")
    quality = quality_at(result)
    assert "heart_rate_partial" in quality["analysis_reasons"]
    assert quality["heart_rate"]["reported_gap_union_ms"] == 800
    assert quality["heart_rate"]["reported_gaps"] == heart["gaps"]
    assert quality["heart_rate"]["sample_count"] == 3


def test_overlapping_gap_reasons_do_not_double_count_elapsed_time(tmp_path):
    manifest = multisport_manifest(enabled=True)
    start = manifest["heart_rate"]["started_at_ms"]
    manifest["heart_rate"].update(status="partial", gaps=[
        dict(started_at_ms=start + 800, ended_at_ms=start + 1700, reason="disconnected"),
        dict(started_at_ms=start + 600, ended_at_ms=start + 1200, reason="stream_error"),
    ])
    source = tmp_path / "gaps.zip"
    capture_at(source, manifest)
    assert quality_at(import_archive(source, tmp_path / "out"))["heart_rate"]["reported_gap_union_ms"] == 1100


@pytest.mark.parametrize("mutation", ["unknown_reason", "unclosed", "extra", "no_partial"])
def test_invalid_or_unclosed_gaps_rejected(tmp_path, mutation):
    manifest = multisport_manifest(enabled=True)
    heart = manifest["heart_rate"]
    gap = dict(started_at_ms=heart["started_at_ms"], ended_at_ms=heart["ended_at_ms"], reason="disconnected")
    heart.update(status="partial", gaps=[gap])
    if mutation == "unknown_reason":
        gap["reason"] = "filled_in"
    elif mutation == "unclosed":
        gap["ended_at_ms"] = None
    elif mutation == "extra":
        gap["samples_guessed"] = 3
    else:
        heart["status"] = "recorded"
    source = tmp_path / "bad.zip"
    capture_at(source, manifest)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("column,value", [
    (0, "2026-02-30T00:00:00Z"), (0, "2026-01-01T00:00:00Z"), (0, "1801000000500"),
    (1, "NaN"), (1, "1.0"), (1, "-1"), (1, "0"), (1, "=100"), (1, "9223372036854775808"),
    (2, "0"), (2, "2"), (2, "01"), (3, "-1"), (3, "65536"), (3, "80.5"),
    (4, "NaN"), (5, "2147483648"), (6, "True"), (7, "1"), (8, "yes"),
    (9, "750||751"), (9, "750.0|751"), (9, "65536|750"), (10, "768"),
])
def test_malformed_hr_rows_rejected_with_frozen_original_preserved(tmp_path, column, value):
    manifest = multisport_manifest(enabled=True)
    rows = sample_rows(manifest)
    rows[0][column] = value
    source = tmp_path / "bad.zip"
    capture_at(source, manifest, rows=rows)
    with pytest.raises(ArchiveRejected) as failure:
        import_archive(source, tmp_path / "out")
    assert sha256(Path(failure.value.directory) / "source.zip") == sha256(source)
    assert not list((tmp_path / "out" / "sessions").glob("*"))


@pytest.mark.parametrize("mutation", ["too_few", "too_many", "skip_index", "first_bound", "last_bound",
                                    "extra_column", "missing_column", "header", "blank_row", "line_quota"])
def test_csv_structure_and_manifest_cross_checks(tmp_path, mutation):
    manifest = multisport_manifest(enabled=True)
    rows, header = sample_rows(manifest), FIELDS[:]
    content = None
    if mutation == "too_few":
        rows.pop()
    elif mutation == "too_many":
        rows.append(rows[-1][:])
        rows[-1][2] = "4"
    elif mutation == "skip_index":
        rows[1][2] = "3"
    elif mutation == "first_bound":
        manifest["heart_rate"]["first_sample_at_ms"] += 1
    elif mutation == "last_bound":
        manifest["heart_rate"]["last_sample_at_ms"] += 1
    elif mutation == "extra_column":
        rows[0].append("new")
    elif mutation == "missing_column":
        rows[0].pop()
    elif mutation == "header":
        header[0] = "device_time"
    elif mutation == "blank_row":
        rows.insert(1, [])
    elif mutation == "line_quota":
        rows[0][9] = "0" * MAX_LINE_CHARACTERS
    content = csv_bytes(rows, header)
    source = tmp_path / "bad.zip"
    capture_at(source, manifest, content=content)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("mutation", ["rollback", "outside", "reverse_capture", "reverse_gap", "outside_gap"])
def test_phone_clock_changes_preserve_all_real_rows_and_flag_uncertainty(tmp_path, mutation):
    manifest = multisport_manifest(enabled=True)
    heart = manifest["heart_rate"]
    rows = sample_rows(manifest)
    if mutation == "rollback":
        epoch = heart["started_at_ms"] + 250
        rows[-1][0:2] = [iso(epoch), str(epoch)]
        heart["last_sample_at_ms"] = epoch
    elif mutation == "outside":
        epoch = heart["started_at_ms"] - 1000
        rows[0][0:2] = [iso(epoch), str(epoch)]
        heart["first_sample_at_ms"] = epoch
    elif mutation == "reverse_capture":
        heart["ended_at_ms"] = heart["started_at_ms"] - 5000
    else:
        start = heart["started_at_ms"]
        heart.update(status="partial", gaps=[dict(started_at_ms=start + 500,
            ended_at_ms=start + (250 if mutation == "reverse_gap" else 7000), reason="process_restart")])
    source = tmp_path / "clock.zip"
    raw_csv = csv_bytes(rows)
    capture_at(source, manifest, content=raw_csv)
    result = import_archive(source, tmp_path / "out")
    quality = quality_at(result)
    assert any("heart_rate_" in reason and "uncertain" in reason for reason in quality["analysis_reasons"])
    report = quality["heart_rate"]
    assert report["sample_count"] == 3
    assert report["sample_receipt_span_ms"] is None
    assert report["reported_gap_union_ms"] is None
    directory = Path(result.directory)
    assert (directory / "raw" / report["original_csv"]).read_bytes() == raw_csv
    normalized = read_rows(directory / "derived" / report["normalized_csv"])
    assert [row["timestamp_unix_ms"] for row in normalized] == [row[1] for row in rows]
    if mutation in ("rollback", "reverse_capture", "reverse_gap"):
        assert quality["phone_time_alignment"]["status"] == "unavailable"
        assert quality["phone_time_alignment"]["reason"] == "phone_or_device_timing_warning"
        for file_report in quality["files"]:
            for name in file_report["csv_files"]:
                ring_rows = read_rows(directory / "derived" / name)
                assert all(row["phone_time_source"] == "unavailable" and
                           row["phone_estimated_unix_ms"] == row["phone_earliest_unix_ms"] ==
                           row["phone_latest_unix_ms"] == "" for row in ring_rows)
        assert json.loads((directory / "manifest.json").read_text())["timing_warnings"] == []


def test_hr_quality_keeps_zero_hr_and_sdk_specific_values(tmp_path):
    manifest = multisport_manifest(enabled=True)
    rows = sample_rows(manifest)
    rows[0][3:6] = ["0", "-1", "-1"]
    rows[0][8] = "false"
    rows[0][9:11] = ["0|751", "0|769"]
    source = tmp_path / "zero.zip"
    capture_at(source, manifest, rows=rows)
    quality = quality_at(import_archive(source, tmp_path / "out"))
    assert quality["heart_rate"]["zero_hr_samples"] == 1
    assert quality["heart_rate"]["contact_not_detected_samples"] == 1
    assert quality["heart_rate"]["zero_rr_intervals"] == 1
    assert "heart_rate_signal_quality_requires_review" in quality["analysis_reasons"]


def test_hr_without_rr_or_contact_support_preserves_empty_columns(tmp_path):
    manifest = multisport_manifest(enabled=True)
    rows = sample_rows(manifest)
    for row in rows:
        row[6:] = ["false", "false", "false", "", ""]
    source = tmp_path / "hr-only.zip"
    original = csv_bytes(rows)
    capture_at(source, manifest, content=original)
    result = import_archive(source, tmp_path / "out")
    quality = quality_at(result)
    report = quality["heart_rate"]
    assert report["rr_sample_rows"] == report["rr_interval_count"] == 0
    assert report["contact_unsupported_samples"] == 3
    assert report["contact_not_detected_samples"] == 0
    assert "heart_rate_signal_quality_requires_review" not in quality["analysis_reasons"]
    directory = Path(result.directory)
    assert (directory / "raw" / report["original_csv"]).read_bytes() == original
    assert all(row["rr_ms"] == row["rr_1_1024s"] == "" for row in
               read_rows(directory / "derived" / report["normalized_csv"]))


def test_single_hr_sample_retains_zero_span_without_inventing_an_interval(tmp_path):
    manifest = multisport_manifest(enabled=True)
    heart = manifest["heart_rate"]
    heart.update(sample_count=1, last_sample_at_ms=heart["first_sample_at_ms"])
    rows = sample_rows(manifest)[:1]
    source = tmp_path / "single.zip"
    capture_at(source, manifest, rows=rows)
    report = quality_at(import_archive(source, tmp_path / "out"))["heart_rate"]
    assert report["sample_count"] == 1
    assert report["sample_receipt_span_ms"] == 0
    assert report["maximum_receipt_interval_ms"] is None
    assert report["first_sample_delay_ms"] == 500
    assert report["last_sample_to_end_ms"] == 3500


@pytest.mark.parametrize("invalid_row", [b"\xff\xfe\n", b'"unterminated\n'])
def test_invalid_csv_encoding_or_quoting_is_rejected_with_original_retained(tmp_path, invalid_row):
    source = tmp_path / "bad.zip"
    capture_at(source, content=csv_bytes([]) + invalid_row)
    with pytest.raises(ArchiveRejected) as failure:
        import_archive(source, tmp_path / "out")
    assert sha256(Path(failure.value.directory) / "source.zip") == sha256(source)
    assert not list((tmp_path / "out" / "sessions").glob("*"))


@pytest.mark.parametrize("mutation", ["path", "foreign_session", "ring_id", "wrong_role", "hash", "length", "simulated"])
def test_hr_files_follow_exact_ownership_and_integrity_contract(tmp_path, mutation):
    def change(m, files):
        entry = m["files"][-1]
        if mutation in ("path", "foreign_session"):
            old = entry["file_name"]
            entry["file_name"] = ("nested/" + old) if mutation == "path" else old.replace(m["session_id"], "x" * 36)
            files[entry["file_name"]] = files.pop(old)
        elif mutation == "ring_id":
            entry["device_session_id"] = m["device_session_id"]
        elif mutation == "wrong_role":
            entry["role"] = "raw"
        elif mutation == "hash":
            entry["sha256"] = "0" * 64
        elif mutation == "length":
            entry["bytes"] += 1
        else:
            entry["simulated"] = True
    source = tmp_path / "bad.zip"
    capture_at(source, mutate=change)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


def test_different_hr_zip_for_same_session_conflicts_without_overwriting(tmp_path):
    first, second = tmp_path / "first.zip", tmp_path / "changed.zip"
    manifest = capture_at(first)
    first_result = import_archive(first, tmp_path / "out")
    rows = sample_rows(manifest)
    rows[0][3] = "81"
    capture_at(second, manifest, rows=rows)
    assert import_archive(second, tmp_path / "out").status == "conflict"
    assert sha256(Path(first_result.directory) / "source.zip") == sha256(first)
    assert len(summarize(tmp_path / "out", tmp_path / "index.csv")) == 1


def test_changed_hr_device_identity_for_same_session_is_isolated(tmp_path):
    first, second = tmp_path / "first.zip", tmp_path / "other-device.zip"
    manifest = capture_at(first)
    root = tmp_path / "out"
    original = import_archive(first, root)
    manifest["heart_rate"].update(device_id="E5F6A7B8", device_name="Polar H10 E5F6A7B8")
    capture_at(second, manifest)
    conflict = import_archive(second, root)
    assert conflict.status == "conflict"
    assert quality_at(original)["heart_rate"]["device_id"] == "A1B2C3D4"
    assert quality_at(conflict)["heart_rate"]["device_id"] == "E5F6A7B8"
    assert sha256(Path(original.directory) / "source.zip") == sha256(first)
    assert sha256(Path(conflict.directory) / "source.zip") == sha256(second)
    assert import_archive(second, root).directory == conflict.directory
    assert len(summarize(root, tmp_path / "index.csv")) == 1


def test_csv_final_output_quota_covers_ring_and_hr_together(tmp_path):
    source = tmp_path / "session.zip"
    capture_at(source)
    initial = import_archive(source, tmp_path / "first")
    final_size = sum(p.stat().st_size for p in (Path(initial.directory) / "derived").glob("*.csv"))
    assert import_archive(source, tmp_path / "exact", Limits(decoded_bytes=final_size)).status == "imported"
    with pytest.raises(ArchiveRejected, match="quota"):
        import_archive(source, tmp_path / "limited", Limits(decoded_bytes=final_size - 1))


@pytest.mark.parametrize("version", [6, 7])
def test_new_sports_and_hr_fields_are_not_reinterpreted_in_legacy_versions(tmp_path, version):
    manifest = stop_provenance_manifest()
    manifest["version"] = version
    if version == 6:
        del manifest["stop_origin"]
        del manifest["stop_observed_at_ms"]
    manifest["heart_rate"] = disabled_heart_rate()
    source = tmp_path / "bad.zip"
    capture_at(source, manifest)
    with pytest.raises(ArchiveRejected, match="manifest fields"):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("version", [6, 7])
@pytest.mark.parametrize("activity", NON_STEP_ACTIVITIES)
def test_legacy_versions_reject_new_sports_even_without_hr_metadata(tmp_path, version, activity):
    manifest = stop_provenance_manifest()
    manifest.update(version=version, activity_code=activity)
    if version == 6:
        del manifest["stop_origin"]
        del manifest["stop_observed_at_ms"]
    source = tmp_path / "legacy-sport.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected, match="unsupported activity_code"):
        import_archive(source, tmp_path / "out")


def test_mixed_legacy_v8_index_preserves_no_step_null_and_legacy_original(tmp_path):
    legacy = tmp_path / "legacy.zip"
    legacy_manifest = archive_at(legacy)
    root = tmp_path / "out"
    legacy_result = import_archive(legacy, root)
    # Emulate the previous activity-aware catalog, then upgrade it in place.
    old_fields = ["session_id", "participant_id", "activity_code", "ground_truth_steps", "ground_truth_status",
                  "started_at_ms", "ended_at_ms", "analysis_status", "daily_aggregation_eligible", "analysis_reasons"]
    old_row = summarize(root, tmp_path / "index.csv")[0]
    with (tmp_path / "index.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=old_fields)
        writer.writeheader()
        writer.writerow({field: old_row[field] for field in old_fields})
    raw = raw_bytes([imu(10120), ppg(10140)])
    manifest = multisport_manifest("badminton")
    manifest["session_id"] = "00000000-0000-0000-0000-000000000002"
    source = tmp_path / "sport.zip"
    capture_at(source, manifest, raw=raw)
    import_archive(source, root)
    rows = summarize(root, tmp_path / "index.csv")
    assert len(rows) == 2
    assert rows[0]["ground_truth_steps"] == 0 and rows[0]["heart_rate_status"] == ""
    assert rows[1]["ground_truth_steps"] is None and rows[1]["ground_truth_status"] == "not_applicable"
    assert sha256(Path(legacy_result.directory) / "source.zip") == sha256(legacy)
    assert json.loads((Path(legacy_result.directory) / "manifest.json").read_text()) == legacy_manifest
