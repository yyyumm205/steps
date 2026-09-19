"""Validate the activity manifest without coercing reference or time values."""

from __future__ import annotations

import json
import re
from uuid import UUID

MAX_LONG = (1 << 63) - 1
MAX_UINT = (1 << 32) - 1


class ValidationError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def integer(value, name: str, minimum=0, maximum=MAX_LONG, nullable=False):
    if value is None and nullable:
        return None
    require(type(value) is int and minimum <= value <= maximum, f"invalid integer: {name}")
    return value


def text(value, name: str, maximum=4096):
    require(type(value) is str and 0 < len(value) <= maximum, f"invalid text: {name}")
    return value


def object_value(value, name: str):
    require(type(value) is dict, f"invalid object: {name}")
    return value


def strict_json(data: bytes):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate JSON key")
            result[key] = value
        return result

    def constant(_):
        raise ValidationError("non-finite JSON number")

    try:
        return json.loads(data, object_pairs_hook=pairs, parse_constant=constant)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise ValidationError(f"invalid JSON: {error}") from error


def uuid_value(value, name):
    text(value, name, 36)
    try:
        require(str(UUID(value)) == value, f"noncanonical UUID: {name}")
    except ValueError as error:
        raise ValidationError(f"invalid UUID: {name}") from error


def safe_name(name):
    text(name, "file_name", 160)
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name) is not None,
            "file name must be a flat portable name")
    require(not name.endswith((".", " ")) and name not in (".", ".."), "invalid file name")
    require(name.split(".")[0].upper() not in
            {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)),
             *(f"LPT{i}" for i in range(1, 10))}, "reserved file name")
    return name


def validate_status(value, name, *, allow_charging_error=False):
    value = object_value(value, name)
    require(type(value.get("collecting")) is bool, f"invalid collecting: {name}")
    for key in ("bytes", "records"):
        integer(value.get(key), f"{name}.{key}", maximum=MAX_UINT)
    integer(value.get("device_session_id"), f"{name}.device_session_id", maximum=65535)
    error = integer(value.get("error_code"), f"{name}.error_code",
                    minimum=-16 if allow_charging_error else 0, maximum=255)
    require(error >= 0 or error == -16, f"unsupported negative error: {name}")
    return value


def validate_charging_recovery(baseline):
    evidence = object_value(baseline.get("charging_recovery_evidence"), "charging recovery evidence")
    require(set(evidence) == {
        "status_error_reason", "battery_charge_status", "battery_received_at_ms",
        "status_received_at_ms", "checked_at_ms", "status_connection_generation",
        "battery_connection_generation",
    }, "charging recovery evidence fields mismatch")
    require(integer(evidence["status_error_reason"], "charging error reason") == 1,
            "charging recovery requires charging reason")
    require(integer(evidence["battery_charge_status"], "battery charge status") == 0,
            "charging recovery requires an idle battery response")
    for key in ("battery_received_at_ms", "status_received_at_ms", "checked_at_ms",
                "status_connection_generation", "battery_connection_generation"):
        integer(evidence[key], key, 1)
    require(evidence["status_received_at_ms"] == baseline["observed_at_ms"],
            "charging recovery STATUS is not the baseline observation")
    require(evidence["status_connection_generation"] == evidence["battery_connection_generation"],
            "charging recovery responses came from different connections")
    for key in ("battery_received_at_ms", "status_received_at_ms"):
        require(0 <= evidence["checked_at_ms"] - evidence[key] <= 5000,
                "charging recovery response is stale or from the future")


def validate_record(value, name):
    value = object_value(value, name)
    integer(value.get("device_session_id"), f"{name}.device_session_id", minimum=1, maximum=65535)
    for key in ("bytes", "records", "uptime_ms"):
        integer(value.get(key), f"{name}.{key}", maximum=MAX_UINT)
    integer(value.get("unix_ms"), f"{name}.unix_ms")
    return value


def validate_manifest(value):
    m = object_value(value, "manifest")
    required = {
        "version", "step_schema_version", "rfbin_version", "simulated", "session_id",
        "participant_id", "participant_name", "installation_id", "ring_placement",
        "ring_address", "ring_name", "time_zone_id", "utc_offset_seconds", "capture_purpose",
        "activity_schema", "activity_code", "activity_label_status", "activity_label_source",
        "ground_truth_source", "ground_truth_status", "ground_truth_steps",
        "ground_truth_recorded_at_ms", "ground_truth_reason", "reference_saved_at_ms",
        "data_integrity_status", "download_completed_at_ms", "start_requested_at_ms",
        "start_confirmed_at_ms", "stop_requested_at_ms", "stop_confirmed_at_ms",
        "started_at_ms", "ended_at_ms", "device_session_id", "capture_boundary_status",
        "timing_warnings", "start_status_evidence", "stop_status_evidence",
        "start_boundary_evidence", "end_boundary_evidence", "start_baseline",
        "device_record_evidence", "device_association_invalidated", "files",
    }
    require(set(m) == required, "manifest fields do not match activity schema v2")
    version = integer(m["version"], "version")
    require(version in (2, 3), "unsupported version")
    for key, expected in (("step_schema_version", 1), ("rfbin_version", 2)):
        require(integer(m[key], key) == expected, f"unsupported {key}")
    require(m["simulated"] is False, "simulated archives require the isolated demo path")
    fixed = {"capture_purpose": "daily_activity", "activity_schema": "daily_activity_v2",
             "activity_code": "free_living", "activity_label_status": "unlabelled",
             "activity_label_source": "none", "ground_truth_source": "external_pedometer",
             "data_integrity_status": "complete"}
    for key, expected in fixed.items():
        require(m[key] == expected, f"unsupported or inconsistent {key}")
    uuid_value(m["session_id"], "session_id")
    uuid_value(m["installation_id"], "installation_id")
    require(type(m["participant_id"]) is str and
            re.fullmatch(r"[a-z0-9]{3,24}", m["participant_id"]) is not None,
            "invalid canonical participant_id")
    require(m["participant_name"] == m["participant_id"], "participant identity mismatch")
    require(type(m["ring_placement"]) is str and m["ring_placement"] in {"left_index", "left_middle", "left_ring",
                                    "right_index", "right_middle", "right_ring"},
            "invalid ring placement")
    require(type(m["ring_address"]) is str and
            re.fullmatch(r"(?:[0-9A-F]{2}:){5}[0-9A-F]{2}", m["ring_address"]) is not None,
            "invalid ring address")
    require(type(m["ring_name"]) is str and len(m["ring_name"]) <= 256, "invalid ring name")
    text(m["time_zone_id"], "time_zone_id", 128)
    integer(m["utc_offset_seconds"], "utc_offset_seconds", -64800, 64800)
    for key in ("start_requested_at_ms", "reference_saved_at_ms", "download_completed_at_ms"):
        integer(m[key], key, 1)
    for key in ("start_confirmed_at_ms", "stop_requested_at_ms", "stop_confirmed_at_ms",
                "started_at_ms", "ended_at_ms", "ground_truth_recorded_at_ms"):
        integer(m[key], key, 1, nullable=True)
    require(m["start_confirmed_at_ms"] is not None, "record has no start confirmation")
    require(m["stop_requested_at_ms"] is not None and m["stop_confirmed_at_ms"] is not None,
            "complete upload requires a requested and confirmed stop")
    steps = integer(m["ground_truth_steps"], "ground_truth_steps", nullable=True)
    status = m["ground_truth_status"]
    require(status in ("valid", "missing", "unreliable"), "invalid reference status")
    if status == "valid":
        require(steps is not None and m["stop_confirmed_at_ms"] is not None,
                "valid reference needs a value and stopped confirmation")
        require(m["ground_truth_reason"] is None, "valid reference must not have an abnormal reason")
    else:
        require(text(m["ground_truth_reason"], "ground_truth_reason").strip() != "",
                "abnormal reference requires a reason")
        if status == "missing":
            require(steps is None, "missing reference must have null steps")
    require((m["ground_truth_recorded_at_ms"] is None) == (steps is None),
            "reference value and confirmation time disagree")
    if steps is not None:
        require(m["ground_truth_recorded_at_ms"] == m["reference_saved_at_ms"],
                "reference confirmation time mismatch")
    started, ended = m["started_at_ms"], m["ended_at_ms"]
    require(started is None or ended is None or ended >= started, "capture boundary order invalid")
    expected_boundary = "confirmed" if started is not None and ended is not None else "uncertain"
    require(m["capture_boundary_status"] == expected_boundary, "capture boundary status mismatch")
    require(type(m["timing_warnings"]) is list and
            all(type(x) is str and 0 < len(x) <= 256 for x in m["timing_warnings"]),
            "invalid timing warnings")
    phone_times = [m[k] for k in ("start_requested_at_ms", "start_confirmed_at_ms",
                                "stop_requested_at_ms", "stop_confirmed_at_ms") if m[k] is not None]
    reference_min = m["stop_confirmed_at_ms"] if status == "valid" else m["stop_requested_at_ms"]
    clock_reversed = any(b < a for a, b in zip(phone_times, phone_times[1:]))
    clock_reversed |= reference_min is not None and m["reference_saved_at_ms"] < reference_min
    clock_reversed |= any(m["download_completed_at_ms"] < v for v in
                          (m["stop_confirmed_at_ms"], m["reference_saved_at_ms"]) if v is not None)
    require(not clock_reversed or "phone_clock_order_uncertain" in m["timing_warnings"],
            "reversed phone time requires an explicit warning")
    for key, actual in (("start_boundary_evidence", started), ("end_boundary_evidence", ended)):
        evidence = m[key]
        if evidence is None:
            require(actual is None, "known boundary requires evidence")
        else:
            object_value(evidence, key)
            require(evidence.get("source") in ("device_time_anchor", "raw_sample"), "invalid boundary source")
            integer(evidence.get("epoch_ms"), "boundary epoch", 1)
            integer(evidence.get("device_uptime_ms"), "boundary uptime", maximum=MAX_UINT, nullable=True)
            text(evidence.get("raw_evidence"), "boundary raw evidence")
            require(actual == evidence["epoch_ms"] or
                    (key == "end_boundary_evidence" and actual is None and started is not None and
                     evidence["epoch_ms"] < started and "device_boundary_order_uncertain" in m["timing_warnings"]),
                    "boundary and evidence disagree")
    device_id = integer(m["device_session_id"], "device_session_id", maximum=65535)
    require(m["device_association_invalidated"] is False, "device association invalidated")
    for key, collecting in (("start_status_evidence", True), ("stop_status_evidence", False)):
        evidence = m[key]
        if evidence is None:
            require(key == "stop_status_evidence" and m["stop_confirmed_at_ms"] is None,
                    "missing status evidence")
            continue
        validate_status(evidence, key)
        require(evidence["device_session_id"] == device_id and evidence["collecting"] is collecting
                and evidence["error_code"] == 0, "status evidence inconsistent")
    current = object_value(m["device_record_evidence"], "device_record_evidence")
    record = validate_record(current.get("record"), "record")
    device_status = validate_status(current.get("status"), "record status")
    integer(current.get("observed_at_ms"), "record observation time", 1)
    require(record["device_session_id"] == device_status["device_session_id"] == device_id,
            "device record id mismatch")
    require(record["records"] > 0 and record["bytes"] > 0 and device_status["error_code"] == 0,
            "invalid record evidence")
    require(record["uptime_ms"] > 0 or record["unix_ms"] > 0, "record has no device time fingerprint")
    require(device_status["collecting"] is False, "complete upload still reports collecting")
    require(record["bytes"] == device_status["bytes"] and record["records"] == device_status["records"],
            "record counters disagree")
    for key in ("start_status_evidence", "stop_status_evidence"):
        require(all(m[key][counter] <= record[counter] for counter in ("bytes", "records")),
                "device record counters moved backwards")
    baseline = object_value(m["start_baseline"], "start_baseline")
    baseline_status = validate_status(baseline.get("status"), "baseline status", allow_charging_error=version == 3)
    require(baseline_status["collecting"] is False and baseline_status["error_code"] == (-16 if version == 3 else 0),
            "invalid start baseline")
    integer(baseline.get("observed_at_ms"), "baseline observation time", 1)
    if version == 3:
        validate_charging_recovery(baseline)
    else:
        require("charging_recovery_evidence" not in baseline,
                "charging recovery evidence requires manifest version 3")
    require(type(baseline.get("records")) is list and len(baseline["records"]) <= 255, "invalid baseline records")
    for item in baseline["records"]:
        validate_record(item, "baseline record")
    baseline_ids = [item["device_session_id"] for item in baseline["records"]]
    require(len(set(baseline_ids)) == len(baseline_ids), "duplicate baseline record ids")
    if not baseline["records"]:
        require(baseline_status["bytes"] == baseline_status["records"] == 0, "empty baseline has nonzero counters")
    else:
        require(any(all(item[key] == baseline_status[key] for key in ("device_session_id", "bytes", "records"))
                    for item in baseline["records"]), "baseline STATUS and LIST disagree")
    previous = next((item for item in baseline["records"] if item["device_session_id"] == device_id), None)
    if previous is None:
        require(device_id != baseline_status["device_session_id"], "record not distinguishable from baseline")
    else:
        require(previous["unix_ms"] > 0 and record["unix_ms"] > 0 and previous["uptime_ms"] > 0 and record["uptime_ms"] > 0
                and previous["unix_ms"] != record["unix_ms"] and previous["uptime_ms"] != record["uptime_ms"],
                "reused record id lacks two changed nonzero time anchors")
    files = m["files"]
    require(type(files) is list and len(files) > 0, "empty file list")
    names = set()
    for entry in files:
        object_value(entry, "file entry")
        require(set(entry) == {"file_name", "role", "device_session_id", "bytes", "sha256", "simulated"},
                "invalid file entry fields")
        name = safe_name(entry["file_name"])
        require(name.casefold() not in names and name.lower() != "manifest.json", "duplicate file entry")
        names.add(name.casefold())
        require(name.startswith(m["session_id"] + "-"), "file does not belong to session")
        require(entry["role"] in ("raw", "evidence"), "invalid file role")
        suffix = ".rfbin" if entry["role"] == "raw" else ".raw-evidence.json"
        require(name.endswith(suffix), "file extension disagrees with role")
        require(integer(entry["device_session_id"], "file device id", maximum=65535) == device_id,
                "file device id mismatch")
        integer(entry["bytes"], "file bytes", 1)
        require(type(entry["sha256"]) is str and re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]),
                "invalid file SHA-256")
        require(entry["simulated"] is False, "simulated file in real archive")
    require(any(x["role"] == "raw" for x in files), "archive has no raw file")
    return m
