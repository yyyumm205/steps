import copy
import json
from pathlib import Path

import pytest

from backend.ringo_data.importer import ArchiveRejected, import_archive, sha256, summarize
from backend.ringo_data.tests.test_importer import archive_at, manifest_for, raw_bytes, read_rows


def recovery_manifest(session_number=1):
    manifest = manifest_for(raw_bytes(), session_number)
    manifest["version"] = 3
    baseline = manifest["start_baseline"]
    baseline["status"]["error_code"] = -16
    observed = baseline["observed_at_ms"]
    baseline["charging_recovery_evidence"] = dict(
        status_error_reason=1, battery_charge_status=0,
        status_received_at_ms=observed, battery_received_at_ms=observed - 100,
        checked_at_ms=observed + 100, status_connection_generation=4,
        battery_connection_generation=4,
    )
    return manifest


@pytest.mark.parametrize("version", [2, 3])
def test_legacy_and_recovery_packages_import_once_without_rewriting_evidence(tmp_path, version):
    source = tmp_path / "input.zip"
    manifest = recovery_manifest() if version == 3 else manifest_for(raw_bytes())
    expected = archive_at(source, manifest=manifest, sidecar=True)
    original_digest = sha256(source)
    root = tmp_path / "out"
    result = import_archive(source, root)
    assert result.status == "imported"
    directory = Path(result.directory)
    assert json.loads((directory / "manifest.json").read_text()) == expected
    assert sha256(directory / "source.zip") == sha256(source) == original_digest
    assert read_rows(directory / "reference.csv")[0]["ground_truth_steps"] == "0"
    assert len(read_rows(next((directory / "derived").glob("*imu*.csv")))) == 2
    assert import_archive(source, root).status == "already_imported"
    assert len(list((root / "sessions").iterdir())) == 1
    rows = summarize(root, tmp_path / "index.csv")
    assert len(rows) == 1 and rows[0]["ground_truth_steps"] == 0
    assert rows[0]["analysis_status"] == "pending_review"


@pytest.mark.parametrize("age", [0, 5000])
def test_recovery_freshness_includes_both_endpoints(tmp_path, age):
    manifest = recovery_manifest()
    baseline = manifest["start_baseline"]
    evidence = baseline["charging_recovery_evidence"]
    evidence.update(battery_received_at_ms=baseline["observed_at_ms"],
                    checked_at_ms=baseline["observed_at_ms"] + age)
    source = tmp_path / "input.zip"
    archive_at(source, manifest=manifest)
    assert import_archive(source, tmp_path / "out").status == "imported"


@pytest.mark.parametrize("field,value", [
    ("status_error_reason", None), ("status_error_reason", 0),
    ("status_error_reason", 2), ("status_error_reason", True),
    ("battery_charge_status", 1), ("battery_charge_status", None),
    ("battery_charge_status", False), ("battery_received_at_ms", 0),
    ("battery_received_at_ms", -1), ("checked_at_ms", 0),
    ("status_received_at_ms", 1), ("status_connection_generation", 0),
    ("battery_connection_generation", 5), ("battery_connection_generation", True),
    ("battery_connection_generation", 4.0),
])
def test_invalid_recovery_evidence_is_rejected_and_source_preserved(tmp_path, field, value):
    manifest = recovery_manifest()
    manifest["start_baseline"]["charging_recovery_evidence"][field] = value
    source = tmp_path / "input.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected) as raised:
        import_archive(source, tmp_path / "out")
    assert sha256(Path(raised.value.directory) / "source.zip") == sha256(source)
    assert not list((tmp_path / "out" / "sessions").glob("*"))


@pytest.mark.parametrize("field,age", [
    ("battery_received_at_ms", -1), ("battery_received_at_ms", 5001),
    ("status_received_at_ms", -1), ("status_received_at_ms", 5001),
])
def test_recovery_rejects_future_or_stale_responses(tmp_path, field, age):
    manifest = recovery_manifest()
    baseline = manifest["start_baseline"]
    evidence = baseline["charging_recovery_evidence"]
    evidence[field] = evidence["checked_at_ms"] - age
    if field == "status_received_at_ms":
        baseline["observed_at_ms"] = evidence[field]
    source = tmp_path / "input.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected, match="stale or from the future"):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("mode", ["missing", "null", "missing_field", "extra_field", "list"])
def test_v3_requires_complete_exact_recovery_evidence(tmp_path, mode):
    manifest = recovery_manifest()
    baseline = manifest["start_baseline"]
    if mode == "missing":
        del baseline["charging_recovery_evidence"]
    elif mode == "null":
        baseline["charging_recovery_evidence"] = None
    elif mode == "missing_field":
        del baseline["charging_recovery_evidence"]["battery_charge_status"]
    elif mode == "extra_field":
        baseline["charging_recovery_evidence"]["ignore_errors"] = True
    else:
        baseline["charging_recovery_evidence"] = []
    source = tmp_path / "input.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected, match="charging recovery evidence"):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("mode", ["negative_error", "compatibility_evidence", "null_evidence"])
def test_v2_does_not_admit_charging_exception(tmp_path, mode):
    manifest = manifest_for(raw_bytes())
    baseline = manifest["start_baseline"]
    if mode == "negative_error":
        baseline["status"]["error_code"] = -16
    else:
        baseline["charging_recovery_evidence"] = (
            recovery_manifest()["start_baseline"]["charging_recovery_evidence"]
            if mode == "compatibility_evidence" else None
        )
    source = tmp_path / "input.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("key,value", [("error_code", 0), ("error_code", -15), ("collecting", True)])
def test_recovery_exception_only_applies_to_idle_charging_baseline(tmp_path, key, value):
    manifest = recovery_manifest()
    manifest["start_baseline"]["status"][key] = value
    source = tmp_path / "input.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("context", ["start_status_evidence", "stop_status_evidence", "device_record_evidence"])
def test_recovery_does_not_relax_success_or_complete_record_evidence(tmp_path, context):
    manifest = recovery_manifest()
    status = manifest[context]["status"] if context == "device_record_evidence" else manifest[context]
    status["error_code"] = -16
    source = tmp_path / "input.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("changed_anchors", [0, 1, 2])
def test_recovery_preserves_reused_record_id_fingerprint_requirement(tmp_path, changed_anchors):
    manifest = recovery_manifest()
    baseline = manifest["start_baseline"]
    old_record = copy.deepcopy(manifest["device_record_evidence"]["record"])
    if changed_anchors >= 1:
        old_record["uptime_ms"] -= 100
    if changed_anchors == 2:
        old_record["unix_ms"] -= 1000
    baseline["records"] = [old_record]
    baseline["status"] = dict(manifest["stop_status_evidence"], error_code=-16)
    source = tmp_path / "input.zip"
    archive_at(source, manifest=manifest)
    if changed_anchors == 2:
        assert import_archive(source, tmp_path / "out").status == "imported"
    else:
        with pytest.raises(ArchiveRejected, match="two changed nonzero time anchors"):
            import_archive(source, tmp_path / "out")
