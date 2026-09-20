import copy
from uuid import UUID

import pytest

from backend.ringo_data.importer import ArchiveRejected, import_archive
from backend.ringo_data.tests.test_importer import archive_at, manifest_for, raw_bytes


def unknown_start_manifest():
    m = manifest_for(raw_bytes())
    m["version"] = 5
    old = dict(device_session_id=7, bytes=900, records=10, uptime_ms=1000, unix_ms=0)
    clock_at = m["device_record_evidence"]["record"]["unix_ms"] - 1000
    m["start_baseline"] = dict(
        status=dict(collecting=False, error_code=0, device_session_id=7, bytes=900, records=10),
        records=[old], observed_at_ms=clock_at + 20,
        unknown_time_start_evidence=dict(version=1, record=copy.deepcopy(old), backup_id=str(UUID(int=20)),
            owner_id=str(UUID(int=21)), raw_sha256="a" * 64, connection_generation=2, preserved_at_ms=clock_at - 100,
            clock=dict(attempt_id=str(UUID(int=22)), ring_address=m["ring_address"], connection_generation=2,
                requested_at_ms=clock_at, received_at_ms=clock_at + 10, requested_elapsed_ms=100,
                received_elapsed_ms=110, device_unix_ms=clock_at, device_uptime_ms=9000)))
    return m


def test_preserved_unknown_record_and_clock_prove_same_id_new_session(tmp_path):
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=unknown_start_manifest())
    assert import_archive(source, tmp_path / "out").status == "imported"
    assert import_archive(source, tmp_path / "out").status == "already_imported"


def test_preserved_flash_from_a_previous_boot_can_have_a_larger_uptime(tmp_path):
    m = unknown_start_manifest()
    m["start_baseline"]["records"][0]["uptime_ms"] = 900000
    m["start_baseline"]["unknown_time_start_evidence"]["record"]["uptime_ms"] = 900000
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=m)
    assert import_archive(source, tmp_path / "out").status == "imported"


@pytest.mark.parametrize("activity", ["free_living", "walking", "running"])
@pytest.mark.parametrize("charging", [False, True])
def test_version_five_preserves_activity_and_charging_contracts(tmp_path, activity, charging):
    m = unknown_start_manifest()
    if activity != "free_living":
        m.update(activity_schema="daily_activity_v3", activity_code=activity, activity_selection_source="participant")
    if charging:
        baseline = m["start_baseline"]
        baseline["status"]["error_code"] = -16
        observed = baseline["observed_at_ms"]
        baseline["charging_recovery_evidence"] = dict(status_error_reason=1, battery_charge_status=0,
            status_received_at_ms=observed, battery_received_at_ms=observed,
            checked_at_ms=observed + 10, status_connection_generation=2, battery_connection_generation=2)
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=m)
    assert import_archive(source, tmp_path / "out").status == "imported"


@pytest.mark.parametrize("field,value", [("raw_sha256", "x"), ("connection_generation", 3),
    ("preserved_at_ms", 2**62), ("backup_id", "../bad"), ("version", 2)])
def test_invalid_preservation_evidence_cannot_release_reused_id(tmp_path, field, value):
    m = unknown_start_manifest()
    m["start_baseline"]["unknown_time_start_evidence"][field] = value
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=m)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("field,value", [("device_uptime_ms", 10001), ("device_unix_ms", 1),
    ("received_at_ms", 1), ("received_elapsed_ms", 10000), ("ring_address", "00:00:00:00:00:02")])
def test_clock_proof_must_cover_new_record_on_same_ring(tmp_path, field, value):
    m = unknown_start_manifest()
    m["start_baseline"]["unknown_time_start_evidence"]["clock"][field] = value
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=m)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("version", [2, 3, 4])
def test_old_versions_cannot_gain_unknown_clock_override(tmp_path, version):
    m = unknown_start_manifest()
    m["version"] = version
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=m)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("mode", ["missing", "different_old", "unchanged_uptime", "zero_new_unix", "extra_clock_field"])
def test_version_five_does_not_invent_new_record_identity(tmp_path, mode):
    m = unknown_start_manifest()
    b = m["start_baseline"]
    e = b["unknown_time_start_evidence"]
    if mode == "missing":
        del b["unknown_time_start_evidence"]
    elif mode == "different_old":
        e["record"]["bytes"] += 1
    elif mode == "unchanged_uptime":
        e["record"]["uptime_ms"] = b["records"][0]["uptime_ms"] = 10000
    elif mode == "zero_new_unix":
        m["device_record_evidence"]["record"]["unix_ms"] = 0
    else:
        e["clock"]["ignore_errors"] = True
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=m)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


def test_different_numeric_id_also_requires_the_synchronized_clock(tmp_path):
    m = unknown_start_manifest()
    m["start_baseline"]["status"]["device_session_id"] = 6
    m["start_baseline"]["records"][0]["device_session_id"] = 6
    m["start_baseline"]["unknown_time_start_evidence"]["record"]["device_session_id"] = 6
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=m)
    assert import_archive(source, tmp_path / "out").status == "imported"
    m["device_record_evidence"]["record"]["unix_ms"] = 0
    archive_at(source, manifest=m)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "bad")
