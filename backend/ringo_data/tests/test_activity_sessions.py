"""Walking/running tasks remain distinct sessions with unlabelled samples."""

import json
from pathlib import Path

import pytest

from backend.ringo_data.importer import ArchiveRejected, import_archive, sha256, summarize
from backend.ringo_data.tests.test_charging_recovery import recovery_manifest
from backend.ringo_data.tests.test_importer import archive_at, manifest_for, raw_bytes, read_rows


def activity_manifest(activity="walking", *, recovery=False, session_number=1):
    manifest = recovery_manifest(session_number) if recovery else manifest_for(raw_bytes(), session_number)
    manifest.update(version=4, activity_schema="daily_activity_v3", activity_code=activity,
                    activity_selection_source="participant")
    return manifest


def artifact_hashes(directory):
    return {path.relative_to(directory).as_posix(): sha256(path)
            for path in directory.rglob("*") if path.is_file()}


@pytest.mark.parametrize("activity", ["walking", "running"])
@pytest.mark.parametrize("recovery", [False, True])
def test_selected_activity_survives_import_without_sample_truth(tmp_path, activity, recovery):
    source = tmp_path / "input.zip"
    expected = archive_at(source, manifest=activity_manifest(activity, recovery=recovery), sidecar=True)
    original_digest = sha256(source)
    root = tmp_path / "out"
    result = import_archive(source, root)
    directory = Path(result.directory)
    assert result.status == "imported"
    assert json.loads((directory / "manifest.json").read_text()) == expected
    assert sha256(directory / "source.zip") == original_digest
    reference = read_rows(directory / "reference.csv")
    assert len(reference) == 1 and reference[0]["activity_code"] == activity
    assert reference[0]["ground_truth_steps"] == "0"
    derived = list((directory / "derived").glob("*.csv"))
    assert len(derived) == 2
    for path in derived:
        rows = read_rows(path)
        assert len(rows) == 2
        assert all(row["activity_code"] == activity for row in rows)
        assert all(row["activity_truth"] == "" and row["activity_label_status"] == "unlabelled"
                   and row["activity_label_source"] == "none" for row in rows)
    before = artifact_hashes(directory)
    assert import_archive(source, root).status == "already_imported"
    assert artifact_hashes(directory) == before
    assert sha256(source) == original_digest
    rows = summarize(root, tmp_path / "index.csv")
    assert len(rows) == 1 and rows[0]["activity_code"] == activity
    assert rows[0]["analysis_status"] == "pending_review"
    assert rows[0]["daily_aggregation_eligible"] is False


@pytest.mark.parametrize("key,value", [
    ("version", 5), ("activity_schema", "daily_activity_v2"),
    ("activity_code", "free_living"), ("activity_code", "other"),
    ("activity_code", None), ("activity_code", ["walking", "running"]),
    ("activity_selection_source", "algorithm"), ("activity_selection_source", None),
    ("activity_label_status", "labelled"), ("activity_label_source", "participant"),
])
def test_selected_activity_rejects_inconsistent_contract(tmp_path, key, value):
    manifest = activity_manifest()
    manifest[key] = value
    source = tmp_path / "invalid.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected) as raised:
        import_archive(source, tmp_path / "out")
    assert sha256(Path(raised.value.directory) / "source.zip") == sha256(source)
    assert not list((tmp_path / "out" / "sessions").glob("*"))


def test_selected_activity_requires_selection_source(tmp_path):
    manifest = activity_manifest()
    del manifest["activity_selection_source"]
    source = tmp_path / "invalid.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected, match="manifest fields"):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("version", [2, 3])
@pytest.mark.parametrize("change", ["code", "schema", "selection_source"])
def test_legacy_versions_keep_the_original_activity_contract(tmp_path, version, change):
    manifest = recovery_manifest() if version == 3 else manifest_for(raw_bytes())
    if change == "code":
        manifest["activity_code"] = "walking"
    elif change == "schema":
        manifest["activity_schema"] = "daily_activity_v3"
    else:
        manifest["activity_selection_source"] = "participant"
    source = tmp_path / "invalid.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("mode", [
    "null_normal", "null_recovery", "missing_recovery", "partial_recovery", "extra_recovery",
    "normal_status_with_recovery", "wrong_error", "collecting", "stale", "different_connection",
    "charging_battery", "wrong_reason", "start_error", "stop_error", "record_error",
])
def test_v4_preserves_charging_recovery_constraints(tmp_path, mode):
    manifest = activity_manifest(recovery=mode != "null_normal")
    baseline = manifest["start_baseline"]
    if mode in ("null_normal", "null_recovery"):
        baseline["charging_recovery_evidence"] = None
    elif mode == "missing_recovery":
        del baseline["charging_recovery_evidence"]
    elif mode == "partial_recovery":
        del baseline["charging_recovery_evidence"]["status_error_reason"]
    elif mode == "extra_recovery":
        baseline["charging_recovery_evidence"]["ignore_errors"] = True
    elif mode == "normal_status_with_recovery":
        baseline["status"]["error_code"] = 0
    elif mode == "wrong_error":
        baseline["status"]["error_code"] = -15
    elif mode == "collecting":
        baseline["status"]["collecting"] = True
    elif mode == "stale":
        baseline["charging_recovery_evidence"]["checked_at_ms"] += 5001
    elif mode == "different_connection":
        baseline["charging_recovery_evidence"]["battery_connection_generation"] += 1
    elif mode == "charging_battery":
        baseline["charging_recovery_evidence"]["battery_charge_status"] = 1
    elif mode == "wrong_reason":
        baseline["charging_recovery_evidence"]["status_error_reason"] = 2
    elif mode == "start_error":
        manifest["start_status_evidence"]["error_code"] = -16
    elif mode == "stop_error":
        manifest["stop_status_evidence"]["error_code"] = -16
    elif mode == "record_error":
        manifest["device_record_evidence"]["status"]["error_code"] = -16
    source = tmp_path / "invalid.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out")


def test_mixed_versions_replace_old_index_with_separate_activity_rows(tmp_path):
    root = tmp_path / "out"
    inputs = [manifest_for(raw_bytes(), 1), recovery_manifest(2),
              activity_manifest("walking", session_number=3),
              activity_manifest("running", recovery=True, session_number=4)]
    snapshots = {}
    for number, manifest in enumerate(inputs, start=1):
        manifest["ground_truth_steps"] = number * 100
        source = tmp_path / f"input{number}.zip"
        archive_at(source, manifest=manifest)
        result = import_archive(source, root)
        directory = Path(result.directory)
        snapshots[directory] = artifact_hashes(directory)

    index = root / "session-index.csv"
    # An existing index from the earlier importer has no activity column.
    index.write_text("session_id,participant_id,ground_truth_steps\nold,fixture001,9\n", encoding="utf-8")
    rows = summarize(root, index)
    assert [(row["activity_code"], row["ground_truth_steps"]) for row in rows] == [
        ("free_living", 100), ("free_living", 200), ("walking", 300), ("running", 400)]
    assert [row["activity_code"] for row in read_rows(index)] == [
        "free_living", "free_living", "walking", "running"]
    assert all("raw_content_shared_across_sessions" in row["analysis_reasons"] for row in rows)
    assert all(row["daily_aggregation_eligible"] is False for row in rows)
    for directory, before in snapshots.items():
        assert artifact_hashes(directory) == before
    for number in range(1, 5):
        assert import_archive(tmp_path / f"input{number}.zip", root).status == "already_imported"
    for directory, before in snapshots.items():
        assert artifact_hashes(directory) == before


def test_changed_activity_with_same_session_id_preserves_both_packages(tmp_path):
    walking, running = tmp_path / "walking.zip", tmp_path / "running.zip"
    archive_at(walking, manifest=activity_manifest("walking"))
    archive_at(running, manifest=activity_manifest("running"))
    root = tmp_path / "out"
    original = Path(import_archive(walking, root).directory)
    before = artifact_hashes(original)
    result = import_archive(running, root)
    assert result.status == "conflict"
    assert artifact_hashes(original) == before
    conflict = Path(result.directory)
    assert sha256(conflict / "source.zip") == sha256(running)
    assert read_rows(conflict / "reference.csv")[0]["activity_code"] == "running"
    rows = summarize(root, tmp_path / "index.csv")
    assert len(rows) == 1 and rows[0]["activity_code"] == "walking"
