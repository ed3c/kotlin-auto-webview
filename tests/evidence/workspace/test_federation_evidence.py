from __future__ import annotations

import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
VERIFIER_PATH = ROOT / "scripts" / "evidence" / "workspace" / "verify_federation_evidence.py"
RECEIPT_PATH = ROOT / "receipts" / "workspace" / "federation-evidence.json"

spec = importlib.util.spec_from_file_location("workspace_evidence_verifier", VERIFIER_PATH)
assert spec is not None and spec.loader is not None
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def manifest():
    return json.loads(RECEIPT_PATH.read_text(encoding="utf-8"))


def errors(data):
    return verifier.verify_manifest(data)


class FederationEvidenceTest(unittest.TestCase):
    def test_baseline_passes(self):
        self.assertEqual([], errors(manifest()))

    def test_url_or_title_cannot_become_identity(self):
        data = manifest()
        data["hard_laws"]["url_or_title_is_stable_identity"] = True
        self.assertTrue(errors(data))

    def test_old_check_cannot_evidence_moved_head(self):
        data = manifest()
        item = next(x for x in data["implementation_subjects"] if x["atom"] == "W2")
        item["check_head_sha"] = "0" * 40
        self.assertTrue(any("stale check head" in error for error in errors(data)))

    def test_google_write_ack_without_read_back_fails(self):
        data = manifest()
        item = next(x for x in data["implementation_subjects"] if x["atom"] == "W3")
        item["read_back_verified"] = False
        self.assertTrue(any("without read-back" in error for error in errors(data)))

    def test_google_manual_edit_cannot_mutate_canonical(self):
        data = manifest()
        item = next(x for x in data["implementation_subjects"] if x["atom"] == "W3")
        item["manual_edit_can_change_canonical"] = True
        self.assertTrue(any("cannot mutate canonical" in error for error in errors(data)))

    def test_private_identifier_in_public_receipt_fails(self):
        data = manifest()
        item = next(x for x in data["implementation_subjects"] if x["atom"] == "W5")
        item["public_receipt"]["private_repo_url"] = "https://github.com/private-owner/private-repo"
        result = errors(data)
        self.assertTrue(any("forbidden key" in error or "private locator" in error for error in result))

    def test_route_request_cannot_be_promoted_to_execution(self):
        data = manifest()
        item = next(x for x in data["implementation_subjects"] if x["atom"] == "W4")
        item["route_request_grants_execution"] = True
        self.assertTrue(any("cannot grant execution" in error for error in errors(data)))

    def test_receipt_cannot_be_reused_for_another_authority(self):
        data = manifest()
        item = next(x for x in data["implementation_subjects"] if x["atom"] == "W4")
        item["receipt_authority"] = "other-authority"
        self.assertTrue(any("receipt authority mismatch" in error for error in errors(data)))

    def test_fixture_cannot_be_reported_as_live_github_connector(self):
        data = manifest()
        lane = next(x for x in data["lanes"] if x["lane_id"] == "L2")
        lane["status"] = "PASS"
        lane["evidence_ids"] = ["EV-W2-GITHUB-MAPPER"]
        result = errors(data)
        self.assertTrue(any("cannot satisfy LIVE_GITHUB_CONNECTOR" in error for error in result))
        self.assertTrue(any("live_provider=true" in error for error in result))

    def test_fixture_cannot_be_reported_as_live_google_account(self):
        data = manifest()
        lane = next(x for x in data["lanes"] if x["lane_id"] == "L3")
        lane["status"] = "PASS"
        lane["evidence_ids"] = ["EV-W3-GOOGLE-SAGA"]
        self.assertTrue(any("cannot satisfy LIVE_GOOGLE_ACCOUNT" in error for error in errors(data)))

    def test_ci_cannot_be_promoted_to_user_outcome(self):
        data = manifest()
        lane = next(x for x in data["lanes"] if x["lane_id"] == "L7")
        lane["status"] = "PASS"
        lane["evidence_ids"] = ["EV-W5-UI"]
        result = errors(data)
        self.assertTrue(any("cannot satisfy USER_OUTCOME" in error for error in result))
        self.assertTrue(any("cannot satisfy user outcome" in error for error in result))

    def test_lane_denominator_cannot_shrink(self):
        data = manifest()
        data["lanes"] = [lane for lane in data["lanes"] if lane["lane_id"] != "L6"]
        self.assertTrue(any("denominator" in error for error in errors(data)))

    def test_absent_lane_cannot_carry_hidden_evidence(self):
        data = manifest()
        lane = next(x for x in data["lanes"] if x["lane_id"] == "L7")
        lane["evidence_ids"] = ["EV-W5-UI"]
        self.assertTrue(any("ABSENT lane cannot carry evidence" in error for error in errors(data)))


if __name__ == "__main__":
    unittest.main()
