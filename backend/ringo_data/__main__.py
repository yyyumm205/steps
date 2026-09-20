"""Run with python -m backend.ringo_data from the repository root."""

import argparse
import json
import math
import sys
import time
from dataclasses import asdict
from pathlib import Path

from . import __version__
from .importer import ArchiveRejected, Limits, import_archive, summarize
from .schema import ValidationError
from .sync import DirectorySync
from .cloud import CloudConfig, CloudSync


def main(argv=None):
    parser = argparse.ArgumentParser(description="Validate and import frozen activity collection ZIPs")
    parser.add_argument("--version", action="version", version=f"ringo_data {__version__}")
    commands = parser.add_subparsers(dest="command", required=True)
    ingest = commands.add_parser("import", help="validate, preserve and decode local archives")
    ingest.add_argument("archives", nargs="+", type=Path)
    ingest.add_argument("--output", required=True, type=Path)
    ingest.add_argument("--max-archive-mib", type=int, default=512)
    ingest.add_argument("--max-expanded-mib", type=int, default=1024)
    ingest.add_argument("--max-decoded-mib", type=int, default=2048)
    summary = commands.add_parser("summary", help="write one reference row per admitted session")
    summary.add_argument("--root", required=True, type=Path)
    summary.add_argument("--output", required=True, type=Path)
    sync = commands.add_parser("sync", help="automatically import stable ZIPs from a local cloud-sync directory")
    sync.add_argument("--incoming", required=True, type=Path)
    sync.add_argument("--output", required=True, type=Path)
    sync.add_argument("--once", action="store_true", help="observe, wait for stability, then process one batch and exit")
    sync.add_argument("--interval-seconds", type=float, default=5)
    sync.add_argument("--stable-seconds", type=float, default=10)
    sync.add_argument("--max-archive-mib", type=int, default=512)
    sync.add_argument("--max-expanded-mib", type=int, default=1024)
    sync.add_argument("--max-decoded-mib", type=int, default=2048)
    cloud = commands.add_parser("cloud-sync", help="read one configured Seafile directory and import its ZIPs")
    cloud.add_argument("--config", required=True, type=Path)
    cloud.add_argument("--once", action="store_true", help="download and import one batch, then exit")
    args = parser.parse_args(argv)
    if args.command == "summary":
        try:
            rows = summarize(args.root, args.output)
            print(json.dumps({"sessions": len(rows), "daily_total_generated": False}))
            return 0
        except (OSError, ValidationError) as error:
            print(json.dumps({"status": "index_error", "reason": str(error)}, ensure_ascii=False), file=sys.stderr)
            return 2
    if args.command == "cloud-sync":
        try:
            config = CloudConfig.load(args.config)
            reader = CloudSync(config)
            if args.once:
                result = reader.once()
                print(json.dumps(result, ensure_ascii=False), flush=True)
                return 2 if result["failed"] else 0
            while True:
                print(json.dumps(reader.poll(), ensure_ascii=False), flush=True)
                time.sleep(config.interval_seconds)
        except KeyboardInterrupt:
            return 0
        except (OSError, ValidationError) as error:
            reason = str(error) if isinstance(error, ValidationError) else "local cloud configuration or storage is unavailable"
            print(json.dumps({"status": "cloud_sync_error", "reason": reason}, ensure_ascii=False), file=sys.stderr)
            return 2
    if min(args.max_archive_mib, args.max_expanded_mib, args.max_decoded_mib) <= 0:
        parser.error("size limits must be positive")
    limits = Limits(archive_bytes=args.max_archive_mib * 1024 ** 2,
                    expanded_bytes=args.max_expanded_mib * 1024 ** 2,
                    entry_bytes=args.max_expanded_mib * 1024 ** 2,
                    decoded_bytes=args.max_decoded_mib * 1024 ** 2)
    if args.command == "sync":
        if not math.isfinite(args.interval_seconds) or args.interval_seconds <= 0:
            parser.error("interval seconds must be finite and positive")
        try:
            scanner = DirectorySync(args.incoming, args.output, stable_seconds=args.stable_seconds, limits=limits)
            first = scanner.scan()
            print(json.dumps(first, ensure_ascii=False), flush=True)
            if args.once:
                time.sleep(args.stable_seconds)
                result = scanner.scan()
                print(json.dumps(result, ensure_ascii=False), flush=True)
                return 2 if result["failed"] else 0
            while True:
                time.sleep(args.interval_seconds)
                print(json.dumps(scanner.scan(), ensure_ascii=False), flush=True)
        except KeyboardInterrupt:
            return 0
        except (OSError, ValidationError) as error:
            print(json.dumps({"status": "sync_error", "reason": str(error)}, ensure_ascii=False), file=sys.stderr)
            return 2
    failed = False
    for archive in args.archives:
        try:
            result = import_archive(archive, args.output, limits)
            print(json.dumps(asdict(result), ensure_ascii=False))
            failed |= result.status == "conflict"
        except (ValidationError, OSError) as error:
            failed = True
            print(json.dumps({"status": "rejected", "reason": str(error),
                              "directory": error.directory if isinstance(error, ArchiveRejected) else None},
                             ensure_ascii=False), file=sys.stderr)
    return 2 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
