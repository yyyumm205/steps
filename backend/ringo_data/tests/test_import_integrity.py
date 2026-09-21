"""Corrupted local archives must not duplicate references or bypass ZIP quotas."""

import csv
import json
import shutil
import zipfile
from pathlib import Path

import pytest

from backend.ringo_data import importer as importer_module
from backend.ringo_data.__main__ import main
from backend.ringo_data.archive_limits import END, ZIP64_END, ZIP64_LOCATOR
from backend.ringo_data.importer import ArchiveRejected, Limits, import_archive, local_path, summarize
from backend.ringo_data.schema import ValidationError
from backend.ringo_data.tests.test_importer import archive_at


def test_copied_session_directory_does_not_duplicate_reference(tmp_path):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    result = import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    shutil.copytree(result.directory, root / "sessions" / "accidental-copy")

    with pytest.raises(ValidationError, match="directory identity"):
        summarize(root, output)
    assert output.read_bytes() == previous


@pytest.mark.parametrize("replace_root", [False, True])
def test_damaged_session_directory_cannot_silently_remove_index_rows(tmp_path, replace_root):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    result = import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    damaged = root / "sessions" if replace_root else Path(result.directory)
    damaged.rename(tmp_path / "preserved-directory")
    damaged.write_text("invalid replacement", encoding="utf-8")

    with pytest.raises(ValidationError, match="regular directory"):
        summarize(root, output)
    assert output.read_bytes() == previous


def test_dangling_session_root_cannot_replace_complete_index(tmp_path, monkeypatch):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    (root / "sessions").rename(tmp_path / "preserved-directory")
    # Windows symlink creation may require extra privileges. Model the lstat
    # result while leaving exists()/glob() to observe the absent target normally.
    sessions = local_path(root) / "sessions"
    is_symlink = Path.is_symlink
    monkeypatch.setattr(Path, "is_symlink", lambda path: path == sessions or is_symlink(path))
    with pytest.raises(ValidationError, match="regular directory"):
        summarize(root, output)
    assert output.read_bytes() == previous


def test_summary_cli_reports_corruption_without_overwriting_index(tmp_path, capsys):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    result = import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    (Path(result.directory) / "reference.csv").write_text("corrupt", encoding="utf-8")

    assert main(["summary", "--root", str(root), "--output", str(output)]) == 2
    event = json.loads(capsys.readouterr().err)
    assert event["status"] == "index_error"
    assert "checksum" in event["reason"]
    assert output.read_bytes() == previous


@pytest.mark.parametrize("name", ["manifest.json", "quality.json", "import.json"])
def test_oversized_existing_json_cannot_exhaust_or_replace_the_index(tmp_path, name):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    result = import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    (Path(result.directory) / name).write_bytes(b"x" * (Limits().json_bytes + 1))

    with pytest.raises(ValidationError, match="size exceeds quota"):
        summarize(root, output)
    assert output.read_bytes() == previous


def test_summary_uses_the_same_archive_quota_as_the_import(tmp_path):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    accepted = Limits(archive_bytes=source.stat().st_size)
    result = import_archive(source, root, accepted)

    assert len(summarize(root, root / "session-index.csv", accepted)) == 1
    with pytest.raises(ValidationError, match="archive size exceeds quota"):
        summarize(root, root / "rejected-index.csv", Limits(archive_bytes=source.stat().st_size - 1))
    assert Path(result.directory, "source.zip").is_file()


def test_existing_symlink_artifact_cannot_escape_the_session_directory(tmp_path, monkeypatch):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    result = import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    linked = local_path(Path(result.directory)) / "source.zip"
    is_symlink = Path.is_symlink
    monkeypatch.setattr(Path, "is_symlink", lambda path: path == linked or is_symlink(path))

    with pytest.raises(ValidationError, match="regular file"):
        summarize(root, output)
    assert output.read_bytes() == previous


def test_existing_windows_junction_cannot_escape_the_session_directory(tmp_path, monkeypatch):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    result = import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    linked = local_path(Path(result.directory)) / "raw"
    is_junction = getattr(Path, "is_junction", lambda self: False)
    monkeypatch.setattr(Path, "is_junction", lambda path: path == linked or is_junction(path), raising=False)

    with pytest.raises(ValidationError, match="regular file"):
        summarize(root, output)
    assert output.read_bytes() == previous


def test_link_like_summary_output_cannot_redirect_index_replacement(tmp_path, monkeypatch):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    target = output.absolute()
    original = importer_module.is_link_like
    monkeypatch.setattr(importer_module, "is_link_like",
                        lambda path: Path(path) == target or original(path))

    with pytest.raises(ValidationError, match="summary output is not a regular file"):
        summarize(root, output)
    assert output.read_bytes() == previous


def test_link_like_import_lock_is_rejected_without_touching_the_index(tmp_path, monkeypatch):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    target = local_path(root) / ".import.lock"
    original = importer_module.is_link_like
    monkeypatch.setattr(importer_module, "is_link_like",
                        lambda path: Path(path) == target or original(path))

    with pytest.raises(ValidationError, match="import lock is not a regular file"):
        summarize(root, output)
    assert output.read_bytes() == previous


def test_existing_tree_entry_quota_preserves_the_previous_index(tmp_path):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    result = import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    for index in range(40):
        (Path(result.directory) / f"unexpected-{index}").mkdir()

    with pytest.raises(ValidationError, match="tree entry quota"):
        summarize(root, output, Limits(entries=1))
    assert output.read_bytes() == previous


def test_existing_tree_entry_quota_stops_before_materializing_the_directory(tmp_path, monkeypatch):
    directory = tmp_path / "large-tree"
    directory.mkdir()
    for index in range(100):
        (directory / f"entry-{index}").touch()
    original_scandir = importer_module.os.scandir
    observed = 0

    class CountingScanner:
        def __init__(self, scanner):
            self.scanner = scanner

        def __next__(self):
            nonlocal observed
            item = next(self.scanner)
            observed += 1
            return item

        def close(self):
            self.scanner.close()

    monkeypatch.setattr(importer_module.os, "scandir",
                        lambda path: CountingScanner(original_scandir(path)))

    with pytest.raises(ValidationError, match="tree entry quota"):
        importer_module.regular_tree_entries(directory, Limits(entries=1))
    assert observed < 100


@pytest.mark.parametrize("remove_root", [False, True])
def test_missing_admitted_session_cannot_silently_remove_index_rows(tmp_path, remove_root):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    result = import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    previous = output.read_bytes()
    missing = root / "sessions" if remove_root else Path(result.directory)
    missing.rename(tmp_path / ("preserved-sessions" if remove_root else "preserved-session"))

    with pytest.raises(ValidationError, match="previously indexed session"):
        summarize(root, output)
    assert output.read_bytes() == previous


def test_new_empty_research_store_can_initialize_an_empty_index(tmp_path):
    root = tmp_path / "out"
    output = root / "session-index.csv"

    assert summarize(root, output) == []
    assert output.read_text(encoding="utf-8").startswith("session_id,")


def test_historical_index_without_activity_column_upgrades_without_losing_rows(tmp_path):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    result = import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    with output.open("r", encoding="utf-8", newline="") as stream:
        current = list(csv.DictReader(stream))
    legacy_fields = [field for field in current[0] if field != "activity_code"]
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=legacy_fields, lineterminator="\n",
                                extrasaction="ignore")
        writer.writeheader()
        writer.writerows(current)

    rows = summarize(root, output)

    assert [row["session_id"] for row in rows] == [Path(result.directory).name]
    with output.open("r", encoding="utf-8", newline="") as stream:
        upgraded = csv.DictReader(stream)
        assert "activity_code" in upgraded.fieldnames
        assert [row["session_id"] for row in upgraded] == [Path(result.directory).name]


@pytest.mark.parametrize("corrupt", [
    b"\xff\xfeinvalid-index",
    None,
])
def test_malformed_previous_index_is_reported_without_replacement(tmp_path, corrupt):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    import_archive(source, root)
    output = root / "session-index.csv"
    summarize(root, output)
    if corrupt is None:
        header = output.read_bytes().splitlines()[0]
        corrupt = header + b"\n" + b"x" * 200_000 + b"\n"
    output.write_bytes(corrupt)

    with pytest.raises(ValidationError, match="existing summary is invalid"):
        summarize(root, output)
    assert output.read_bytes() == corrupt


@pytest.mark.parametrize("name", [".staging", "sessions", "conflicts", "rejected"])
def test_import_rejects_link_like_research_storage(tmp_path, monkeypatch, name):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    root.mkdir()
    linked = local_path(root) / name
    is_junction = getattr(Path, "is_junction", lambda self: False)
    monkeypatch.setattr(Path, "is_junction", lambda path: path == linked or is_junction(path), raising=False)

    with pytest.raises(ValidationError, match="not a regular directory"):
        import_archive(source, root)
    assert not (root / "sessions").exists()


def test_zip_entry_quota_checked_before_materializing_directory(tmp_path, monkeypatch):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    invoked = False
    original = zipfile.ZipFile

    def tracking_zipfile(*args, **kwargs):
        nonlocal invoked
        invoked = True
        return original(*args, **kwargs)

    monkeypatch.setattr(zipfile, "ZipFile", tracking_zipfile)
    with pytest.raises(ArchiveRejected, match="entry quota"):
        import_archive(source, root, Limits(entries=1))
    assert not invoked


def test_import_receipt_uses_public_backend_version(tmp_path):
    from backend.ringo_data import __version__

    source = tmp_path / "source.zip"
    archive_at(source)
    result = import_archive(source, tmp_path / "out")
    receipt = json.loads((Path(result.directory) / "import.json").read_text(encoding="utf-8"))
    assert receipt["importer_version"] == __version__


@pytest.mark.parametrize("kind", ["entry_count", "directory_length", "name_length"])
def test_forged_directory_cannot_bypass_early_quota(tmp_path, monkeypatch, kind):
    import struct

    source = tmp_path / "source.zip"
    archive_at(source, extras={f"file{i}.txt": "synthetic" for i in range(10)})
    data = bytearray(source.read_bytes())
    footer = len(data) - END.size
    fields = list(END.unpack_from(data, footer))
    if kind == "entry_count":
        fields[3] = fields[4] = 1
    elif kind == "directory_length":
        fields[5] -= 1
    else:
        struct.pack_into("<H", data, fields[6] + 28, 65535)
    END.pack_into(data, footer, *fields)
    source.write_bytes(data)

    def forbidden_zipfile(*args, **kwargs):
        pytest.fail("the unbounded ZIP directory loader must not run")

    monkeypatch.setattr(zipfile, "ZipFile", forbidden_zipfile)
    with pytest.raises(ArchiveRejected):
        import_archive(source, tmp_path / "out", Limits(entries=4 if kind == "entry_count" else 256))


@pytest.mark.parametrize("zip64", [False, True])
def test_standard_and_zip64_directory_imports_remain_supported(tmp_path, zip64):
    source = tmp_path / "source.zip"
    archive_at(source, sidecar=True, compression=zipfile.ZIP_DEFLATED)
    data = source.read_bytes()
    fields = list(END.unpack(data[-END.size:]))
    footer_offset = len(data) - END.size
    if zip64:
        extended = ZIP64_END.pack(b"PK\x06\x06", 44, 45, 45, 0, 0,
                                  fields[4], fields[4], fields[5], fields[6])
        locator = ZIP64_LOCATOR.pack(b"PK\x06\x07", 0, footer_offset, 1)
        fields[3] = fields[4] = 65535
        fields[5] = fields[6] = 0xFFFFFFFF
        data = data[:footer_offset] + extended + locator + END.pack(*fields)
        source.write_bytes(data)
    else:
        with zipfile.ZipFile(source, "a") as archive:
            archive.comment = b"synthetic archive comment"
    result = import_archive(source, tmp_path / "out")
    assert result.status == "imported"
    assert (Path(result.directory) / "source.zip").read_bytes() == source.read_bytes()


def test_nested_receipt_name_is_not_exempt_from_inventory_check(tmp_path):
    source, root = tmp_path / "source.zip", tmp_path / "out"
    archive_at(source)
    result = import_archive(source, root)
    (Path(result.directory) / "raw" / "import.json").write_text("unexpected", encoding="utf-8")
    with pytest.raises(ValidationError, match="inventory"):
        summarize(root, root / "session-index.csv")


def test_cli_version_matches_import_receipt_version(capsys):
    from backend.ringo_data import __version__

    with pytest.raises(SystemExit) as exit_status:
        main(["--version"])
    assert exit_status.value.code == 0
    assert capsys.readouterr().out.strip() == f"ringo_data {__version__}"
