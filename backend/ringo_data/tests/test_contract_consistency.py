"""Cross-check upload admission against the Android session evidence rules."""

import copy
from pathlib import Path

import pytest

from backend.ringo_data.importer import ArchiveRejected, import_archive, sha256
from backend.ringo_data.tests.test_charging_recovery import recovery_manifest
from backend.ringo_data.tests.test_importer import archive_at, manifest_for, raw_bytes
from backend.ringo_data.tests.test_unknown_time_start import unknown_start_manifest


def manifest_version(version):
    if version == 5:
        return unknown_start_manifest()
    if version == 3:
        return recovery_manifest()
    manifest = manifest_for(raw_bytes())
    if version == 4:
        manifest.update(version=4, activity_schema="daily_activity_v3", activity_code="walking",
                        activity_selection_source="participant")
    return manifest


def rejected_with_original(tmp_path, manifest, match):
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected, match=match) as error:
        import_archive(source, tmp_path / "out")
    assert sha256(Path(error.value.directory) / "source.zip") == sha256(source)
    assert not list((tmp_path / "out" / "sessions").glob("*"))


@pytest.mark.parametrize("version", [2, 3, 4, 5])
@pytest.mark.parametrize("counter", ["bytes", "records"])
def test_stop_cannot_regress_even_when_final_record_has_caught_up(tmp_path, version, counter):
    manifest = manifest_version(version)
    manifest["stop_status_evidence"][counter] -= 1
    rejected_with_original(tmp_path, manifest, "STOP counters moved backwards")


@pytest.mark.parametrize("version", [2, 3, 4, 5])
def test_monotonic_counters_and_frozen_package_remain_compatible(tmp_path, version):
    manifest = manifest_version(version)
    manifest["start_status_evidence"].update(bytes=0, records=0)
    manifest["stop_status_evidence"].update(bytes=1, records=1)
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=manifest)
    original = sha256(source)
    result = import_archive(source, tmp_path / "out")
    assert result.status == "imported"
    assert sha256(Path(result.directory) / "source.zip") == original
    assert import_archive(source, tmp_path / "out").status == "already_imported"


def combined_manifest(generation):
    manifest = unknown_start_manifest()
    baseline = manifest["start_baseline"]
    baseline["status"]["error_code"] = -16
    observed = baseline["observed_at_ms"]
    baseline["charging_recovery_evidence"] = dict(status_error_reason=1, battery_charge_status=0,
        status_received_at_ms=observed, battery_received_at_ms=observed,
        checked_at_ms=observed + 10, status_connection_generation=generation,
        battery_connection_generation=generation)
    return manifest


@pytest.mark.parametrize("generation", [1, 3, 999])
def test_separately_valid_recovery_proofs_must_share_one_connection(tmp_path, generation):
    rejected_with_original(tmp_path, combined_manifest(generation), "different connections")


def test_combined_recovery_proofs_on_one_connection_are_accepted(tmp_path):
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=combined_manifest(2))
    assert import_archive(source, tmp_path / "out").status == "imported"


@pytest.mark.parametrize("zone,offset", [("UTC", 0), ("UT", 0), ("GMT", 0), ("Z", 0),
    ("Asia/Shanghai", 28800), ("America/New_York", -14400), ("Etc/GMT+8", -28800), ("Europe/Paris", 7200),
    ("+8", 28800), ("-03", -10800), ("+0830", 30600), ("-083015", -30615), ("+08:30", 30600),
    ("-03:30:15", -12615), ("+18:00", 64800), ("-18:00:00", -64800), ("UTC+08:00", 28800),
    ("GMT-03:30", -12600), ("UT+00:00", 0)])
def test_java_zone_id_forms_import_without_host_tzdata(tmp_path, zone, offset):
    manifest = manifest_for(raw_bytes())
    manifest.update(time_zone_id=zone, utc_offset_seconds=offset)
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=manifest)
    assert import_archive(source, tmp_path / "out").status == "imported"


@pytest.mark.parametrize("zone", ["=1+1", "@SUM(1,2)", "\tUTC", "UTC\r\n=1+1", " UTC", "UTC ",
    "A", "1/Zone", "Asia:Shanghai", "UTC+19:00", "+18:00:01", "-18:01", "+08:60", "+08:00:60",
    "+8:00", "+080", "+08:", "GMT+", "+1+1", ""])
def test_invalid_zone_syntax_and_out_of_range_offsets_are_rejected(tmp_path, zone):
    manifest = manifest_for(raw_bytes())
    manifest["time_zone_id"] = zone
    rejected_with_original(tmp_path, manifest, "time_zone_id")


@pytest.mark.parametrize("zone,offset", [("UTC", 3600), ("GMT", -3600), ("Z", 28800),
    ("+08:00", 0), ("-03:30", 12600), ("UT+00:00", 1)])
def test_fixed_offset_cannot_disagree_with_saved_offset(tmp_path, zone, offset):
    manifest = manifest_for(raw_bytes())
    manifest.update(time_zone_id=zone, utc_offset_seconds=offset)
    rejected_with_original(tmp_path, manifest, "utc_offset_seconds disagree")


def test_region_offset_remains_the_captured_value_after_tzdb_changes(tmp_path):
    manifest = manifest_for(raw_bytes())
    manifest.update(time_zone_id="Asia/Shanghai", utc_offset_seconds=32400)
    source = tmp_path / "capture.zip"
    archive_at(source, manifest=manifest)
    assert import_archive(source, tmp_path / "out").status == "imported"


@pytest.mark.parametrize("path", [("start_status_evidence",), ("stop_status_evidence",),
    ("device_record_evidence",), ("device_record_evidence", "status"),
    ("device_record_evidence", "record"), ("start_baseline",), ("start_baseline", "status")])
def test_unknown_nested_contract_fields_are_not_silently_ignored(tmp_path, path):
    manifest = manifest_for(raw_bytes())
    target = manifest
    for field in path:
        target = target[field]
    target["ignore_errors"] = True
    rejected_with_original(tmp_path, manifest, "invalid fields")


def test_unknown_record_proof_cannot_hide_unrecognized_record_fields(tmp_path):
    manifest = unknown_start_manifest()
    baseline = manifest["start_baseline"]
    baseline["records"][0]["ignore_errors"] = True
    baseline["unknown_time_start_evidence"]["record"] = copy.deepcopy(baseline["records"][0])
    rejected_with_original(tmp_path, manifest, "invalid fields")
