"""Poll a local cloud-sync folder; only stable, complete ZIP snapshots reach the importer."""

from __future__ import annotations

import hashlib
import math
import os
import stat
import tempfile
import time
import zipfile
from dataclasses import asdict
from pathlib import Path

from .importer import ArchiveRejected, Limits, import_archive, is_link_like, local_path, summarize
from .schema import ValidationError, require


def fingerprint(path):
    value = path.stat(follow_symlinks=False)
    require(stat.S_ISREG(value.st_mode), "incoming archive must be a regular file")
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns


class DirectorySync:
    """One scanner owns its observations; durable import identities handle restart and duplicates."""

    def __init__(self, incoming, output, *, stable_seconds=10, limits=None, monotonic=time.monotonic,
                 include_file=None, expected_file=None):
        require(math.isfinite(stable_seconds) and stable_seconds >= 0, "stable seconds must be finite and nonnegative")
        require(include_file is None or expected_file is None,
                "include_file and expected_file cannot be used together")
        self.incoming, self.output = local_path(incoming), local_path(output)
        require(self.incoming.is_dir(), "incoming sync directory does not exist")
        require(self.incoming != self.output and self.incoming not in self.output.parents and
                self.output not in self.incoming.parents, "incoming and output directories must be separate, without nesting")
        self.stable_seconds = stable_seconds
        self.limits = limits or Limits()
        self.monotonic = monotonic
        self.include_file = include_file
        self.expected_file = expected_file
        self.observed = {}
        self.processed = {}

    def scan(self):
        """Observe once. A new/changed file needs another observation after the quiet interval."""
        now, events, present = self.monotonic(), [], set()
        for path in sorted(self.incoming.iterdir()):
            # Sync-client partials such as .name.zip, name.zip.part and name.zip.tmp stay untouched.
            if path.name.startswith(".") or path.suffix.lower() != ".zip" or path.is_symlink() or not path.is_file():
                continue
            expected_content = self.expected_file(path.name) if self.expected_file is not None else None
            if (self.expected_file is not None and expected_content is None) or (
                    self.include_file is not None and not self.include_file(path.name)):
                continue
            present.add(path.name)
            try:
                signature = fingerprint(path)
                prior = self.observed.get(path.name)
                if prior is None or prior[0] != signature:
                    self.observed[path.name] = (signature, now)
                    self.processed.pop(path.name, None)
                    events.append(dict(file=path.name, status="waiting", reason="settling"))
                    continue
                if now - prior[1] < self.stable_seconds:
                    events.append(dict(file=path.name, status="waiting", reason="settling"))
                    continue
                if path.name in self.processed:
                    events.append(dict(self.processed[path.name], status="unchanged",
                                       previous_status=self.processed[path.name]["status"]))
                    continue
                event = self._import_stable(path, signature, expected_content)
                events.append(event)
                if event["status"] in {"imported", "already_imported", "conflict", "rejected"}:
                    self.processed[path.name] = event
            except (OSError, ValidationError) as error:
                events.append(dict(file=path.name, status="error", reason=str(error)))
        for name in self.observed.keys() - present:
            self.observed.pop(name, None)
            self.processed.pop(name, None)
        try:
            rows = summarize(self.output, self.output / "session-index.csv", self.limits)
            summary = dict(status="indexed", sessions=len(rows), daily_total_generated=False)
        except (OSError, ValidationError) as error:
            summary = dict(status="index_error", reason=str(error), daily_total_generated=False)
        return dict(files=events, index=summary, pending=sum(e["status"] == "waiting" for e in events),
                    failed=summary["status"] == "index_error" or any(
                        e.get("previous_status", e["status"]) in {"error", "rejected", "conflict"} for e in events))

    def _import_stable(self, source, expected, expected_content=None):
        if expected[2] == 0:
            return dict(file=source.name, status="waiting", reason="incomplete_zip")
        require(expected[2] <= self.limits.archive_bytes, "archive size quota exceeded")
        staging = self.output / ".sync-staging"
        require(not is_link_like(staging) and (not staging.exists() or staging.is_dir()),
                "sync staging is not a regular directory")
        staging.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix="snapshot-", suffix=".zip", dir=staging)
        snapshot = Path(name)
        try:
            count = 0
            digest = hashlib.sha256() if expected_content is not None else None
            with os.fdopen(fd, "wb") as out, source.open("rb") as inp:
                for chunk in iter(lambda: inp.read(1024 * 1024), b""):
                    count += len(chunk)
                    require(count <= self.limits.archive_bytes, "incoming archive grew beyond size quota")
                    out.write(chunk)
                    if digest is not None:
                        digest.update(chunk)
                out.flush()
                os.fsync(out.fileno())
            if fingerprint(source) != expected or count != expected[2]:
                self.observed.pop(source.name, None)
                return dict(file=source.name, status="waiting", reason="changed_during_snapshot")
            if expected_content is not None:
                expected_digest, expected_bytes = expected_content
                require(count == expected_bytes and digest.hexdigest() == expected_digest,
                        "registered cloud archive changed before import; restore or download it again")
            # A central directory is written only when a ZIP is completed. Keep incomplete downloads
            # waiting; completed archives still pass every CRC/schema/resource check in import_archive.
            if not zipfile.is_zipfile(snapshot):
                return dict(file=source.name, status="waiting", reason="incomplete_zip")
            try:
                result = import_archive(snapshot, self.output, self.limits, expected_archive=expected_content)
                return dict(asdict(result), file=source.name)
            except ArchiveRejected as error:
                return dict(file=source.name, status="rejected", reason=str(error), directory=error.directory)
        finally:
            snapshot.unlink(missing_ok=True)
