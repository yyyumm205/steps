"""Run with python -m backend.ringo_data from the repository root."""

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

from .importer import ArchiveRejected, Limits, import_archive, summarize
from .schema import ValidationError


def main(argv=None):
    parser = argparse.ArgumentParser(description="Validate and import frozen free-living activity ZIPs")
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
    args = parser.parse_args(argv)
    if args.command == "summary":
        rows = summarize(args.root, args.output)
        print(json.dumps({"sessions": len(rows), "daily_total_generated": False}))
        return 0
    if min(args.max_archive_mib, args.max_expanded_mib, args.max_decoded_mib) <= 0:
        parser.error("size limits must be positive")
    limits = Limits(archive_bytes=args.max_archive_mib * 1024 ** 2,
                    expanded_bytes=args.max_expanded_mib * 1024 ** 2,
                    entry_bytes=args.max_expanded_mib * 1024 ** 2,
                    decoded_bytes=args.max_decoded_mib * 1024 ** 2)
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
