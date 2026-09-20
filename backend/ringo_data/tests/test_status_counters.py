"""Preserve counter order across start, stop, and final record observations."""

import json
from pathlib import Path

import pytest

from backend.ringo_data.importer import ArchiveRejected, import_archive, sha256
from backend.ringo_data.tests.test_activity_sessions import activity_manifest
from backend.ringo_data.tests.test_charging_recovery import recovery_manifest
from backend.ringo_data.tests.test_importer import archive_at, manifest_for, raw_bytes
from backend.ringo_data.tests.test_unknown_time_start import unknown_start_manifest


@pytest.fixture(params=[
    (2, "free_living", False), (3, "free_living", True),
    (4, "walking", False), (4, "running", False),
    (4, "walking", True), (4, "running", True),
    (5, "free_living", False), (5, "free_living", True),
    (5, "walking", False), (5, "running", False),
    (5, "walking", True), (5, "running", True),
], ids=lambda case: f"v{case[0]}-{case[1]}-{'charging' if case[2] else 'normal'}")
def counter_manifest(request):
    version, activity, charging = request.param
    if version == 2:
        return manifest_for(raw_bytes())
    if version == 3:
        return recovery_manifest()
    if version == 4:
        return activity_manifest(activity, recovery=charging)
    manifest = unknown_start_manifest()
    if activity != "free_living":
        manifest.update(activity_schema="daily_activity_v3", activity_code=activity,
                        activity_selection_source="participant")
    if charging:
        baseline = manifest["start_baseline"]
        baseline["status"]["error_code"] = -16
        evidence = recovery_manifest()["start_baseline"]["charging_recovery_evidence"]
        observed = baseline["observed_at_ms"]
        generation = baseline["unknown_time_start_evidence"]["connection_generation"]
        evidence.update(status_received_at_ms=observed, battery_received_at_ms=observed,
                        checked_at_ms=observed, status_connection_generation=generation,
                        battery_connection_generation=generation)
        baseline["charging_recovery_evidence"] = evidence
    return manifest


@pytest.mark.parametrize("rollback", ["bytes", "records", "both_to_zero"])
def test_stop_counter_rollback_is_rejected_and_original_retained(tmp_path, counter_manifest, rollback):
    stop = counter_manifest["stop_status_evidence"]
    if rollback == "both_to_zero":
        stop.update(bytes=0, records=0)
    else:
        stop[rollback] -= 1
    source = tmp_path / "input.zip"
    archive_at(source, manifest=counter_manifest)
    root = tmp_path / "out"
    with pytest.raises(ArchiveRejected, match="counters moved backwards") as raised:
        import_archive(source, root)
    assert sha256(Path(raised.value.directory) / "source.zip") == sha256(source)
    assert not list((root / "sessions").glob("*"))


@pytest.mark.parametrize("counter", ["bytes", "records"])
def test_final_record_counter_rollback_is_rejected(tmp_path, counter_manifest, counter):
    counter_manifest["stop_status_evidence"][counter] += 1
    source = tmp_path / "input.zip"
    archive_at(source, manifest=counter_manifest)
    with pytest.raises(ArchiveRejected, match="counters moved backwards"):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("observations", ["zero", "growing", "unchanged", "stop_at_final", "all_equal"])
def test_nondecreasing_counters_preserve_each_observation(tmp_path, counter_manifest, observations):
    start = counter_manifest["start_status_evidence"]
    stop = counter_manifest["stop_status_evidence"]
    final = counter_manifest["device_record_evidence"]["record"]
    for counter in ("bytes", "records"):
        start[counter], stop[counter] = {
            "zero": (0, 0),
            "growing": (0, final[counter] - 1),
            "unchanged": (final[counter] - 1, final[counter] - 1),
            "stop_at_final": (final[counter] - 1, final[counter]),
            "all_equal": (final[counter], final[counter]),
        }[observations]
    source = tmp_path / "input.zip"
    expected = archive_at(source, manifest=counter_manifest)
    root = tmp_path / "out"
    result = import_archive(source, root)
    assert result.status == "imported"
    assert json.loads((Path(result.directory) / "manifest.json").read_text()) == expected
    assert import_archive(source, root).status == "already_imported"
