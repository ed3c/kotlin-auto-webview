from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
VERIFIER_PATH = ROOT / "scripts" / "evidence" / "workspace" / "verify_federation_evidence.py"
RECEIPT_PATH = ROOT / "receipts" / "workspace" / "federation-evidence.json"

spec = importlib.util.spec_from_file_location("workspace_evidence_verifier_pinning", VERIFIER_PATH)
assert spec is not None and spec.loader is not None
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def manifest():
    return json.loads(RECEIPT_PATH.read_text(encoding="utf-8"))


class ExactSubjectPinningTest(unittest.TestCase):
    def test_head_and_check_cannot_slide_together(self):
        data = manifest()
        item = next(x for x in data["implementation_subjects"] if x["atom"] == "W2")
        moved = "1" * 40
        item["head_sha"] = moved
        item["check_head_sha"] = moved
        result = verifier.verify_manifest(data)
        self.assertTrue(any("exact subject drift at head_sha" in error for error in result))

    def test_workflow_run_id_is_part_of_exact_subject(self):
        data = manifest()
        item = next(x for x in data["implementation_subjects"] if x["atom"] == "W3")
        item["workflow_run_id"] += 1
        result = verifier.verify_manifest(data)
        self.assertTrue(any("exact subject drift at workflow_run_id" in error for error in result))

    def test_simulator_ci_cannot_be_promoted_to_physical_device(self):
        data = manifest()
        lane = next(x for x in data["lanes"] if x["lane_id"] == "L6")
        lane["status"] = "PASS"
        lane["evidence_ids"] = ["EV-W5-UI"]
        result = verifier.verify_manifest(data)
        self.assertTrue(any("cannot satisfy PHYSICAL_DEVICE" in error for error in result))
        self.assertTrue(any("simulator/CI cannot satisfy physical-device" in error for error in result))


if __name__ == "__main__":
    unittest.main()
