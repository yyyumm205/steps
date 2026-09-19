import json
import zipfile
from pathlib import Path

import pytest

from backend.ringo_data.importer import ArchiveRejected, Limits, import_archive, sha256, summarize
from backend.ringo_data.tests.test_importer import archive_at, imu, manifest_for, ppg, raw_bytes, read_rows


def imported(tmp_path, raw=None, change=None):
    source = tmp_path / "source.zip"
    raw = raw if raw is not None else raw_bytes(anchor=0)
    manifest = manifest_for(raw)
    if change:
        change(manifest)
    archive_at(source, raw=raw, manifest=manifest)
    result = import_archive(source, tmp_path / "research")
    root = Path(result.directory)
    quality = json.loads((root / "quality.json").read_text())
    rows = {kind: read_rows(next((root / "derived").glob(f"*{kind}*.csv"))) for kind in ("imu", "ppg")
            if list((root / "derived").glob(f"*{kind}*.csv"))}
    return source, root, quality, rows


@pytest.mark.parametrize("anchor", [0, 1_800_000_000_000])
def test_phone_window_aligns_all_channels_with_one_offset_and_keeps_device_values(tmp_path, anchor):
    _, root, quality, channels = imported(tmp_path, raw_bytes(anchor=anchor))
    plan = quality["phone_time_alignment"]
    assert plan["status"] == "estimated"
    assert plan["phone_window_start_ms"] == 1_801_000_000_000
    assert plan["offset_min_ms"] == 1_801_000_000_000 - 10000
    assert plan["offset_max_ms"] == 1_801_000_004_000 - 10040
    assert plan["placement_half_width_ms"] == 1980
    rows = channels["imu"] + channels["ppg"]
    assert {int(r["phone_estimated_unix_ms"]) - int(r["ring_uptime_ms"]) for r in rows} == {
        plan["estimated_offset_ms"]}
    assert [int(r["phone_estimated_unix_ms"]) for r in channels["imu"]] == [1_801_000_001_980, 1_801_000_002_000]
    assert all(int(r["phone_earliest_unix_ms"]) <= int(r["phone_estimated_unix_ms"]) <=
               int(r["phone_latest_unix_ms"]) for r in rows)
    assert all(r["timestamp_unix_ms"] == r["timestamp_iso"] == "" for r in rows)
    assert rows[0]["device_anchor_estimated_unix_ms"] == (str(anchor) if anchor else "")
    assert all(r["phone_time_source"] == "phone_capture_window_v1" for r in rows)
    assert plan["clock_drift_measured"] is False
    assert plan["capture_boundaries_confirmed"] is False
    assert quality["absolute_sample_time_status"] == "unknown"
    assert quality["analysis_status"] == "pending_review"
    assert read_rows(root / "reference.csv")[0]["ground_truth_steps"] == "0"


def test_alignment_preserves_frozen_bytes_reference_and_idempotency(tmp_path):
    source, root, _, _ = imported(tmp_path)
    with zipfile.ZipFile(source) as archive:
        assert (root / "manifest.json").read_bytes() == archive.read("manifest.json")
        for path in (root / "raw").iterdir():
            assert path.read_bytes() == archive.read(path.name)
    assert sha256(root / "source.zip") == sha256(source)
    before = {p.relative_to(root): sha256(p) for p in root.rglob("*") if p.is_file()}
    assert import_archive(source, root.parent.parent).status == "already_imported"
    assert before == {p.relative_to(root): sha256(p) for p in root.rglob("*") if p.is_file()}
    receipt = json.loads((root / "import.json").read_text())
    assert receipt["importer_version"] == "0.2.0"
    assert receipt["artifacts"]["quality.json"] == sha256(root / "quality.json")


@pytest.mark.parametrize("packets,anchor,reason", [
    ([imu(10020), imu(500)], 0, "device_uptime_rollback_or_wrap"),
    ([imu(0xFFFFFFF0), imu(20)], 0, "device_uptime_rollback_or_wrap"),
    ([imu(10)], 0, "device_uptime_rollback_or_wrap"),
    ([imu(10020), imu(20000)], 0, "signal_span_exceeds_phone_window"),
])
def test_unsafe_timeline_stays_unaligned_without_rejecting_original(tmp_path, packets, anchor, reason):
    _, _, quality, channels = imported(tmp_path, raw_bytes(packets, anchor=anchor))
    assert quality["phone_time_alignment"]["reason"] == reason
    assert quality["phone_time_alignment"]["status"] == "unavailable"
    for rows in channels.values():
        assert all(r["phone_estimated_unix_ms"] == r["phone_earliest_unix_ms"] == r["phone_latest_unix_ms"] == ""
                   for r in rows)
        assert all(r["phone_time_source"] == "unavailable" for r in rows)


def test_phone_clock_warning_prevents_estimate(tmp_path):
    _, _, quality, _ = imported(tmp_path, change=lambda m: m.update(timing_warnings=["phone_clock_order_uncertain"]))
    assert quality["phone_time_alignment"]["reason"] == "phone_or_device_timing_warning"


def test_gaps_remain_gaps_and_are_not_stretched_to_fit_phone_duration(tmp_path):
    _, _, quality, channels = imported(tmp_path, raw_bytes([imu(10020), imu(10520)], anchor=0))
    rows = channels["imu"]
    times = [int(r["phone_estimated_unix_ms"]) for r in rows]
    assert [b - a for a, b in zip(times, times[1:])] == [20, 480, 20]
    assert quality["files"][0]["channels"]["imu"]["gaps"] == 1
    assert quality["sample_coverage_status"] == "not_assessed"


def test_midnight_estimate_keeps_whole_reference_and_no_daily_allocation(tmp_path):
    def midnight(m):
        delta = 1_800_057_598_000 - m["start_requested_at_ms"]  # 23:59:58 UTC
        for key in ("start_requested_at_ms", "start_confirmed_at_ms", "stop_requested_at_ms",
                    "stop_confirmed_at_ms", "reference_saved_at_ms", "ground_truth_recorded_at_ms",
                    "download_completed_at_ms"):
            m[key] += delta
    _, root, quality, channels = imported(tmp_path, raw_bytes([imu(10020), imu(12020)], anchor=0), midnight)
    dates = {r["phone_estimated_iso"][:10] for r in channels["imu"]}
    assert len(dates) == 2
    assert quality["daily_aggregation_eligible"] is False
    assert len(read_rows(root / "reference.csv")) == 1
    index = summarize(root.parent.parent, tmp_path / "index.csv")
    assert len(index) == 1 and index[0]["daily_aggregation_eligible"] is False


def test_multiple_files_are_preserved_without_independent_offsets(tmp_path):
    source = tmp_path / "source.zip"
    def extra_file(m, files):
        entry = dict(m["files"][0], file_name=m["session_id"] + "-second.rfbin")
        files[entry["file_name"]] = next(iter(files.values()))
        m["files"].append(entry)
    archive_at(source, mutate=extra_file)
    result = import_archive(source, tmp_path / "research")
    quality = json.loads((Path(result.directory) / "quality.json").read_text())
    assert quality["phone_time_alignment"]["reason"] == "multiple_files_need_shared_clock_evidence"


def test_alignment_columns_count_against_decoded_output_quota(tmp_path, monkeypatch):
    from backend.ringo_data import importer
    original = importer.add_phone_time
    entered = []
    def tracked(*args):
        entered.append(True)
        return original(*args)
    monkeypatch.setattr(importer, "add_phone_time", tracked)
    source = tmp_path / "source.zip"
    archive_at(source)
    with pytest.raises(ArchiveRejected, match="decoded output quota exceeded") as error:
        import_archive(source, tmp_path / "research", Limits(decoded_bytes=2000))
    assert entered == [True]
    assert sha256(Path(error.value.directory) / "source.zip") == sha256(source)
    assert not list((tmp_path / "research" / "sessions").glob("*"))


def test_remote_zero_step_shape_has_bounded_phone_placement(tmp_path):
    # Synthetic samples reproduce the observed timing envelope without participant data.
    raw = raw_bytes([imu(3925955, count=1), imu(3970095, count=1), ppg(3925992)],
                    uptime=3925360, anchor=0)
    def phone(m):
        m.update(start_requested_at_ms=1_801_000_000_000, start_confirmed_at_ms=1_801_000_002_015,
                 stop_requested_at_ms=1_801_000_044_735, stop_confirmed_at_ms=1_801_000_045_036,
                 reference_saved_at_ms=1_801_000_057_007, ground_truth_recorded_at_ms=1_801_000_057_007,
                 download_completed_at_ms=1_801_000_058_542)
    _, _, quality, _ = imported(tmp_path, raw, phone)
    plan = quality["phone_time_alignment"]
    assert plan["status"] == "estimated"
    assert plan["last_sample_uptime_ms"] - plan["first_sample_uptime_ms"] == 44143
    assert plan["placement_half_width_ms"] == 446.5
