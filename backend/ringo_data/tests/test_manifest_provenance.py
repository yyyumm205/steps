"""Manifest v6/v7 provenance additions preserve every accepted legacy contract."""

import copy
import json
from pathlib import Path

import pytest

from backend.ringo_data.importer import ArchiveRejected, import_archive
from backend.ringo_data.tests.test_contract_consistency import manifest_version
from backend.ringo_data.tests.test_importer import archive_at, read_rows


def provenance_manifest(base_version=4):
    manifest = copy.deepcopy(manifest_version(base_version))
    hand, finger = manifest["ring_placement"].split("_", 1)
    manifest.update(version=6, ring_placement_schema="hand_finger_v1",
                    ring_hand=hand, ring_finger=finger,
                    app_version="0.8.1-local-recovery",
                    created_at="2026-09-21T08:09:10.123Z")
    return manifest


def stop_provenance_manifest(origin="user_request", base_version=4):
    manifest = provenance_manifest(base_version)
    manifest.update(version=7, stop_origin=origin, stop_observed_at_ms=None)
    if origin == "device_observed":
        manifest["stop_observed_at_ms"] = manifest["stop_requested_at_ms"]
        manifest["stop_requested_at_ms"] = None
    return manifest


@pytest.mark.parametrize("base_version", [2, 3, 4, 5])
def test_v6_preserves_all_existing_evidence_combinations_and_exports_provenance(tmp_path, base_version):
    source = tmp_path / f"v6-from-{base_version}.zip"
    expected = archive_at(source, manifest=provenance_manifest(base_version))
    result = import_archive(source, tmp_path / "out")
    assert result.status == "imported"
    row = read_rows(Path(result.directory) / "reference.csv")[0]
    for key in ("ring_placement_schema", "ring_placement", "ring_hand", "ring_finger",
                "app_version", "created_at"):
        assert row[key] == str(expected[key])


@pytest.mark.parametrize("field", [
    "ring_placement_schema", "ring_hand", "ring_finger", "app_version", "created_at",
])
def test_v6_requires_every_provenance_field(tmp_path, field):
    manifest = provenance_manifest()
    del manifest[field]
    source = tmp_path / f"missing-{field}.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected, match="manifest fields"):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("field,value,match", [
    ("ring_placement_schema", "hand_v2", "placement schema"),
    ("ring_hand", "right", "components disagree"),
    ("ring_finger", "middle", "components disagree"),
    ("app_version", "", "app_version"),
    ("created_at", "2026-09-21 08:09:10", "created_at"),
    ("created_at", "2026-02-30T08:09:10Z", "created_at"),
])
def test_v6_rejects_inconsistent_or_invalid_provenance(tmp_path, field, value, match):
    manifest = provenance_manifest()
    manifest[field] = value
    source = tmp_path / f"invalid-{field}.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected, match=match):
        import_archive(source, tmp_path / "out")


def test_v6_still_rejects_unknown_top_level_fields(tmp_path):
    manifest = provenance_manifest()
    manifest["unreviewed_field"] = True
    source = tmp_path / "unknown.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected, match="manifest fields"):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("base_version", [2, 3, 4, 5])
@pytest.mark.parametrize("origin,quality_reason", [
    ("user_request", None),
    ("device_observed", "device_observed_stop"),
    ("legacy_unspecified", "legacy_stop_origin_unspecified"),
])
def test_v7_exports_stop_provenance_without_reinterpreting_legacy_evidence(
        tmp_path, base_version, origin, quality_reason):
    manifest = stop_provenance_manifest(origin, base_version)
    source = tmp_path / f"v7-{base_version}-{origin}.zip"
    archive_at(source, manifest=manifest)
    result = import_archive(source, tmp_path / "out")
    assert result.status == "imported"
    row = read_rows(Path(result.directory) / "reference.csv")[0]
    assert row["stop_origin"] == origin
    assert row["stop_requested_at_ms"] == ("" if origin == "device_observed" else str(manifest["stop_requested_at_ms"]))
    assert row["stop_observed_at_ms"] == (str(manifest["stop_observed_at_ms"])
                                                if origin == "device_observed" else "")
    reasons = json.loads((Path(result.directory) / "quality.json").read_text())["analysis_reasons"]
    if quality_reason is None:
        assert "device_observed_stop" not in reasons
        assert "legacy_stop_origin_unspecified" not in reasons
    else:
        assert quality_reason in reasons


@pytest.mark.parametrize("origin,requested,observed", [
    ("user_request", None, None),
    ("user_request", 10, 11),
    ("device_observed", 10, 11),
    ("device_observed", None, None),
    ("legacy_unspecified", None, None),
    ("legacy_unspecified", 10, 11),
    ("unknown", 10, None),
])
def test_v7_rejects_inconsistent_stop_provenance(tmp_path, origin, requested, observed):
    manifest = stop_provenance_manifest()
    manifest.update(stop_origin=origin, stop_requested_at_ms=requested, stop_observed_at_ms=observed)
    source = tmp_path / f"invalid-stop-{origin}-{requested}-{observed}.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected, match="stop|origin"):
        import_archive(source, tmp_path / "out")


@pytest.mark.parametrize("version", [2, 3, 4, 5])
def test_legacy_versions_keep_their_exact_original_field_sets(tmp_path, version):
    manifest = manifest_version(version)
    manifest["app_version"] = "unexpected"
    source = tmp_path / f"legacy-{version}.zip"
    archive_at(source, manifest=manifest)
    with pytest.raises(ArchiveRejected, match="manifest fields"):
        import_archive(source, tmp_path / "out")
