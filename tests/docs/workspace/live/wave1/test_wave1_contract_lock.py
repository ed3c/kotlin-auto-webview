from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]
CHECKER = ROOT / "scripts/ci/check-workspace-live-wave1.py"
LOCK = ROOT / "docs/workspace/live/wave1/contract-lock.json"

spec = importlib.util.spec_from_file_location("wave1_checker", CHECKER)
assert spec is not None and spec.loader is not None
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def baseline():
    return json.loads(LOCK.read_text(encoding="utf-8"))


class Wave1ContractLockTest(unittest.TestCase):
    def assert_fails(self, data, needle):
        errors = checker.verify_lock(data)
        self.assertTrue(any(needle in error for error in errors), errors)

    def test_baseline(self):
        self.assertEqual([], checker.verify_lock(baseline()))

    def test_parent_head_cannot_slide(self):
        data = baseline()
        data["parent"]["head_sha"] = "1" * 40
        self.assert_fails(data, "parent exact subject drift")

    def test_repository_tree_cannot_slide(self):
        data = baseline()
        data["repository_subjects"][0]["tree_sha"] = "2" * 40
        self.assert_fails(data, "repository/commit/tree drift")

    def test_blob_identity_cannot_slide(self):
        data = baseline()
        data["source_files"][0]["blob_sha"] = "3" * 40
        self.assert_fails(data, "exact file subject drift")

    def test_mutable_raw_url_is_rejected(self):
        data = baseline()
        data["source_files"][0]["raw_url"] = data["source_files"][0]["raw_url"].replace(data["source_files"][0]["commit_sha"], "main")
        self.assert_fails(data, "raw URL is not exact-commit bound")

    def test_required_symbol_set_cannot_disappear(self):
        data = baseline()
        data["source_files"][0]["required_symbols"] = []
        self.assert_fails(data, "required_symbols")

    def test_existing_github_transport_cannot_be_hidden_or_duplicated(self):
        data = baseline()
        lane = next(item for item in data["lanes"] if item["atom"] == "L2-GH")
        lane["existing"].remove("Ktor read-only public api.github.com transport")
        self.assert_fails(data, "existing REST transport")

    def test_absent_google_transport_cannot_be_reported_present(self):
        data = baseline()
        lane = next(item for item in data["lanes"] if item["atom"] == "L3-GOOGLE")
        lane["missing"].remove("Drive/Docs/Sheets live transport")
        self.assert_fails(data, "absent live transport")

    def test_missing_bettor_consumer_cannot_be_reported_ready(self):
        data = baseline()
        lane = next(item for item in data["lanes"] if item["atom"] == "L4-BETTOR")
        lane["start_receipt"]["external_ready"] = True
        lane["missing"].remove("Bettor capability-workspace consumer module")
        self.assert_fails(data, "external authority falsely marked ready")
        self.assert_fails(data, "absent consumer")

    def test_missing_domain_adapter_cannot_be_reported_live(self):
        data = baseline()
        lane = next(item for item in data["lanes"] if item["atom"] == "L5-DOMAIN")
        lane["start_receipt"]["live_evidence_ready"] = True
        lane["missing"].remove("KAW domain receipt-reference adapter")
        self.assert_fails(data, "live evidence falsely marked ready")
        self.assert_fails(data, "absent KAW adapter")

    def test_external_capability_boundary_cannot_be_deleted(self):
        data = baseline()
        data["lanes"][0]["external_capabilities"] = []
        self.assert_fails(data, "external capability boundary missing")

    def test_path_lease_collision_is_rejected(self):
        data = baseline()
        duplicate = data["lanes"][0]["first_safe_write"][0]
        data["lanes"][1]["first_safe_write"].append(duplicate)
        self.assert_fails(data, "path lease collision")

    def test_local_handoff_cannot_be_materialized_with_placeholders(self):
        data = baseline()
        data["local_handoff"] = {"state": "READY", "reason": "PLACEHOLDERS"}
        self.assert_fails(data, "Local Handoff must remain ABSENT")

    def test_fixture_cannot_be_promoted_to_live(self):
        data = baseline()
        data["hard_laws"]["fixture_is_live"] = True
        self.assert_fails(data, "hard_laws")

    def test_credential_like_material_is_rejected(self):
        data = baseline()
        data["lanes"][0]["existing"].append("ghp_" + "A" * 30)
        self.assert_fails(data, "credential-like")

    def test_void_connector_issues_remain_excluded(self):
        data = baseline()
        data["excluded_void_issues"] = [173]
        self.assert_fails(data, "connector-misfire issue exclusion drift")

    def test_maximum_claim_cannot_widen(self):
        data = baseline()
        data["lanes"][0]["maximum_claim"] = "LIVE_PROVIDER_PASS"
        self.assert_fails(data, "maximum claim widened")


if __name__ == "__main__":
    unittest.main()
