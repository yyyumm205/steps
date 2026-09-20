"""HEALTH v2 decoding with original packet time retained.

Header layout, packet parsing and acceleration conversion derive from the
0.5.3 handoff decoder. Its epoch fallback and time-normalization are deliberately
absent: device-clock estimates and research timestamps have separate columns.
"""

from __future__ import annotations

import csv
import datetime as dt
import struct
import zlib
from dataclasses import asdict, dataclass
from pathlib import Path

from .schema import require

MAGIC = b"RFV2RAW\0"
HEADER = struct.Struct("<8sHHHHIIqqqQII")
HEADER_SIZE = 64


@dataclass(frozen=True)
class Header:
    session_id: int
    records: int
    anchor_uptime_ms: int
    anchor_unix_ms: int
    started_at_ms: int
    ended_at_ms: int
    payload_bytes: int
    payload_crc32: int


def read_header(path: Path) -> Header:
    with path.open("rb") as source:
        raw = source.read(HEADER_SIZE)
    require(len(raw) == HEADER_SIZE, "truncated rfbin header")
    magic, version, size, session, flags, records, uptime, anchor, start, end, length, crc, reserved = HEADER.unpack(raw)
    require(magic == MAGIC and version == 2 and size == HEADER_SIZE, "invalid rfbin header/version")
    require(flags == reserved == 0, "unsupported rfbin flags")
    require(path.stat().st_size == HEADER_SIZE + length, "rfbin payload length mismatch")
    require(records > 0 and length > 0 and anchor >= 0 and start >= 0 and end >= 0,
            "invalid rfbin header values")
    require(start == 0 or end == 0 or end >= start, "rfbin boundary order invalid")
    return Header(session, records, uptime, anchor, start, end, length, crc)


def iso(milliseconds):
    if milliseconds is None:
        return ""
    try:
        return (dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc) +
                dt.timedelta(milliseconds=milliseconds)).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    except (OverflowError, ValueError):
        return ""


class OutputBudget:
    """Shared decoded-text quota; limits are engineering bounds, not duration claims."""

    def __init__(self, maximum):
        self.maximum = maximum
        self.used = 0

    def write(self, handle, value):
        self.used += len(value.encode("utf-8"))
        require(self.used <= self.maximum, "decoded output quota exceeded")
        return handle.write(value)


class BudgetedText:
    def __init__(self, handle, budget):
        self.handle, self.budget = handle, budget

    def write(self, value):
        return self.budget.write(self.handle, value)


def decode_to_csv(source_path: Path, destination: Path, manifest: dict,
                  budget: OutputBudget | None = None) -> dict:
    header = read_header(source_path)
    budget = budget or OutputBudget(2 * 1024 ** 3)
    destination.mkdir(parents=True, exist_ok=True)
    streams, writers = {}, {}
    channels = {
        "imu": {"samples": 0, "packets": 0, "first_uptime_ms": None, "last_uptime_ms": None,
                "first_packet_uptime_ms": None, "last_packet_uptime_ms": None,
                "gaps": 0, "overlaps": 0, "rollbacks": 0},
        "ppg": {"samples": 0, "packets": 0, "first_uptime_ms": None, "last_uptime_ms": None,
                "first_packet_uptime_ms": None, "last_packet_uptime_ms": None,
                "gaps": 0, "overlaps": 0, "rollbacks": 0},
    }
    events = []
    processed = crc = records = 0
    common = ["activity_session_id", "participant_id", "source_file", "device_session_id",
              "timestamp_iso", "timestamp_unix_ms", "ring_uptime_ms", "packet_uptime_ms",
              "relative_sample_offset_ms", "relative_to_device_anchor_ms",
              "device_anchor_estimated_unix_ms", "device_anchor_estimated_iso",
              "time_source", "packet_index", "packet_seq", "sample_index", "activity_code",
              "activity_truth", "activity_label_status", "activity_label_source"]

    def writer(channel):
        if channel not in writers:
            prefix = "ringfitness_imu_lp_acc_50hz_" if channel == "imu" else "ringfitness_ppg_green_ir_25hz_"
            handle = (destination / (prefix + source_path.stem + ".csv")).open("w", encoding="utf-8", newline="")
            streams[channel] = handle
            writers[channel] = csv.writer(BudgetedText(handle, budget), lineterminator="\n")
            extra = (["accel_x_raw", "accel_y_raw", "accel_z_raw", "accel_x_ms2", "accel_y_ms2", "accel_z_ms2"]
                     if channel == "imu" else ["mode", "channels_mask", "green_raw", "red_raw", "infrared_raw"])
            writers[channel].writerow(common + extra)
        return writers[channel]

    def timing(channel, packet_uptime, count, interval, packet_index):
        stats = channels[channel]
        first = (packet_uptime - (count - 1) * interval) & 0xFFFFFFFF
        if stats["last_uptime_ms"] is not None:
            deviation = first - stats["last_uptime_ms"] - interval
            if deviation != 0:
                kind = "gaps" if deviation > 0 else "overlaps"
                stats[kind] += 1
                if len(events) < 1000:
                    events.append({"channel": channel, "packet_index": packet_index,
                                   "kind": kind, "deviation_ms": deviation})
            if packet_uptime < stats["last_packet_uptime_ms"]:
                stats["rollbacks"] += 1
        if stats["first_uptime_ms"] is None:
            stats["first_uptime_ms"] = first
            stats["first_packet_uptime_ms"] = packet_uptime
        stats["last_uptime_ms"] = packet_uptime
        stats["last_packet_uptime_ms"] = packet_uptime
        stats["packets"] += 1
        return stats

    def row_prefix(stats, uptime, packet_uptime, packet_index, sequence):
        anchor_delta = uptime - header.anchor_uptime_ms
        estimated = header.anchor_unix_ms + anchor_delta if header.anchor_unix_ms > 0 else None
        stats["samples"] += 1
        return [manifest["session_id"], manifest["participant_id"], source_path.name, header.session_id,
                "", "", uptime, packet_uptime, uptime - stats["first_uptime_ms"], anchor_delta,
                estimated, iso(estimated), "uncalibrated_device_clock", packet_index, sequence,
                stats["samples"], manifest["activity_code"], "", manifest["activity_label_status"],
                manifest["activity_label_source"]]

    with source_path.open("rb") as source:
        source.seek(HEADER_SIZE)

        def read(count):
            require(count <= header.payload_bytes - processed, "packet crosses rfbin payload boundary")
            data = source.read(count)
            require(len(data) == count, "truncated HEALTH packet")
            return data

        try:
            while processed < header.payload_bytes:
                command = read(2)
                require(command[0] == 0x32, "invalid HEALTH command")
                subcommand = command[1]
                if subcommand == 0x10:
                    packet = command + read(15)
                elif subcommand == 0x12:
                    fixed = read(5)
                    count, uptime = struct.unpack("<BI", fixed)
                    require(count > 0, "empty IMU packet")
                    samples = read(count * 6)
                    packet = command + fixed + samples
                    stats = timing("imu", uptime, count, 20, records)
                    output = writer("imu")
                    for index in range(count):
                        xyz = struct.unpack_from("<hhh", samples, index * 6)
                        sample_uptime = (uptime - (count - 1 - index) * 20) & 0xFFFFFFFF
                        output.writerow(row_prefix(stats, sample_uptime, uptime, records, "") +
                                        list(xyz) + [f"{v / 2048.0 * 9.80665:.6f}" for v in xyz])
                elif subcommand == 0x11:
                    fixed = read(10)
                    sequence, mode = struct.unpack_from("<HB", fixed)
                    count, mask = fixed[4], fixed[5]
                    require(count > 0 and mask > 0 and mask & ~7 == 0, "invalid PPG channel/count")
                    channel_count = sum(bool(mask & bit) for bit in (1, 2, 4))
                    uptime = struct.unpack_from("<I", fixed, 6)[0]
                    samples = read(count * channel_count * 4)
                    packet = command + fixed + samples
                    stats = timing("ppg", uptime, count, 40, records)
                    output, cursor = writer("ppg"), 0
                    for index in range(count):
                        values = []
                        for bit in (1, 2, 4):
                            if mask & bit:
                                values.append(struct.unpack_from("<i", samples, cursor)[0])
                                cursor += 4
                            else:
                                values.append("")
                        sample_uptime = (uptime - (count - 1 - index) * 40) & 0xFFFFFFFF
                        output.writerow(row_prefix(stats, sample_uptime, uptime, records, sequence) +
                                        [mode, mask] + values)
                else:
                    require(False, "unknown HEALTH packet type")
                processed += len(packet)
                require(processed <= header.payload_bytes, "packet crosses rfbin boundary")
                crc = zlib.crc32(packet, crc)
                records += 1
            require(crc & 0xFFFFFFFF == header.payload_crc32, "rfbin payload CRC mismatch")
            require(records == header.records, "rfbin record count mismatch")
            require(channels["imu"]["samples"] + channels["ppg"]["samples"] > 0,
                    "rfbin contains no IMU/PPG samples")
        finally:
            for stream in streams.values():
                stream.close()
    return {"source_file": source_path.name, "header": asdict(header), "parsed_records": records,
            "channels": channels, "timing_events": events, "timing_event_detail_limit": 1000,
            "sample_clock_status": "uncalibrated", "sample_coverage_status": "not_assessed",
            "normalization_applied": False, "csv_files": sorted(Path(stream.name).name for stream in streams.values())}
