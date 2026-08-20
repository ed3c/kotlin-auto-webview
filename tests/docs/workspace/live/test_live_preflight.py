from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
CHECKER = ROOT / "scripts" / "ci" / "check-workspace-live-preflight.py"
MANIFEST = ROOT / "docs" / "workspace" / "live" / "implementation-preflight.json"

spec = importlib.util.spec_from_file_location("workspace_live_preflight", CHECKER)
assert spec is not None and spec.loader is not None
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def baseline():
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


class LivePreflightTest(unittest.TestCase):
    def assertRejected(self, data, text):
        self.assertTrue(any(text in error for error in checker.verify(data)), checker.verify(data))

    def test_baseline(self):
        self.assertEqual([], checker.verify(baseline()))

    def test_missing_owner_fails(self):
        data = baseline()
        data["atoms"][0]["issue"] = data["atoms"][1]["issue"]
        self.assertRejected(data, "duplicate issue owner")

    def test_false_git_parent_fails(self):
        data = baseline()
        next(x for x in data["atoms"] if x["atom"] == "L3-GOOGLE")["git_parent"]["pr"] = 163
        self.assertRejected(data, "false or missing Git parent")

    def test_fixture_cannot_be_promoted_to_live(self):
        data = baseline()
        data["required_lanes"]["L3"] = "PASS"
        self.assertRejected(data, "promoted or denominator changed")

    def test_device_cannot_be_marked_ready_without_external_authority(self):
        data = baseline()
        item = next(x for x in data["atoms"] if x["atom"] == "L6-DEVICE")
        item["state"] = "READY_FOR_IMPLEMENTATION_PREP"
        item["external_authorities"] = []
        self.assertRejected(data, "owner/lane/state drift")
        self.assertRejected(data, "external authority boundary absent")

    def test_user_outcome_cannot_start_without_live_vertical_slice(self):
        data = baseline()
        next(x for x in data["atoms"] if x["atom"] == "L7-USER")["completion_dependencies"] = []
        self.assertRejected(data, "L7 must remain blocked")

    def test_denominator_cannot_shrink(self):
        data = baseline()
        next(x for x in data["atoms"] if x["atom"] == "P1-EVIDENCE")["completion_dependencies"].remove(170)
        self.assertRejected(data, "denominator dependencies changed")

    def test_route_ack_cannot_become_execution(self):
        data = baseline()
        data["hard_laws"]["route_ack_is_execution"] = True
        self.assertRejected(data, "hard laws changed")

    def test_local_handoff_cannot_be_materialized_with_placeholders(self):
        data = baseline()
        data["local_handoff"]["state"] = "READY"
        self.assertRejected(data, "Local Handoff must remain absent")

    def test_path_collision_fails(self):
        data = baseline()
        gh = next(x for x in data["atoms"] if x["atom"] == "L2-GH")
        google = next(x for x in data["atoms"] if x["atom"] == "L3-GOOGLE")
        google["planned_paths"].append(gh["planned_paths"][0])
        self.assertRejected(data, "path lease collision")

    def test_void_connector_issues_remain_excluded(self):
        data = baseline()
        data["void_issues_excluded"] = []
        self.assertRejected(data, "misfire issues must remain explicitly excluded")

    def test_private_marker_in_public_preflight_fails(self):
        data = baseline()
        data["atoms"][0]["maximum_claim"] = "private-owner"
        self.assertRejected(data, "forbidden public text")


if __name__ == "__main__":
    unittest.main()
