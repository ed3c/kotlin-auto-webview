import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
VALIDATOR = ROOT / "validate_admission.py"
EXPECTED_COMMIT = "0e9e5898f0e0dcc679d99e5f4518e19310e96775"
EXPECTED_TREE = "4c9d1d5f644fc69d9a0a5e658b51d1753fd2ac32"


class AdmissionValidatorTest(unittest.TestCase):
    def run_validator(self, root: Path):
        validator = root / "validate_admission.py"
        return subprocess.run(
            [sys.executable, str(validator), "--expected-commit", EXPECTED_COMMIT, "--expected-tree", EXPECTED_TREE],
            cwd=root,
            capture_output=True,
            text=True,
            check=False,
        )

    def fixture(self):
        tmp = tempfile.TemporaryDirectory()
        root = Path(tmp.name)
        for name in ("validate_admission.py", "upstream.lock.json", "capability-map.yaml", "policy-profile-matrix.yaml", "source-ledger.json"):
            (root / name).write_text((ROOT / name).read_text())
        return tmp, root

    def assert_reason(self, result, reason):
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)["reason"], reason)

    def test_baseline_passes_static_admission_gate(self):
        result = self.run_validator(ROOT)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual(payload["state"], "PASS")
        self.assertEqual(payload["evidence_ceiling"], "STATIC_SOURCE_ADMISSION_ONLY")
        self.assertEqual(payload["local_handoff_execution"], "NOT_CLAIMED")

    def test_stale_commit_is_rejected(self):
        tmp, root = self.fixture()
        try:
            path = root / "upstream.lock.json"
            data = json.loads(path.read_text())
            data["commit"] = "0" * 40
            path.write_text(json.dumps(data))
            self.assert_reason(self.run_validator(root), "STALE_SOURCE_PIN")
        finally:
            tmp.cleanup()

    def test_missing_capability_is_rejected(self):
        tmp, root = self.fixture()
        try:
            path = root / "capability-map.yaml"
            text = path.read_text()
            start = text.index("  - id: direct-mcp-execution\n")
            end = text.index("  - id: privileged-shell-root-terminal\n")
            path.write_text(text[:start] + text[end:])
            self.assert_reason(self.run_validator(root), "CAPABILITY_COMPLETENESS")
        finally:
            tmp.cleanup()

    def test_forbidden_direct_mcp_admission_is_rejected(self):
        tmp, root = self.fixture()
        try:
            path = root / "capability-map.yaml"
            text = path.read_text().replace(
                "  - id: direct-mcp-execution\n    source: README.md\n    blob: e3fec9627be574d7b2e2767718ce0a2c99c0a346\n    decision: DENIED_BY_ARCHITECTURE",
                "  - id: direct-mcp-execution\n    source: README.md\n    blob: e3fec9627be574d7b2e2767718ce0a2c99c0a346\n    decision: ADOPT_AS_CONTRACT",
            )
            path.write_text(text)
            self.assert_reason(self.run_validator(root), "FORBIDDEN_CAPABILITY_ADMITTED")
        finally:
            tmp.cleanup()

    def test_play_safe_accessibility_widening_is_rejected(self):
        tmp, root = self.fixture()
        try:
            path = root / "policy-profile-matrix.yaml"
            path.write_text(path.read_text().replace("      accessibility_service: false", "      accessibility_service: true"))
            self.assert_reason(self.run_validator(root), "PLAY_SAFE_WIDENED")
        finally:
            tmp.cleanup()

    def test_provenance_copy_without_review_is_rejected(self):
        tmp, root = self.fixture()
        try:
            path = root / "source-ledger.json"
            data = json.loads(path.read_text())
            data["copied_source"] = [{"path": "unreviewed.kt"}]
            path.write_text(json.dumps(data))
            self.assert_reason(self.run_validator(root), "PROVENANCE_REVIEW_REQUIRED")
        finally:
            tmp.cleanup()

    def test_source_blob_disagreement_is_rejected(self):
        tmp, root = self.fixture()
        try:
            path = root / "capability-map.yaml"
            path.write_text(path.read_text().replace("60287ce616911f4b59de1ecb523d27a8bdecae8a", "1" * 40, 1))
            self.assert_reason(self.run_validator(root), "STALE_SOURCE_PIN")
        finally:
            tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
