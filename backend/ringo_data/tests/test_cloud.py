import io
import json
import sqlite3
import urllib.error
import urllib.parse
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path

import pytest

from backend.ringo_data.cloud import (
    BASE_URL, CloudConfig, CloudDownloader, CloudSync, CloudSyncError, NoRedirect,
    ReadOnlyCloudHttp, read_client_token, trusted_url,
)
from backend.ringo_data.importer import sha256
from backend.ringo_data.schema import ValidationError
from backend.ringo_data.tests.test_importer import archive_at, manifest_for, raw_bytes, read_rows


REPO = "00000000-0000-0000-0000-000000000001"
TOKEN = "synthetic-account-token"
ADDRESS = BASE_URL + "/files/test-download?access=synthetic-download-secret"


class Response(io.BytesIO):
    def __init__(self, data, *, url=ADDRESS, status=200, headers=None, fail=False):
        super().__init__(data)
        self.url, self.status = url, status
        self.headers = headers or {}
        self.fail = fail

    def geturl(self):
        return self.url

    def read(self, size=-1):
        if self.fail:
            raise OSError(ADDRESS + " " + TOKEN)
        return super().read(size)

    def read1(self, size=-1):
        return self.read(size)


class FakeHttp:
    def __init__(self, listing, payloads):
        self.listing, self.payloads = listing, payloads
        self.calls = []
        self.addresses = {}
        self.fail_files = set()

    @contextmanager
    def open(self, url, token=None):
        self.calls.append((url, token))
        parts = urllib.parse.urlsplit(url)
        if parts.path.endswith("/dir/"):
            assert token == TOKEN
            assert urllib.parse.parse_qs(parts.query)["p"] == ["/study/"]
            data = json.dumps(self.listing).encode()
        elif parts.path.endswith("/file/"):
            assert token == TOKEN
            remote = urllib.parse.parse_qs(parts.query)["p"][0]
            name = remote.removeprefix("/study/")
            self.addresses[ADDRESS + "&file=" + name] = name
            data = json.dumps(ADDRESS + "&file=" + name).encode()
        else:
            assert token is None, "Account credentials must not be sent to download capability URLs"
            name = self.addresses[url]
            with Response(self.payloads[name], fail=name in self.fail_files) as response:
                yield response
            return
        with Response(data, url=url) as response:
            yield response


def config_at(tmp_path, **overrides):
    config = CloudConfig(BASE_URL, REPO, "/study/", tmp_path / "accounts.db", tmp_path / "inbox", tmp_path / "research",
                         stable_seconds=0)
    return replace(config, **overrides)


def data_at(tmp_path, name="source.zip", number=1, steps=0, raw=None):
    path = tmp_path / name
    raw = raw if raw is not None else raw_bytes()
    archive_at(path, raw=raw, manifest=manifest_for(raw, number), mutate=lambda m, _: m.update(ground_truth_steps=steps))
    return path.read_bytes()


def listing_for(name, data, identity="a" * 40):
    return {"type": "file", "name": name, "id": identity, "size": len(data)}


def reader(config, http):
    return CloudSync(config, http=http, token_reader=lambda _db, _base: TOKEN)


def test_cloud_download_import_and_restart_keep_same_archive_and_single_reference(tmp_path):
    data = data_at(tmp_path, steps=73)
    http = FakeHttp([listing_for("one.zip", data)], {"one.zip": data})
    config = config_at(tmp_path)
    first = reader(config, http).once()
    assert not first["failed"]
    assert first["cloud"]["files"][0]["status"] == "downloaded"
    assert first["local"]["index"]["sessions"] == 1
    original = next(config.local_inbox.glob("*.zip"))
    assert original.read_bytes() == data
    rows = read_rows(config.research_output / "session-index.csv")
    assert rows[0]["ground_truth_steps"] == "73" and rows[0]["analysis_status"] == "pending_review"
    next_round = reader(config, http).once()
    assert next_round["cloud"]["files"][0]["status"] == "already_downloaded"
    assert next_round["local"]["files"][0]["status"] == "already_imported"
    assert len([url for url, token in http.calls if token is None]) == 1
    assert len(list(config.research_output.joinpath("sessions").glob("*"))) == 1
    state = config.local_inbox.joinpath(".cloud-downloads.json").read_text()
    assert TOKEN not in state and "synthetic-download-secret" not in state


def test_same_remote_name_with_new_content_keeps_both_and_reports_session_conflict(tmp_path):
    first = data_at(tmp_path, "first.zip", steps=2)
    second = data_at(tmp_path, "second.zip", steps=73)
    http = FakeHttp([listing_for("one.zip", first)], {"one.zip": first})
    service = reader(config_at(tmp_path), http)
    assert not service.once()["failed"]
    http.listing = [listing_for("one.zip", second, "b" * 40)]
    http.payloads["one.zip"] = second
    result = service.once()
    assert result["cloud"]["files"][0]["status"] == "downloaded"
    assert {p.read_bytes() for p in service.config.local_inbox.glob("*.zip")} == {first, second}
    assert any(row["status"] == "conflict" for row in result["local"]["files"])
    assert read_rows(service.config.research_output / "session-index.csv")[0]["ground_truth_steps"] == "2"


@pytest.mark.parametrize("changed_field", ["name", "id", "size"])
def test_remote_object_change_during_download_is_not_published_and_can_be_retried(tmp_path, changed_field):
    first = data_at(tmp_path, "first.zip", steps=2)
    replacement = data_at(tmp_path, "replacement.zip", number=2, steps=73)
    row = listing_for("one.zip", first)

    class ChangingHttp(FakeHttp):
        changed = False

        @contextmanager
        def open(self, url, token=None):
            parts = urllib.parse.urlsplit(url)
            if not self.changed and not parts.path.endswith(("/dir/", "/file/")):
                self.changed = True
                with super().open(url, token) as response:
                    if changed_field == "name":
                        row["name"] = "renamed.zip"
                    elif changed_field == "id":
                        row["id"] = "b" * 40
                    else:
                        row["size"] = len(replacement)
                    self.payloads[row["name"]] = replacement if changed_field == "size" else first
                    yield response
                return
            with super().open(url, token) as response:
                yield response

    http = ChangingHttp([row], {"one.zip": first})
    config = config_at(tmp_path)
    service = reader(config, http)
    result = service.once()
    assert result["failed"]
    assert result["cloud"]["files"] == [{
        "status": "download_error",
        "reason": "cloud file changed during download; retry the current remote object",
    }]
    assert not list(config.local_inbox.glob("*.zip"))
    assert json.loads(config.local_inbox.joinpath(".cloud-downloads.json").read_text())["files"] == {}
    assert result["local"]["index"]["sessions"] == 0

    resumed = service.once()
    assert not resumed["failed"]
    assert resumed["cloud"]["files"][0]["status"] == "downloaded"
    expected = replacement if changed_field == "size" else first
    assert next(config.local_inbox.glob("*.zip")).read_bytes() == expected
    assert resumed["local"]["index"]["sessions"] == 1


def test_interrupted_download_stays_out_of_inbox_and_other_good_file_continues(tmp_path):
    bad = data_at(tmp_path, "a.zip")
    good = data_at(tmp_path, "b.zip", number=2, raw=raw_bytes(anchor=1_800_000_000_001))
    http = FakeHttp([listing_for("a.zip", bad), listing_for("b.zip", good, "b" * 40)], {"a.zip": bad, "b.zip": good})
    http.fail_files.add("a.zip")
    service = reader(config_at(tmp_path), http)
    result = service.once()
    assert result["failed"] and result["local"]["index"]["sessions"] == 1
    assert [e["status"] for e in result["cloud"]["files"]] == ["download_error", "downloaded"]
    assert len(list(service.config.local_inbox.glob("*.zip"))) == 1
    assert not list(service.config.local_inbox.glob("*.part"))
    rendered = json.dumps(result)
    assert TOKEN not in rendered and "synthetic-download-secret" not in rendered
    http.fail_files.clear()
    resumed = service.once()
    assert not resumed["failed"] and resumed["local"]["index"]["sessions"] == 2


@pytest.mark.parametrize("mode", ["short", "long", "not_zip"])
def test_incomplete_or_invalid_network_payload_is_never_imported(tmp_path, mode):
    data = data_at(tmp_path)
    wrong = {"short": data[:-1], "long": data + b"extra", "not_zip": b"x" * len(data)}[mode]
    http = FakeHttp([listing_for("one.zip", data)], {"one.zip": wrong})
    result = reader(config_at(tmp_path), http).once()
    assert result["failed"] and result["local"]["index"]["sessions"] == 0
    assert not list((tmp_path / "inbox").glob("*.zip"))


def test_temporary_and_subdirectory_entries_are_not_downloaded(tmp_path):
    data = data_at(tmp_path)
    entries = [listing_for("hidden.zip.part", data), listing_for(".hidden.zip", data),
               {"type": "dir", "name": "another-experiment"}, listing_for("one.zip", data)]
    http = FakeHttp(entries, {"one.zip": data})
    result = reader(config_at(tmp_path), http).once()
    assert not result["failed"] and len(result["cloud"]["files"]) == 1
    assert len(http.calls) == 4


@pytest.mark.parametrize("name", ["../other.zip", "other\\secret.zip", "encoded%2fpath.zip"])
def test_remote_file_names_cannot_escape_the_configured_directory(tmp_path, name):
    data = data_at(tmp_path)
    http = FakeHttp([listing_for(name, data)], {})
    result = reader(config_at(tmp_path), http).once()
    assert result["failed"] and len(http.calls) == 1


@pytest.mark.parametrize("name", ["bad\ud800.zip", "bad\udfff.zip"])
def test_unpaired_surrogate_file_name_does_not_stop_the_next_valid_download(tmp_path, name):
    data = data_at(tmp_path)
    http = FakeHttp([listing_for(name, data), listing_for("good.zip", data, "b" * 40)], {"good.zip": data})
    result = reader(config_at(tmp_path), http).once()
    assert result["failed"]
    assert [event["status"] for event in result["cloud"]["files"]] == ["download_error", "downloaded"]
    assert result["cloud"]["files"][0]["reason"] == "cloud file name is invalid"
    assert result["local"]["index"]["sessions"] == 1
    assert len([url for url, token in http.calls if token is None]) == 1
    assert TOKEN not in json.dumps(result) and "synthetic-download-secret" not in json.dumps(result)


@pytest.mark.parametrize("kind", ["files", "response", "archive"])
def test_remote_quotas_limit_download_work(tmp_path, kind):
    data = data_at(tmp_path)
    entries = [listing_for("one.zip", data), listing_for("two.zip", data, "b" * 40)]
    http = FakeHttp(entries, {"one.zip": data, "two.zip": data})
    config = config_at(tmp_path, **{"files": {"max_files": 1}, "response": {"max_response_bytes": 20},
                                  "archive": {"max_archive_bytes": len(data) - 1}}[kind])
    result = reader(config, http).once()
    assert result["failed"] and len(http.calls) == 1
    assert not list(config.local_inbox.glob("*.zip"))


def test_reusing_inbox_for_different_cloud_scope_is_rejected_before_network(tmp_path):
    data = data_at(tmp_path)
    config = config_at(tmp_path)
    http = FakeHttp([listing_for("one.zip", data)], {"one.zip": data})
    reader(config, http).once()
    http.calls.clear()
    other_output = tmp_path / "other-research"
    result = reader(replace(config, repo_id="00000000-0000-0000-0000-000000000002", research_output=other_output), http).once()
    assert result["cloud"]["failed"] and not http.calls
    assert result["local"]["index"]["status"] == "index_skipped" and not other_output.exists()


def test_damaged_scope_receipt_does_not_relabel_existing_inbox_as_another_study(tmp_path):
    data = data_at(tmp_path)
    config = config_at(tmp_path)
    http = FakeHttp([listing_for("one.zip", data)], {"one.zip": data})
    reader(config, http).once()
    (config.local_inbox / ".cloud-downloads.json").write_text("{}")
    http.calls.clear()
    another = tmp_path / "other-output"
    result = reader(replace(config, research_output=another), http).once()
    assert result["cloud"]["status"] == "scope_error" and not http.calls
    assert not another.exists() and len(list(config.local_inbox.glob("*.zip"))) == 1


@pytest.mark.parametrize("cloud_failure", [False, True])
def test_first_scope_cannot_claim_nonempty_inbox_even_when_remote_is_empty_or_unavailable(tmp_path, cloud_failure):
    config = config_at(tmp_path)
    config.local_inbox.mkdir()
    unrelated = config.local_inbox / "other-study.zip"
    archive_at(unrelated, manifest=manifest_for(raw_bytes(), 99))
    original = unrelated.read_bytes()
    http = FakeHttp([], {})
    if cloud_failure:
        http.listing = None
    result = reader(config, http).once()
    assert result["cloud"]["status"] == "scope_error" and not http.calls
    assert unrelated.read_bytes() == original and not config.research_output.exists()
    assert not (config.local_inbox / ".cloud-downloads.json").exists()


def test_empty_remote_still_persists_scope_before_other_files_can_arrive(tmp_path):
    config = config_at(tmp_path)
    http = FakeHttp([], {})
    result = reader(config, http).once()
    assert not result["failed"] and (config.local_inbox / ".cloud-downloads.json").exists()
    assert json.loads((config.local_inbox / ".cloud-downloads.json").read_text())["files"] == {}


def test_missing_scope_marker_after_download_refuses_to_reclaim_existing_packages(tmp_path):
    data = data_at(tmp_path)
    config = config_at(tmp_path)
    http = FakeHttp([listing_for("one.zip", data)], {"one.zip": data})
    reader(config, http).once()
    source = next(config.local_inbox.glob("*.zip"))
    before = source.read_bytes()
    (config.local_inbox / ".cloud-downloads.json").unlink()
    http.calls.clear()
    output = tmp_path / "new-output"
    result = reader(replace(config, research_output=output), http).once()
    assert result["cloud"]["status"] == "scope_error" and not http.calls
    assert source.read_bytes() == before and not output.exists()


def test_bound_inbox_imports_only_registered_hash_packages_and_leaves_unrelated_files(tmp_path):
    data = data_at(tmp_path)
    config = config_at(tmp_path)
    http = FakeHttp([listing_for("one.zip", data)], {"one.zip": data})
    service = reader(config, http)
    service.once()
    foreign = config.local_inbox / "other-study.zip"
    foreign_manifest = archive_at(foreign, manifest=manifest_for(raw_bytes(), 99))
    rendered_as_hash = config.local_inbox / ("cloud-" + sha256(foreign) + ".zip")
    foreign.rename(rendered_as_hash)
    result = service.once()
    assert not result["failed"] and result["local"]["index"]["sessions"] == 1
    assert rendered_as_hash.exists()
    assert not (config.research_output / "sessions" / foreign_manifest["session_id"]).exists()
    assert all(row["file"] != rendered_as_hash.name for row in result["local"]["files"])


def test_published_zip_without_receipt_waits_until_the_same_cloud_file_is_read_back(tmp_path, monkeypatch):
    data = data_at(tmp_path)
    config = config_at(tmp_path)
    http = FakeHttp([listing_for("one.zip", data)], {"one.zip": data})
    service = reader(config, http)
    save = service.downloader._save

    def fail_receipt(state):
        if state["files"]:
            raise OSError("injected process interruption after ZIP publication")
        save(state)

    monkeypatch.setattr(service.downloader, "_save", fail_receipt)
    result = service.once()
    assert result["failed"] and len(list(config.local_inbox.glob("*.zip"))) == 1
    assert result["local"]["index"]["sessions"] == 0
    http.listing = []
    restarted = reader(config, http)
    assert restarted.once()["local"]["index"]["sessions"] == 0
    http.listing = [listing_for("one.zip", data)]
    resumed = restarted.once()
    assert not resumed["failed"] and resumed["local"]["index"]["sessions"] == 1
    assert len(list(config.local_inbox.glob("*.zip"))) == 1


@pytest.mark.parametrize("operation", ["metadata", "download"])
def test_slow_drip_body_uses_single_reads_and_obeys_absolute_deadline(tmp_path, monkeypatch, operation):
    from backend.ringo_data import cloud
    clock = [0.0]
    monkeypatch.setattr(cloud.time, "monotonic", lambda: clock[0])
    chunks = []

    class Drip(Response):
        def read(self, size=-1):
            raise AssertionError("Buffered read can wait indefinitely under continuous trickle")

        def read1(self, size=-1):
            chunks.append(size)
            clock[0] += 31 if operation == "metadata" else 61
            return io.BytesIO.read(self, 1)

    class SlowHttp:
        @contextmanager
        def open(self, url, token=None):
            with Drip(b"[unending slow response") as response:
                yield response

    downloader = CloudDownloader(config_at(tmp_path), SlowHttp(), lambda *_: TOKEN)
    with pytest.raises(ValidationError, match="time budget"):
        if operation == "metadata":
            downloader._json(BASE_URL + "/api2/fixture/", TOKEN)
        else:
            downloader._download(ADDRESS, 20)
    assert len(chunks) == 2
    assert not list(downloader.config.local_inbox.glob("*.zip"))
    assert not list(downloader.config.local_inbox.glob("*.part"))


def test_damaged_existing_inbox_file_is_preserved_and_reported(tmp_path):
    data = data_at(tmp_path)
    config = config_at(tmp_path)
    http = FakeHttp([listing_for("one.zip", data)], {"one.zip": data})
    reader(config, http).once()
    archive = next(config.local_inbox.glob("*.zip"))
    archive.write_bytes(b"damaged local artifact")
    result = reader(config, http).once()
    assert result["cloud"]["failed"] and archive.read_bytes() == b"damaged local artifact"


def test_credentials_are_read_only_and_multiple_matching_accounts_require_selection(tmp_path):
    database = tmp_path / "accounts.db"
    with sqlite3.connect(database) as db:
        db.execute("CREATE TABLE Accounts(url TEXT, username TEXT, token TEXT)")
        db.execute("INSERT INTO Accounts VALUES(?,?,?)", (BASE_URL + "/", "synthetic", TOKEN))
        db.execute("INSERT INTO Accounts VALUES(?,?,?)", ("https://unrelated.invalid", "other", "other-token"))
    before = sha256(database)
    assert read_client_token(database, BASE_URL) == TOKEN
    assert sha256(database) == before
    with sqlite3.connect(database) as db:
        db.execute("INSERT INTO Accounts VALUES(?,?,?)", (BASE_URL, "second", "second-token"))
    with pytest.raises(CloudSyncError, match="exactly one") as error:
        read_client_token(database, BASE_URL)
    assert TOKEN not in str(error.value)


def test_missing_client_account_does_not_create_database_or_leak_details(tmp_path):
    missing = tmp_path / "missing.db"
    with pytest.raises(CloudSyncError, match="sign in"):
        read_client_token(missing, BASE_URL)
    assert not missing.exists()


@pytest.mark.parametrize("url", ["http://cloud.tsinghua.edu.cn/file", "https://foreign.invalid/file?token=" + TOKEN,
                                "https://cloud.tsinghua.edu.cn:444/file", "https://user:secret@cloud.tsinghua.edu.cn/file",
                                "https://cloud.tsinghua.edu.cn/file#fragment"])
def test_foreign_or_insecure_addresses_are_rejected_without_echoing_credentials(url):
    with pytest.raises(ValidationError) as error:
        trusted_url(url)
    assert TOKEN not in str(error.value) and url not in str(error.value)


def test_http_adapter_only_issues_get_and_never_follows_redirects_or_echoes_network_urls():
    class Opener:
        calls = []

        def open(self, request, timeout):
            self.calls.append(request)
            assert request.method == "GET" and timeout == 30
            raise urllib.error.HTTPError(ADDRESS, 302, "Location contains " + TOKEN, {}, None)

    opener = Opener()
    with pytest.raises(CloudSyncError) as error:
        with ReadOnlyCloudHttp(opener).open(BASE_URL + "/api2/repos/fixture/dir/", TOKEN):
            pass
    assert len(opener.calls) == 1 and opener.calls[0].get_header("Authorization") == "Token " + TOKEN
    assert TOKEN not in str(error.value) and "synthetic-download-secret" not in str(error.value)
    assert NoRedirect().redirect_request(None, None, 302, None, None, "https://foreign.invalid") is None


def test_foreign_download_link_is_rejected_before_any_request(tmp_path):
    data = data_at(tmp_path)

    class ForeignLink(FakeHttp):
        @contextmanager
        def open(self, url, token=None):
            if urllib.parse.urlsplit(url).path.endswith("/file/"):
                self.calls.append((url, token))
                with Response(json.dumps("https://foreign.invalid/?token=" + TOKEN).encode()) as response:
                    yield response
            else:
                with super().open(url, token) as response:
                    yield response

    http = ForeignLink([listing_for("one.zip", data)], {})
    result = reader(config_at(tmp_path), http).once()
    assert result["failed"] and len(http.calls) == 2
    assert TOKEN not in json.dumps(result)


@pytest.mark.parametrize("download_path", ["/bad\ud800", "/未编码路径"])
def test_non_ascii_download_url_is_rejected_without_stopping_other_downloads(tmp_path, download_path):
    data = data_at(tmp_path)

    class MalformedLink(FakeHttp):
        @contextmanager
        def open(self, url, token=None):
            parts = urllib.parse.urlsplit(url)
            if parts.path.endswith("/file/") and urllib.parse.parse_qs(parts.query)["p"] == ["/study/bad.zip"]:
                self.calls.append((url, token))
                with Response(json.dumps(BASE_URL + download_path + "?access=" + TOKEN).encode()) as response:
                    yield response
            else:
                with super().open(url, token) as response:
                    yield response

    http = MalformedLink([listing_for("bad.zip", data), listing_for("good.zip", data, "b" * 40)],
                         {"good.zip": data})
    result = reader(config_at(tmp_path), http).once()
    assert result["failed"]
    assert [event["status"] for event in result["cloud"]["files"]] == ["download_error", "downloaded"]
    assert result["local"]["index"]["sessions"] == 1
    assert len([url for url, token in http.calls if token is None]) == 1
    assert TOKEN not in json.dumps(result)


def test_percent_encoded_unicode_url_still_uses_the_trusted_origin():
    address = BASE_URL + "/" + urllib.parse.quote("合法路径")
    assert trusted_url(address) == address


def test_config_paths_resolve_locally_and_inbox_output_must_stay_separate(tmp_path):
    path = tmp_path / "config.json"
    value = {"base_url": BASE_URL, "repo_id": REPO, "remote_path": "/study", "account_db": "accounts.db",
             "local_inbox": "inbox", "research_output": "research"}
    path.write_text(json.dumps(value))
    config = CloudConfig.load(path)
    assert config.remote_path == "/study/" and config.account_db == tmp_path / "accounts.db"
    assert config.local_inbox == tmp_path / "inbox"
    value["research_output"] = "inbox/research"
    path.write_text(json.dumps(value))
    with pytest.raises(ValidationError, match="separate"):
        CloudConfig.load(path)


def test_config_rejects_unpaired_surrogate_in_remote_directory(tmp_path):
    path = tmp_path / "config.json"
    value = {"base_url": BASE_URL, "repo_id": REPO, "remote_path": "/study\ud800/", "account_db": "accounts.db",
             "local_inbox": "inbox", "research_output": "research"}
    path.write_text(json.dumps(value))
    with pytest.raises(CloudSyncError, match="invalid configured cloud directory"):
        CloudConfig.load(path)
    assert not (tmp_path / "inbox").exists() and not (tmp_path / "research").exists()
