"""Validate Polar HR/RR rows without manufacturing heartbeat or missing samples.

The original Android format timestamps BLE notification receipt on the phone.
Several samples can therefore legitimately share one timestamp. This module
retains that meaning and never turns receipt time into exact physiological time.
"""

from __future__ import annotations

import csv
import datetime as dt
import os
import re
from pathlib import Path

from .health_raw_v2 import BudgetedText, OutputBudget
from .schema import MAX_LONG, ValidationError, require


FIELDS = ["timestamp_iso", "timestamp_unix_ms", "sample_index", "hr_bpm", "corrected_hr_bpm",
          "ppg_quality", "rr_available", "contact_supported", "contact_status", "rr_ms", "rr_1_1024s"]
PREFIX_FIELDS = ["session_id", "participant_id", "activity_code", "timestamp_source",
                 "phone_receipt_offset_ms"]
MAX_LINE_CHARACTERS = 64 * 1024
MAX_RR_VALUES_PER_ROW = 4096


def decimal(value, name, minimum=0, maximum=MAX_LONG):
    # Bounds are serialization limits, not physiological acceptance thresholds.
    expression = r"(?:0|[1-9][0-9]*)" if minimum >= 0 else r"(?:0|-?[1-9][0-9]*)"
    require(type(value) is str and len(value) <= 20 and re.fullmatch(expression, value) is not None,
            "invalid heart rate CSV integer: " + name)
    parsed = int(value)
    require(minimum <= parsed <= maximum, "out-of-range heart rate CSV integer: " + name)
    return parsed


def receipt_iso(value, expected_ms):
    # Java Instant generated from epoch milliseconds emits a UTC Z timestamp.
    match = re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.(\d{1,9}))?Z", value)
    require(match is not None, "invalid heart rate CSV timestamp_iso")
    fraction = match.group(1) or ""
    require(not fraction[3:].strip("0"), "heart rate CSV timestamp has sub-millisecond precision")
    try:
        instant = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        delta = instant - dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)
        actual_ms = (delta.days * 86400 + delta.seconds) * 1000 + delta.microseconds // 1000
    except (ValueError, OverflowError) as error:
        raise ValidationError("invalid heart rate CSV timestamp_iso") from error
    require(actual_ms == expected_ms, "heart rate CSV ISO and numeric timestamps disagree")


def rr_values(value, name):
    if value == "":
        return []
    parts = value.split("|")
    require(len(parts) <= MAX_RR_VALUES_PER_ROW, "heart rate RR row quota exceeded")
    return [decimal(part, name, maximum=65535) for part in parts]


def bounded_lines(stream):
    while line := stream.readline(MAX_LINE_CHARACTERS + 1):
        require(len(line) <= MAX_LINE_CHARACTERS, "heart rate CSV line quota exceeded")
        yield line


def union_duration(intervals):
    total = 0
    previous = None
    for start, end in sorted(intervals):
        if previous is None:
            previous = (start, end)
        elif start <= previous[1]:
            previous = (previous[0], max(previous[1], end))
        else:
            total += previous[1] - previous[0]
            previous = (start, end)
    return total + (previous[1] - previous[0] if previous else 0)


def import_heart_rate(stage: Path, manifest: dict, budget: OutputBudget):
    metadata = manifest.get("heart_rate")
    if metadata is None:
        return None
    entries = [entry for entry in manifest["files"] if entry["role"] == "polar_hr_rr"]
    result = {
        "enabled": metadata["enabled"], "device_id": metadata["device_id"],
        "device_name": metadata["device_name"], "status": metadata["status"],
        "timestamp_source": "phone_receipt", "sample_coverage_status": "not_assessed",
        "physiological_sample_time_known": False, "resampling_applied": False,
        "started_at_ms": metadata["started_at_ms"], "ended_at_ms": metadata["ended_at_ms"],
        "first_sample_at_ms": metadata["first_sample_at_ms"],
        "last_sample_at_ms": metadata["last_sample_at_ms"], "sample_count": 0,
        "reported_gaps": metadata["gaps"], "reported_gap_union_ms": None,
        "timing_warnings": [],
        "sample_receipt_span_ms": None, "first_sample_delay_ms": None,
        "last_sample_to_end_ms": None, "maximum_receipt_interval_ms": None,
        "same_receipt_timestamp_rows": 0, "zero_hr_samples": 0,
        "contact_not_detected_samples": 0, "contact_unsupported_samples": 0,
        "rr_sample_rows": 0, "rr_interval_count": 0, "zero_rr_intervals": 0,
        "original_csv": entries[0]["file_name"] if entries else None,
        "normalized_csv": None,
    }
    if metadata["enabled"]:
        start, end = metadata["started_at_ms"], metadata["ended_at_ms"]
        if end < start or any(gap["ended_at_ms"] < gap["started_at_ms"] for gap in metadata["gaps"]):
            result["timing_warnings"].append("heart_rate_phone_clock_order_uncertain")
        if any(not start <= gap["started_at_ms"] <= gap["ended_at_ms"] <= end
               for gap in metadata["gaps"]):
            result["timing_warnings"].append("heart_rate_capture_boundary_uncertain")
        if not result["timing_warnings"]:
            result["reported_gap_union_ms"] = union_duration(
                (gap["started_at_ms"], gap["ended_at_ms"]) for gap in metadata["gaps"])
    else:
        result["reported_gap_union_ms"] = 0
    if not entries:
        return result
    source = stage / "raw" / entries[0]["file_name"]
    destination = stage / "derived" / (manifest["session_id"] + "_polar_hr_rr.csv")
    destination.parent.mkdir(exist_ok=True)
    previous = first = None
    try:
        with source.open(encoding="utf-8", newline="") as inp, destination.open(
                "x", encoding="utf-8", newline="") as out:
            reader = csv.reader(bounded_lines(inp), strict=True)
            require(next(reader, None) == FIELDS, "heart rate CSV header mismatch")
            writer = csv.writer(BudgetedText(out, budget), lineterminator="\n")
            writer.writerow(PREFIX_FIELDS + FIELDS)
            for values in reader:
                require(len(values) == len(FIELDS), "heart rate CSV row field count mismatch")
                row = dict(zip(FIELDS, values))
                count = result["sample_count"] + 1
                require(count <= metadata["sample_count"], "heart rate CSV has more samples than manifest")
                require(decimal(row["sample_index"], "sample_index", 1) == count,
                        "heart rate CSV sample index is not contiguous")
                epoch = decimal(row["timestamp_unix_ms"], "timestamp_unix_ms", 1)
                receipt_iso(row["timestamp_iso"], epoch)
                if not metadata["started_at_ms"] <= epoch <= metadata["ended_at_ms"]:
                    if "heart_rate_capture_boundary_uncertain" not in result["timing_warnings"]:
                        result["timing_warnings"].append("heart_rate_capture_boundary_uncertain")
                if previous is not None and epoch < previous:
                    if "heart_rate_phone_clock_order_uncertain" not in result["timing_warnings"]:
                        result["timing_warnings"].append("heart_rate_phone_clock_order_uncertain")
                hr = decimal(row["hr_bpm"], "hr_bpm", maximum=65535)
                # Polar SDK exposes Int for corrected HR/quality, including SDK-specific
                # values. Retain those values instead of inventing a quality threshold.
                for field in ("corrected_hr_bpm", "ppg_quality"):
                    decimal(row[field], field, -(1 << 31), (1 << 31) - 1)
                for field in ("rr_available", "contact_supported", "contact_status"):
                    require(row[field] in ("true", "false"), "invalid heart rate CSV boolean: " + field)
                rr_ms = rr_values(row["rr_ms"], "rr_ms")
                rr_raw = rr_values(row["rr_1_1024s"], "rr_1_1024s")
                require(len(rr_ms) == len(rr_raw), "heart rate RR arrays have different lengths")
                result["sample_count"] = count
                result["zero_hr_samples"] += hr == 0
                result["contact_unsupported_samples"] += row["contact_supported"] == "false"
                result["contact_not_detected_samples"] += (
                    row["contact_supported"] == "true" and row["contact_status"] == "false")
                result["rr_sample_rows"] += bool(rr_ms)
                result["rr_interval_count"] += len(rr_ms)
                result["zero_rr_intervals"] += sum(value == 0 for value in rr_ms)
                if previous is not None:
                    interval = epoch - previous
                    result["same_receipt_timestamp_rows"] += interval == 0
                    if interval >= 0:
                        result["maximum_receipt_interval_ms"] = max(
                            result["maximum_receipt_interval_ms"] or 0, interval)
                first = epoch if first is None else first
                previous = epoch
                writer.writerow([manifest["session_id"], manifest["participant_id"], manifest["activity_code"],
                                 "phone_receipt", epoch - metadata["started_at_ms"]] + values)
            out.flush()
            os.fsync(out.fileno())
    except csv.Error as error:
        raise ValidationError("invalid heart rate CSV: " + str(error)) from error
    require(result["sample_count"] == metadata["sample_count"], "heart rate CSV sample count mismatch")
    require(first == metadata["first_sample_at_ms"] and previous == metadata["last_sample_at_ms"],
            "heart rate CSV sample bounds differ from manifest")
    if first is not None and not result["timing_warnings"]:
        result.update(sample_receipt_span_ms=previous - first,
                      first_sample_delay_ms=first - metadata["started_at_ms"],
                      last_sample_to_end_ms=metadata["ended_at_ms"] - previous)
    if result["timing_warnings"]:
        result["reported_gap_union_ms"] = None
        result["maximum_receipt_interval_ms"] = None
    result["normalized_csv"] = destination.name
    return result
