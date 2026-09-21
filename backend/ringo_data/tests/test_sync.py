import json
import os
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

import pytest

from backend.ringo_data.__main__ import main
from backend.ringo_data import importer
from backend.ringo_data.importer import Limits, import_archive, import_lock, sha256, summarize
from backend.ringo_data.schema import ValidationError
from backend.ringo_data.sync import DirectorySync
from backend.ringo_data.tests.test_importer import archive_at, imu, manifest_for, ppg, raw_bytes, read_rows


class Clock:
    now = 0

    def __call__(self):
        return self.now


def scanner_at(tmp_path, **kwargs):
    incoming = tmp_path / "incoming"
    incoming.mkdir(exist_ok=True)
    clock = Clock()
    scanner = DirectorySync(incoming, tmp_path / "research", monotonic=clock, **kwargs)
    return scanner, incoming, clock


def settled_scan(scanner, clock):
    scanner.scan()
    clock.now += 11
    return scanner.scan()


def distinct_raw(number):
    return raw_bytes([imu(10020 + number), ppg(10040 + number)])


def test_stable_batch_imports_zero_and_distinct_sessions_then_preserves_identity_on_restart(tmp_path):
    scanner, incoming, clock = scanner_at(tmp_path)
    first = archive_at(incoming / "a.zip")
    raw = distinct_raw(2)
    second = archive_at(incoming / "b.zip", raw=raw, manifest=manifest_for(raw, 2),
                        mutate=lambda m, _: m.update(ground_truth_steps=73))
    before = {p.name: sha256(p) for p in incoming.iterdir()}
    observed = scanner.scan()
    assert observed["pending"] == 2
    assert observed["index"]["sessions"] == 0
    clock.now = 11
    result = scanner.scan()
    assert [e["status"] for e in result["files"]] == ["imported", "imported"]
    assert not result["failed"]
    rows = read_rows(scanner.output / "session-index.csv")
    assert {r["session_id"] for r in rows} == {first["session_id"], second["session_id"]}
    assert {r["ground_truth_steps"] for r in rows} == {"0", "73"}
    assert all(r["analysis_status"] == "pending_review" and r["daily_aggregation_eligible"] == "False" for r in rows)
    assert [e["status"] for e in scanner.scan()["files"]] == ["unchanged", "unchanged"]
    restarted = DirectorySync(incoming, scanner.output, monotonic=clock)
    again = settled_scan(restarted, clock)
    assert [e["status"] for e in again["files"]] == ["already_imported", "already_imported"]
    assert len(read_rows(scanner.output / "session-index.csv")) == 2
    assert {p.name: sha256(p) for p in incoming.iterdir()} == before


def test_partial_names_and_incomplete_zip_wait_until_final_file_is_complete(tmp_path):
    scanner, incoming, clock = scanner_at(tmp_path)
    archive_at(incoming / "finished.zip.part")
    archive_at(incoming / ".hidden.zip")
    (incoming / "growing.zip").write_bytes(b"PK\x03\x04")
    (incoming / "empty.zip").touch()
    result = settled_scan(scanner, clock)
    assert {e["file"] for e in result["files"]} == {"growing.zip", "empty.zip"}
    assert all(e["status"] == "waiting" and e["reason"] == "incomplete_zip" for e in result["files"])
    assert result["index"]["sessions"] == 0
    assert not (scanner.output / "rejected").exists()
    (incoming / "finished.zip.part").rename(incoming / "finished.zip")
    raw = distinct_raw(2)
    archive_at(incoming / "growing.zip", raw=raw, manifest=manifest_for(raw, 2))
    result = settled_scan(scanner, clock)
    assert {e["status"] for e in result["files"] if e["file"] != "empty.zip"} == {"imported"}
    assert result["index"]["sessions"] == 2


def test_growth_resets_the_quiet_interval(tmp_path):
    scanner, incoming, clock = scanner_at(tmp_path)
    source = incoming / "record.zip"
    source.write_bytes(b"PK")
    scanner.scan()
    clock.now = 11
    archive_at(source)
    assert scanner.scan()["files"][0]["reason"] == "settling"
    clock.now = 20
    assert scanner.scan()["files"][0]["status"] == "waiting"
    clock.now = 22
    assert scanner.scan()["files"][0]["status"] == "imported"


def test_invalid_and_conflicting_archives_do_not_block_a_valid_record(tmp_path):
    scanner, incoming, clock = scanner_at(tmp_path)
    archive_at(incoming / "a-first.zip")
    archive_at(incoming / "b-conflict.zip", mutate=lambda m, _: m.update(ground_truth_steps=1))
    archive_at(incoming / "c-rejected.zip", mutate=lambda m, _: m.update(ground_truth_steps=-1))
    raw = distinct_raw(2)
    archive_at(incoming / "d-valid.zip", raw=raw, manifest=manifest_for(raw, 2))
    result = settled_scan(scanner, clock)
    assert [e["status"] for e in result["files"]] == ["imported", "conflict", "rejected", "imported"]
    assert result["failed"] and result["index"]["sessions"] == 2
    assert len(list((scanner.output / "conflicts").glob("*/*/source.zip"))) == 1
    assert len(list((scanner.output / "rejected").glob("*/source.zip"))) == 1
    assert scanner.scan()["failed"]
    assert len(list(incoming.glob("*.zip"))) == 4


def test_repeated_copies_of_one_archive_produce_one_session(tmp_path):
    scanner, incoming, clock = scanner_at(tmp_path)
    archive_at(incoming / "a.zip")
    shutil.copyfile(incoming / "a.zip", incoming / "b.zip")
    result = settled_scan(scanner, clock)
    assert [e["status"] for e in result["files"]] == ["imported", "already_imported"]
    assert result["index"]["sessions"] == 1


@pytest.mark.parametrize("layout", ["same", "output_inside", "input_inside"])
def test_overlapping_directories_are_rejected_before_writing(tmp_path, layout):
    incoming = tmp_path / "incoming"
    incoming.mkdir()
    output = {"same": incoming, "output_inside": incoming / "research", "input_inside": tmp_path}[layout]
    with pytest.raises(ValidationError, match="without nesting"):
        DirectorySync(incoming, output)
    assert list(incoming.iterdir()) == []


def test_source_change_during_snapshot_is_waiting_and_never_imported(tmp_path, monkeypatch):
    from backend.ringo_data import sync
    scanner, incoming, clock = scanner_at(tmp_path)
    source = incoming / "a.zip"
    archive_at(source)
    scanner.scan()
    clock.now = 11
    original = sync.fingerprint
    calls = 0

    def changing(path):
        nonlocal calls
        calls += 1
        if calls == 2:
            with path.open("ab") as stream:
                stream.write(b"still downloading")
        return original(path)

    monkeypatch.setattr(sync, "fingerprint", changing)
    result = scanner.scan()
    assert result["files"][0]["reason"] == "changed_during_snapshot"
    assert result["index"]["sessions"] == 0
    assert not (scanner.output / "rejected").exists()


def test_summary_publish_failure_keeps_previous_complete_index(tmp_path, monkeypatch):
    scanner, incoming, clock = scanner_at(tmp_path)
    archive_at(incoming / "a.zip")
    settled_scan(scanner, clock)
    index = scanner.output / "session-index.csv"
    before = index.read_bytes()
    original = importer.os.replace

    def fail_index(source, target):
        if Path(target).name == "session-index.csv":
            raise OSError("injected index replacement failure")
        return original(source, target)

    monkeypatch.setattr(importer.os, "replace", fail_index)
    result = scanner.scan()
    assert result["index"]["status"] == "index_error"
    assert result["failed"] and index.read_bytes() == before
    assert not list(scanner.output.glob(".session-index-*.tmp"))


def test_existing_artifact_damage_is_reported_without_overwriting_last_index(tmp_path):
    scanner, incoming, clock = scanner_at(tmp_path)
    m = archive_at(incoming / "a.zip")
    settled_scan(scanner, clock)
    index = scanner.output / "session-index.csv"
    before = index.read_bytes()
    (scanner.output / "sessions" / m["session_id"] / "reference.csv").write_text("damaged")
    result = scanner.scan()
    assert result["index"]["status"] == "index_error"
    assert index.read_bytes() == before


def test_damaged_same_session_stays_retryable_while_other_good_archives_continue(tmp_path):
    scanner, incoming, clock = scanner_at(tmp_path)
    first = archive_at(incoming / "a.zip")
    settled_scan(scanner, clock)
    first_reference = scanner.output / "sessions" / first["session_id"] / "reference.csv"
    original_reference = first_reference.read_bytes()
    first_reference.write_text("damaged")

    raw = distinct_raw(2)
    second = archive_at(incoming / "b.zip", raw=raw, manifest=manifest_for(raw, 2))
    scanner = DirectorySync(incoming, scanner.output, monotonic=clock)
    failed = settled_scan(scanner, clock)

    first_event = next(entry for entry in failed["files"] if entry["file"] == "a.zip")
    second_event = next(entry for entry in failed["files"] if entry["file"] == "b.zip")
    assert first_event["status"] == "error"
    assert "existing canonical session integrity error" in first_event["reason"]
    assert second_event["status"] == "imported"
    assert (scanner.output / "sessions" / second["session_id"] / "source.zip").is_file()
    assert not (scanner.output / "rejected").exists()

    first_reference.write_bytes(original_reference)
    retried = scanner.scan()
    first_event = next(entry for entry in retried["files"] if entry["file"] == "a.zip")
    assert first_event["status"] == "already_imported"
    assert retried["index"]["status"] == "indexed"
    assert retried["index"]["sessions"] == 2


def test_damaged_conflict_stays_retryable_while_other_good_archives_continue(tmp_path):
    scanner, incoming, clock = scanner_at(tmp_path)
    first = archive_at(incoming / "a.zip")
    settled_scan(scanner, clock)
    archive_at(incoming / "b-conflict.zip", mutate=lambda manifest, _: manifest.update(ground_truth_steps=1))
    conflict_result = settled_scan(scanner, clock)
    conflict_event = next(entry for entry in conflict_result["files"] if entry["file"] == "b-conflict.zip")
    conflict_reference = Path(conflict_event["directory"]) / "reference.csv"
    original_reference = conflict_reference.read_bytes()
    conflict_reference.write_text("damaged")

    raw = distinct_raw(2)
    second = archive_at(incoming / "c.zip", raw=raw, manifest=manifest_for(raw, 2))
    scanner = DirectorySync(incoming, scanner.output, monotonic=clock)
    failed = settled_scan(scanner, clock)

    conflict_event = next(entry for entry in failed["files"] if entry["file"] == "b-conflict.zip")
    good_event = next(entry for entry in failed["files"] if entry["file"] == "c.zip")
    assert conflict_event["status"] == "error"
    assert "existing conflict artifact integrity error" in conflict_event["reason"]
    assert good_event["status"] == "imported"
    assert (scanner.output / "sessions" / first["session_id"] / "source.zip").is_file()
    assert (scanner.output / "sessions" / second["session_id"] / "source.zip").is_file()
    assert not (scanner.output / "rejected").exists()

    conflict_reference.write_bytes(original_reference)
    retried = scanner.scan()
    conflict_event = next(entry for entry in retried["files"] if entry["file"] == "b-conflict.zip")
    assert conflict_event["status"] == "conflict"
    assert retried["index"]["status"] == "indexed"
    assert retried["index"]["sessions"] == 2


def test_tampered_canonical_manifest_cannot_claim_new_raw_and_new_archive_retries(tmp_path, capsys):
    scanner, incoming, clock = scanner_at(tmp_path)
    first = archive_at(incoming / "a.zip")
    settled_scan(scanner, clock)

    second_raw = distinct_raw(2)
    second = archive_at(incoming / "b.zip", raw=second_raw, manifest=manifest_for(second_raw, 2))
    canonical_manifest = scanner.output / "sessions" / first["session_id"] / "manifest.json"
    original = canonical_manifest.read_bytes()
    tampered = json.loads(original)
    tampered["files"][0]["sha256"] = second["files"][0]["sha256"]
    canonical_manifest.write_text(json.dumps(tampered))

    scanner.scan()
    clock.now += 11
    failed = scanner.scan()
    event = next(entry for entry in failed["files"] if entry["file"] == "b.zip")
    assert event["status"] == "error"
    assert "existing canonical session integrity error" in event["reason"]
    assert failed["index"]["status"] == "index_error"
    assert not (scanner.output / "sessions" / second["session_id"]).exists()
    assert not (scanner.output / "conflicts" / second["session_id"]).exists()
    assert not (scanner.output / "rejected").exists()

    assert main(["import", str(incoming / "b.zip"), "--output", str(scanner.output)]) == 2
    cli_event = json.loads(capsys.readouterr().err)
    assert cli_event["status"] == "research_store_error"
    assert cli_event["retryable"] is True
    assert not (scanner.output / "sessions" / second["session_id"]).exists()
    assert not (scanner.output / "conflicts" / second["session_id"]).exists()
    assert not (scanner.output / "rejected").exists()

    canonical_manifest.write_bytes(original)
    retried = scanner.scan()
    event = next(entry for entry in retried["files"] if entry["file"] == "b.zip")
    assert event["status"] == "imported"
    assert retried["index"]["status"] == "indexed"
    assert retried["index"]["sessions"] == 2


@pytest.mark.parametrize("name,damage", [
    ("import.json", {}), ("import.json", []),
    ("import.json", {"session_id": "missing_hash", "artifacts": {}}),
    ("quality.json", {}), ("quality.json", []),
    ("quality.json", {"analysis_status": "pending_review"}),
    ("quality.json", {"analysis_status": "pending_review", "analysis_reasons": None}),
    ("quality.json", {"analysis_status": "pending_review", "analysis_reasons": [1]}),
])
def test_structurally_damaged_metadata_keeps_sync_running_and_preserves_new_good_sessions(tmp_path, name, damage):
    scanner, incoming, clock = scanner_at(tmp_path)
    first = archive_at(incoming / "a.zip")
    settled_scan(scanner, clock)
    index = scanner.output / "session-index.csv"
    before = index.read_bytes()
    damaged = scanner.output / "sessions" / first["session_id"] / name
    damaged.write_text(json.dumps(damage))
    preserved = damaged.read_bytes()
    # A restarted watcher must handle reimport of the damaged record as a rejected/error result.
    scanner = DirectorySync(incoming, scanner.output, monotonic=clock)
    scanner.scan()
    second_raw = distinct_raw(2)
    second = archive_at(incoming / "b.zip", raw=second_raw, manifest=manifest_for(second_raw, 2))
    result = settled_scan(scanner, clock)
    assert result["index"]["status"] == "index_error"
    assert result["failed"]
    assert any(e["file"] == "b.zip" and e["status"] == "imported" for e in result["files"])
    assert (scanner.output / "sessions" / second["session_id"] / "source.zip").is_file()
    assert index.read_bytes() == before and damaged.read_bytes() == preserved
    third_raw = distinct_raw(3)
    third = archive_at(incoming / "c.zip", raw=third_raw, manifest=manifest_for(third_raw, 3))
    next_round = settled_scan(scanner, clock)
    assert next_round["index"]["status"] == "index_error"
    assert (scanner.output / "sessions" / third["session_id"] / "source.zip").is_file()
    assert index.read_bytes() == before and damaged.read_bytes() == preserved


def test_once_cli_imports_one_settled_batch_and_can_be_repeated(tmp_path, capsys):
    incoming = tmp_path / "incoming"
    incoming.mkdir()
    archive_at(incoming / "a.zip")
    args = ["sync", "--incoming", str(incoming), "--output", str(tmp_path / "out"),
            "--once", "--stable-seconds", "0"]
    assert main(args) == 0
    assert json.loads(capsys.readouterr().out.splitlines()[-1])["files"][0]["status"] == "imported"
    assert main(args) == 0
    assert json.loads(capsys.readouterr().out.splitlines()[-1])["files"][0]["status"] == "already_imported"


def test_sync_retries_after_an_active_importer_releases_the_lock(tmp_path):
    scanner, incoming, clock = scanner_at(tmp_path)
    archive_at(incoming / "a.zip")
    scanner.scan()
    clock.now = 11
    with import_lock(scanner.output):
        result = scanner.scan()
        assert result["files"][0]["status"] == "error"
        assert "locked" in result["files"][0]["reason"]
        assert result["index"]["status"] == "index_error"
    assert scanner.scan()["files"][0]["status"] == "imported"


def test_lock_is_exclusive_across_processes_and_os_releases_it_after_kill(tmp_path):
    root = tmp_path / "out"
    ready = tmp_path / "ready"
    code = """
import sys, time
from pathlib import Path
from backend.ringo_data.importer import import_lock
with import_lock(Path(sys.argv[1])):
    Path(sys.argv[2]).write_text('locked')
    time.sleep(60)
"""
    process = subprocess.Popen([sys.executable, "-c", code, str(root), str(ready)],
                               cwd=Path(__file__).resolve().parents[3])
    try:
        deadline = time.monotonic() + 10
        while not ready.exists() and time.monotonic() < deadline and process.poll() is None:
            time.sleep(0.02)
        assert ready.exists(), "Child process did not acquire the lock"
        with pytest.raises(ValidationError, match="locked"):
            summarize(root, root / "session-index.csv")
        source = tmp_path / "a.zip"
        archive_at(source)
        with pytest.raises(ValidationError, match="locked"):
            import_archive(source, root)
        process.kill()
        process.wait(timeout=5)
        assert (root / ".import.lock").exists()
        assert import_archive(source, root).status == "imported"
        assert import_archive(source, root).status == "already_imported"
        assert len(summarize(root, root / "session-index.csv")) == 1
    finally:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=5)


def test_legacy_lock_is_preserved_for_explicit_upgrade(tmp_path):
    root = tmp_path / "out"
    root.mkdir()
    marker = root / ".import.lock"
    marker.write_text("1234")
    with pytest.raises(ValidationError, match="legacy"):
        summarize(root, root / "session-index.csv")
    assert marker.read_text() == "1234"


@pytest.mark.parametrize("relative", [".import.lock", "sessions/x/reference.csv", "rejected/x/source.zip"])
def test_index_output_cannot_replace_lock_or_preserved_artifacts(tmp_path, relative):
    root = tmp_path / "out"
    with pytest.raises(ValidationError, match="cannot replace"):
        summarize(root, root / relative)
    assert not root.exists()


def test_oversized_file_does_not_block_other_archives(tmp_path):
    scanner, incoming, clock = scanner_at(tmp_path, limits=Limits(archive_bytes=8192))
    (incoming / "a-large.zip").write_bytes(bytes(8193))
    archive_at(incoming / "b.zip")
    result = settled_scan(scanner, clock)
    assert [e["status"] for e in result["files"]] == ["error", "imported"]
    assert result["index"]["sessions"] == 1
