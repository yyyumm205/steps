"""Phone-window placement of an uncalibrated sample timeline.

This derived estimate preserves device intervals. Its feasible offset interval
assumes a stable phone clock, nominal device tick rate and correctly associated
samples contained by the phone START request and STOP confirmation. It is not
a measured clock-sync error bound or evidence of complete activity coverage.
"""

from __future__ import annotations

import csv
import os

from .health_raw_v2 import BudgetedText, OutputBudget, iso
from .schema import require


FIELDS = ["phone_estimated_unix_ms", "phone_estimated_iso", "phone_earliest_unix_ms",
          "phone_latest_unix_ms", "phone_time_source"]
METHOD = "phone_capture_window_v1"


def alignment_plan(manifest, reports, directory):
    result = {
        "method": METHOD, "status": "unavailable", "reason": None,
        "phone_window_start_ms": manifest["start_requested_at_ms"],
        "phone_window_end_ms": manifest["stop_confirmed_at_ms"],
        "first_sample_uptime_ms": None, "last_sample_uptime_ms": None,
        "offset_min_ms": None, "offset_max_ms": None, "estimated_offset_ms": None,
        "placement_half_width_ms": None,
        "assumptions": ["stable_phone_wall_clock", "nominal_device_tick_rate",
                        "associated_samples_within_phone_capture_window"],
        "clock_drift_measured": False, "capture_boundaries_confirmed": False,
    }

    def unavailable(reason):
        result["reason"] = reason
        return result

    phone = [manifest[key] for key in ("start_requested_at_ms", "start_confirmed_at_ms",
                                      "stop_requested_at_ms", "stop_confirmed_at_ms")]
    if any(type(t) is not int or t <= 0 for t in phone):
        return unavailable("phone_capture_window_missing")
    if any(b < a for a, b in zip(phone, phone[1:])) or manifest["timing_warnings"]:
        return unavailable("phone_or_device_timing_warning")
    if manifest["device_association_invalidated"]:
        return unavailable("device_association_invalidated")
    # A 32-bit uptime cannot disambiguate a complete turn without a boot/clock probe.
    if phone[-1] - phone[0] >= 2 ** 32:
        return unavailable("phone_window_exceeds_uptime_cycle")
    if len(reports) != 1:
        return unavailable("multiple_files_need_shared_clock_evidence")
    if any(channel["rollbacks"] for channel in reports[0]["channels"].values()):
        return unavailable("device_uptime_rollback_or_wrap")

    first = last = None
    for name in reports[0]["csv_files"]:
        with (directory / name).open(encoding="utf-8", newline="") as stream:
            for row in csv.DictReader(stream):
                uptime, packet = int(row["ring_uptime_ms"]), int(row["packet_uptime_ms"])
                if uptime > packet:
                    return unavailable("device_uptime_rollback_or_wrap")
                first = uptime if first is None else min(first, uptime)
                last = uptime if last is None else max(last, uptime)
    if first is None:
        return unavailable("samples_missing")
    result.update(first_sample_uptime_ms=first, last_sample_uptime_ms=last)
    low, high = phone[0] - first, phone[-1] - last
    if low > high:
        return unavailable("signal_span_exceeds_phone_window")
    result.update(status="estimated", offset_min_ms=low, offset_max_ms=high,
                  estimated_offset_ms=(low + high) // 2,
                  placement_half_width_ms=(high - low) / 2)
    return result


def add_phone_time(manifest, reports, directory, max_decoded_bytes):
    """Append explicit estimates inside the importer's unpublished staging tree."""
    plan = alignment_plan(manifest, reports, directory)
    budget = OutputBudget(max_decoded_bytes)
    for report in reports:
        for name in report["csv_files"]:
            source = directory / name
            temporary = source.with_suffix(".phone-time.tmp")
            with source.open(encoding="utf-8", newline="") as inp, temporary.open(
                    "x", encoding="utf-8", newline="") as out:
                reader = csv.DictReader(inp)
                require(reader.fieldnames is not None and not set(FIELDS) & set(reader.fieldnames),
                        "phone alignment columns already present")
                writer = csv.DictWriter(BudgetedText(out, budget), fieldnames=reader.fieldnames + FIELDS,
                                        lineterminator="\n")
                writer.writeheader()
                for row in reader:
                    values = dict.fromkeys(FIELDS, "")
                    values["phone_time_source"] = METHOD if plan["status"] == "estimated" else "unavailable"
                    if plan["status"] == "estimated":
                        uptime = int(row["ring_uptime_ms"])
                        estimate = uptime + plan["estimated_offset_ms"]
                        values.update(phone_estimated_unix_ms=estimate, phone_estimated_iso=iso(estimate),
                                      phone_earliest_unix_ms=uptime + plan["offset_min_ms"],
                                      phone_latest_unix_ms=uptime + plan["offset_max_ms"])
                    writer.writerow(dict(row, **values))
                out.flush()
                os.fsync(out.fileno())
            temporary.replace(source)
    return plan
