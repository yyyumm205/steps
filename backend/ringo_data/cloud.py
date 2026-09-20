"""Read-only, scoped Seafile download into a local inbox and the existing session importer."""

from __future__ import annotations

import hashlib
import http.client
import json
import math
import os
import sqlite3
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from uuid import UUID

from . import __version__
from .importer import Limits, import_lock, sha256, sync_directory
from .schema import ValidationError, object_value, require, strict_json
from .sync import DirectorySync


BASE_URL = "https://cloud.tsinghua.edu.cn"


class CloudSyncError(ValidationError):
    """Messages contain no credentials, download URLs or HTTP response bodies."""


class CloudScopeError(CloudSyncError):
    """The inbox's persisted scope is untrusted; do not feed it into this research output."""


def utf8(value, message):
    """Reject unpaired JSON surrogate escapes before URL or identity encoding."""
    try:
        return value.encode("utf-8")
    except UnicodeError:
        raise CloudSyncError(message) from None


@dataclass(frozen=True)
class CloudConfig:
    base_url: str
    repo_id: str
    remote_path: str
    account_db: Path
    local_inbox: Path
    research_output: Path
    interval_seconds: float = 60
    stable_seconds: float = 10
    max_files: int = 1024
    max_response_bytes: int = 2 * 1024 ** 2
    max_archive_bytes: int = 512 * 1024 ** 2

    @classmethod
    def load(cls, path):
        path = Path(path).resolve()
        require(path.stat().st_size <= 32_768, "cloud configuration is too large")
        value = object_value(strict_json(path.read_bytes()), "cloud configuration")
        required = {"base_url", "repo_id", "remote_path", "account_db", "local_inbox", "research_output"}
        optional = {"interval_seconds", "stable_seconds", "max_files", "max_response_bytes", "max_archive_bytes"}
        require(required <= set(value) and set(value) <= required | optional, "cloud configuration fields are invalid")
        require(value["base_url"] == BASE_URL, "cloud base URL must be the configured trusted HTTPS origin")
        try:
            require(type(value["repo_id"]) is str and str(UUID(value["repo_id"])) == value["repo_id"], "invalid cloud repository ID")
        except (ValueError, TypeError, AttributeError):
            raise CloudSyncError("invalid cloud repository ID") from None
        remote = value["remote_path"]
        require(type(remote) is str and remote.startswith("/") and len(remote) <= 2048 and
                not any(c in remote for c in ("\\", "\0", "\r", "\n", "%")) and
                all(part not in (".", "..") for part in remote.split("/")), "invalid configured cloud directory")
        utf8(remote, "invalid configured cloud directory")
        value["remote_path"] = remote.rstrip("/") + "/" if remote != "/" else "/"
        for key in ("account_db", "local_inbox", "research_output"):
            require(type(value[key]) is str and value[key], "local cloud paths must be configured")
            value[key] = (path.parent / value[key]).resolve()
        for key, low, high in (("max_files", 1, 4096), ("max_response_bytes", 1024, 16 * 1024 ** 2),
                               ("max_archive_bytes", 1, 8 * 1024 ** 3)):
            if key in value:
                require(type(value[key]) is int and low <= value[key] <= high, "cloud resource quota is invalid")
        for key, minimum in (("interval_seconds", 1), ("stable_seconds", 0)):
            if key in value:
                require(type(value[key]) in (int, float) and math.isfinite(value[key]) and value[key] >= minimum,
                        "cloud polling interval is invalid")
        config = cls(**value)
        require(config.local_inbox != config.research_output and config.local_inbox not in config.research_output.parents and
                config.research_output not in config.local_inbox.parents, "cloud inbox and research output must be separate without nesting")
        return config


def read_client_token(account_db, base_url):
    require(base_url == BASE_URL, "unsupported account origin")
    try:
        uri = Path(account_db).resolve().as_uri() + "?mode=ro"
        with sqlite3.connect(uri, uri=True) as connection:
            rows = connection.execute(
                "SELECT token FROM Accounts WHERE rtrim(url, '/')=? AND token IS NOT NULL AND token<>''",
                (base_url,)).fetchall()
    except sqlite3.Error:
        raise CloudSyncError("client account database is unavailable; open and sign in to the Seafile client") from None
    if len(rows) != 1:
        raise CloudSyncError("exactly one signed-in account for the configured server is required; confirm the account selection")
    token = rows[0][0]
    require(type(token) is str and 0 < len(token) <= 4096 and all(32 < ord(c) < 127 for c in token),
            "client account credential is invalid; sign in again")
    return token


def trusted_url(url):
    require(type(url) is str and 0 < len(url) <= 8192 and url.isascii() and
            not any(c in url for c in ("\r", "\n", "\0")),
            "cloud address is invalid")
    try:
        parsed = urllib.parse.urlsplit(url)
        valid = parsed.scheme == "https" and parsed.hostname == "cloud.tsinghua.edu.cn" and parsed.port in (None, 443)
        valid = valid and parsed.username is None and parsed.password is None and not parsed.fragment
    except ValueError:
        valid = False
    require(valid, "cloud address is outside the trusted HTTPS origin")
    return url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class ReadOnlyCloudHttp:
    def __init__(self, opener=None):
        self.opener = opener or urllib.request.build_opener(NoRedirect())

    @contextmanager
    def open(self, url, token=None):
        trusted_url(url)
        headers = {"Accept": "application/json,application/zip", "Accept-Encoding": "identity",
                   "User-Agent": f"RingFitnessResearch/{__version__}"}
        if token is not None:
            headers["Authorization"] = "Token " + token
        request = urllib.request.Request(url, headers=headers, method="GET")
        try:
            with self.opener.open(request, timeout=30) as response:
                trusted_url(response.geturl())
                require(response.status == 200, "cloud request did not return a complete file")
                require(response.headers.get("Content-Encoding", "identity").lower() == "identity",
                        "encoded cloud response is unsupported")
                yield response
        except (OSError, urllib.error.URLError, http.client.HTTPException):
            raise CloudSyncError("cloud request interrupted or unavailable; retry the same download") from None


class CloudDownloader:
    def __init__(self, config, http=None, token_reader=read_client_token):
        self.config = config
        self.http = http or ReadOnlyCloudHttp()
        self.token_reader = token_reader
        config.local_inbox.mkdir(parents=True, exist_ok=True)
        self.state_path = config.local_inbox / ".cloud-downloads.json"
        self.scope = hashlib.sha256(utf8(config.base_url + "\n" + config.repo_id + "\n" + config.remote_path,
                                         "invalid configured cloud directory")).hexdigest()

    def _json(self, url, token):
        deadline = time.monotonic() + 60
        with self.http.open(url, token) as response:
            data = bytearray()
            while True:
                require(time.monotonic() <= deadline, "cloud metadata request exceeded its time budget")
                chunk = response.read1(64 * 1024)
                require(time.monotonic() <= deadline, "cloud metadata request exceeded its time budget")
                if not chunk:
                    break
                data.extend(chunk)
                require(len(data) <= self.config.max_response_bytes, "cloud metadata response exceeds quota")
        try:
            return strict_json(bytes(data))
        except ValidationError:
            raise CloudSyncError("cloud metadata response is invalid") from None

    def _state(self):
        try:
            if not self.state_path.exists():
                require(all(item.name == ".import.lock" for item in self.config.local_inbox.iterdir()),
                        "initial cloud inbox must be dedicated and empty")
                value = {"version": 1, "scope_sha256": self.scope, "files": {}}
                # Bind before the first request or published ZIP, including an empty remote folder.
                self._save(value)
                return value
            require(self.state_path.stat().st_size <= 16 * 1024 ** 2, "local cloud receipt exceeds quota")
            value = object_value(strict_json(self.state_path.read_bytes()), "local cloud receipt")
            require(type(value.get("version")) is int and value["version"] == 1 and value.get("scope_sha256") == self.scope,
                    "local cloud inbox belongs to another configured scope")
            files = object_value(value.get("files"), "local cloud files")
            for identity, receipt in files.items():
                require(type(identity) is str and len(identity) == 64 and all(c in "0123456789abcdef" for c in identity),
                        "local cloud source identity is invalid")
                receipt = object_value(receipt, "local cloud file receipt")
                digest = receipt.get("sha256")
                require(type(digest) is str and len(digest) == 64 and all(c in "0123456789abcdef" for c in digest) and
                        type(receipt.get("bytes")) is int and receipt["bytes"] > 0, "local cloud file receipt is invalid")
            return value
        except (ValidationError, OSError):
            raise CloudScopeError("local cloud inbox scope cannot be verified; keep its files and check the configuration") from None

    def registered_files(self):
        """Only verified receipts authorize an inbox ZIP to enter this research output."""
        names, errors = set(), []
        with import_lock(self.config.local_inbox):
            state = self._state()
            for receipt in state["files"].values():
                digest = receipt["sha256"]
                name = "cloud-" + digest + ".zip"
                path = self.config.local_inbox / name
                try:
                    require(path.is_file() and not path.is_symlink() and path.stat().st_size == receipt["bytes"] and
                            sha256(path) == digest, "registered local cloud file needs review")
                    names.add(name)
                except (ValidationError, OSError):
                    errors.append({"status": "download_error", "reason": "registered local cloud file needs review; original retained"})
        return names, errors

    def _save(self, state):
        fd, name = tempfile.mkstemp(prefix=".cloud-receipt-", suffix=".tmp", dir=self.config.local_inbox)
        temporary = Path(name)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(state, stream, ensure_ascii=False, allow_nan=False)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.state_path)
            sync_directory(self.config.local_inbox)
        finally:
            temporary.unlink(missing_ok=True)

    def poll(self):
        with import_lock(self.config.local_inbox):
            return self._poll_locked()

    def _poll_locked(self):
        state = self._state()
        token = self.token_reader(self.config.account_db, self.config.base_url)
        query = urllib.parse.urlencode({"p": self.config.remote_path})
        listing = self._json(f"{self.config.base_url}/api2/repos/{self.config.repo_id}/dir/?{query}", token)
        require(type(listing) is list and len(listing) <= self.config.max_files, "cloud directory listing exceeds quota or has an invalid shape")
        events = []
        for row in listing:
            try:
                row = object_value(row, "cloud directory entry")
                if row.get("type") != "file":
                    continue
                name = row.get("name")
                require(type(name) is str and 0 < len(name) <= 240 and not any(c in name for c in ("/", "\\", "\0", "\r", "\n", "%")),
                        "cloud file name is invalid")
                if name.startswith(".") or not name.lower().endswith(".zip"):
                    continue
                size, remote_id = row.get("size"), row.get("id")
                require(type(size) is int and 0 < size <= self.config.max_archive_bytes, "cloud archive size exceeds quota")
                require(type(remote_id) is str and 0 < len(remote_id) <= 128 and remote_id.isalnum(), "cloud file identity is invalid")
                identity = hashlib.sha256(utf8(name + "\0" + remote_id, "cloud file name is invalid")).hexdigest()
                previous = state["files"].get(identity)
                if previous is not None:
                    previous = object_value(previous, "local cloud file receipt")
                    digest = previous.get("sha256")
                    require(type(digest) is str and len(digest) == 64 and all(c in "0123456789abcdef" for c in digest),
                            "local cloud file receipt is invalid")
                    existing = self.config.local_inbox / ("cloud-" + digest + ".zip")
                    if previous.get("bytes") == size and existing.is_file() and not existing.is_symlink() and sha256(existing) == digest:
                        events.append({"status": "already_downloaded", "sha256": digest, "bytes": size})
                        continue
                remote = self.config.remote_path + name
                path_query = urllib.parse.urlencode({"p": remote})
                address = self._json(f"{self.config.base_url}/api2/repos/{self.config.repo_id}/file/?{path_query}", token)
                trusted_url(address)
                digest = self._download(address, size)
                state["files"][identity] = {"sha256": digest, "bytes": size}
                self._save(state)
                events.append({"status": "downloaded", "sha256": digest, "bytes": size})
            except (ValidationError, OSError, http.client.HTTPException) as error:
                # Never include a URL, request object, server body or credential in diagnostics.
                reason = str(error) if isinstance(error, ValidationError) else "local or network download failed; source retained"
                events.append({"status": "download_error", "reason": reason})
        return {"files": events, "failed": any(e["status"] == "download_error" for e in events)}

    def _download(self, address, size):
        fd, name = tempfile.mkstemp(prefix=".cloud-download-", suffix=".part", dir=self.config.local_inbox)
        temporary = Path(name)
        try:
            digest, count = hashlib.sha256(), 0
            deadline = time.monotonic() + 120 + size / 16_384
            # File URLs may contain a short-lived capability. They never receive the account token.
            with os.fdopen(fd, "wb") as target, self.http.open(address) as response:
                while True:
                    require(time.monotonic() <= deadline, "cloud download exceeded its time budget; retry the same file")
                    chunk = response.read1(1024 * 1024)
                    require(time.monotonic() <= deadline, "cloud download exceeded its time budget; retry the same file")
                    if not chunk:
                        break
                    count += len(chunk)
                    require(count <= size, "download exceeds the listed archive length")
                    target.write(chunk)
                    digest.update(chunk)
                require(count == size, "download is incomplete; retry the same file")
                target.flush()
                os.fsync(target.fileno())
            require(zipfile.is_zipfile(temporary), "download is not a complete ZIP; source retained")
            checksum = digest.hexdigest()
            final = self.config.local_inbox / ("cloud-" + checksum + ".zip")
            if final.exists():
                require(final.is_file() and not final.is_symlink() and sha256(final) == checksum, "existing local cloud file is damaged; retained for review")
            else:
                # link publishes without replacing a concurrently published file of the same name.
                try:
                    os.link(temporary, final)
                except FileExistsError:
                    require(final.is_file() and not final.is_symlink() and sha256(final) == checksum, "local cloud publication conflict; original retained")
                sync_directory(self.config.local_inbox)
            return checksum
        finally:
            temporary.unlink(missing_ok=True)


class CloudSync:
    def __init__(self, config, http=None, token_reader=read_client_token):
        self.config = config
        self.downloader = CloudDownloader(config, http, token_reader)
        self.registered = set()
        self.scanner = DirectorySync(config.local_inbox, config.research_output, stable_seconds=config.stable_seconds,
                                     limits=Limits(archive_bytes=config.max_archive_bytes), include_file=lambda name: name in self.registered)

    @staticmethod
    def scope_error(error):
        return {"cloud": {"files": [], "failed": True, "status": "scope_error", "reason": str(error)},
                "local": {"files": [], "index": {"status": "index_skipped"}, "pending": 0, "failed": True}, "failed": True}

    def _scan_registered(self, cloud):
        self.registered, errors = self.downloader.registered_files()
        cloud["files"].extend(errors)
        cloud["failed"] |= bool(errors)
        return self.scanner.scan()

    def poll(self):
        try:
            cloud = self.downloader.poll()
        except CloudScopeError as error:
            return self.scope_error(error)
        except (ValidationError, OSError, http.client.HTTPException) as error:
            cloud = {"files": [], "failed": True, "status": "cloud_unavailable",
                     "reason": str(error) if isinstance(error, ValidationError) else
                         "cloud read could not complete; verify client login, configured scope and network"}
        try:
            local = self._scan_registered(cloud)
        except CloudScopeError as error:
            return self.scope_error(error)
        except (ValidationError, OSError):
            local = {"files": [], "index": {"status": "index_error", "reason": "local cloud verification is unavailable; retry later"},
                     "pending": 0, "failed": True}
        return {"cloud": cloud, "local": local, "failed": cloud["failed"] or local["failed"]}

    def once(self, sleep=time.sleep):
        result = self.poll()
        if result["cloud"].get("status") == "scope_error":
            return result
        sleep(self.config.stable_seconds)
        try:
            result["local"] = self._scan_registered(result["cloud"])
        except CloudScopeError as error:
            return self.scope_error(error)
        except (ValidationError, OSError):
            result["local"] = {"files": [], "index": {"status": "index_error", "reason": "local cloud verification is unavailable; retry later"},
                               "pending": 0, "failed": True}
        result["failed"] = result["cloud"]["failed"] or result["local"]["failed"]
        return result
