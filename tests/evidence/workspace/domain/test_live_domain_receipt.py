from __future__ import annotations

from copy import deepcopy
import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
VERIFIER = ROOT / "scripts/evidence/workspace/domain/verify_live_domain_receipt.py"
spec = importlib.util.spec_from_file_location("verify_live_domain_receipt", VERIFIER)
assert spec and spec.loader
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)

HEAD = "1" * 40


def baseline() -> dict:
    return {
        "schema": "kaw.workspace.live-domain-receipt.v1",
        "lane": "L5_LIVE_DOMAIN_AUTHORITY_RECEIPT",
        "status": "PASS",
        "maximum_claim": "EXACT_PUBLIC_DOMAIN_RECEIPT_VALIDATION",
        "producer": {
            "repository_full_name": "ed3c/truth-verify-loop",
            "repository_id": 1,
            "pull_request_number": 48,
            "commit_sha": "2" * 40,
            "tree_sha": "3" * 40,
            "receipt_path": "receipts/kaw/public-claim-canary.json",
            "receipt_blob_sha": "4" * 40,
            "receipt_content_sha256": "5" * 64,
            "workflow_runs": [
                {"name": "KAW Domain Receipt", "run_id": 10, "conclusion": "success"},
                {"name": "verify", "run_id": 11, "conclusion": "success"},
            ],
        },
        "execution": {
            "implementation_head_sha": HEAD,
            "workflow_run_id": 12,
            "workflow_run_attempt": 1,
            "runner_os": "Linux",
            "started_at": "2026-08-21T00:00:00Z",
            "ended_at": "2026-08-21T00:00:01Z",
        },
        "subject": {
            "claim_id": "synthetic-sdk-release",
            "claim_digest": "49baf11ae87da437f87de2380672fb2d7b92810b2b2ad948f278637433b06c21",
            "receipt_id": "TVL-KAW-PUBLIC-SYNTHETIC-1",
        },
        "authority": {
            "owner": "truth-verify-loop",
            "environment": "PUBLIC_SYNTHETIC_CI",
            "verdict_state": "SUPPORTED",
            "closed": True,
            "closure_digest": "6" * 64,
            "source_freshness": "CURRENT",
            "evidence_ceiling": "DOMAIN_VERDICT",
        },
        "validation": {
            "exact_repository": True,
            "exact_commit": True,
            "exact_tree": True,
            "exact_blob": True,
            "exact_content_digest": True,
            "producer_workflows_exact_head": True,
            "verdict_preserved": True,
            "raw_source_imported": False,
            "raw_evidence_imported": False,
        },
        "disclosure": {
            "credential_persisted": False,
            "authorization_header_persisted": False,
            "cookie_persisted": False,
            "email_persisted": False,
            "internal_reasoning_persisted": False,
            "private_locator_persisted": False,
            "raw_source_persisted": False,
            "raw_evidence_persisted": False,
        },
        "evidence_boundary": {
            "l2_public_github": "PASS_SEPARATE_RECEIPT",
            "l3_google": "NOT_EXERCISED",
            "l4_bettor": "NOT_EXERCISED",
            "l5_domain_authority": "PASS",
            "l6_physical_device": "NOT_EXERCISED",
            "l7_user_outcome": "ABSENT",
            "paid_outcome": "ABSENT",
            "merge_release": "NOT_AUTHORIZED",
        },
    }


class LiveDomainReceiptTest(unittest.TestCase):
    def reject(self, mutate) -> None:
        candidate = deepcopy(baseline())
        mutate(candidate)
        with self.assertRaises(validator.DomainReceiptValidationError):
            validator.validate_receipt(candidate, expected_implementation_head=HEAD)

    def test_baseline(self):
        digest = validator.validate_receipt(baseline(), expected_implementation_head=HEAD)
        self.assertEqual(64, len(digest))

    def test_unknown_top_level_field_is_rejected(self):
        self.reject(lambda value: value.__setitem__("raw_receipt", "hidden"))

    def test_schema_lane_status_and_maximum_claim_cannot_change(self):
        self.reject(lambda value: value.__setitem__("schema", "other"))
        self.reject(lambda value: value.__setitem__("lane", "L4"))
        self.reject(lambda value: value.__setitem__("status", "PARTIAL"))
        self.reject(lambda value: value.__setitem__("maximum_claim", "ALL_DOMAIN_TRUTH"))

    def test_producer_repository_and_pull_request_are_exact(self):
        self.reject(lambda value: value["producer"].__setitem__("repository_full_name", "other/repo"))
        self.reject(lambda value: value["producer"].__setitem__("pull_request_number", 0))

    def test_commit_tree_blob_and_content_digest_are_exact(self):
        self.reject(lambda value: value["producer"].__setitem__("commit_sha", "main"))
        self.reject(lambda value: value["producer"].__setitem__("tree_sha", "x" * 40))
        self.reject(lambda value: value["producer"].__setitem__("receipt_blob_sha", "x" * 40))
        self.reject(lambda value: value["producer"].__setitem__("receipt_content_sha256", "x" * 64))

    def test_receipt_path_cannot_drift(self):
        self.reject(lambda value: value["producer"].__setitem__("receipt_path", "other.json"))

    def test_producer_workflow_denominator_cannot_shrink_or_duplicate(self):
        self.reject(lambda value: value["producer"].__setitem__("workflow_runs", value["producer"]["workflow_runs"][:1]))
        self.reject(lambda value: value["producer"]["workflow_runs"][1].__setitem__("name", "KAW Domain Receipt"))
        self.reject(lambda value: value["producer"]["workflow_runs"][1].__setitem__("run_id", 10))
        self.reject(lambda value: value["producer"]["workflow_runs"][0].__setitem__("conclusion", "failure"))

    def test_execution_head_and_run_identity_are_required(self):
        self.reject(lambda value: value["execution"].__setitem__("implementation_head_sha", "2" * 40))
        self.reject(lambda value: value["execution"].__setitem__("workflow_run_id", 0))
        self.reject(lambda value: value["execution"].__setitem__("workflow_run_attempt", 0))
        self.reject(lambda value: value["execution"].__setitem__("ended_at", "2025-01-01T00:00:00Z"))

    def test_claim_and_receipt_identity_cannot_change(self):
        self.reject(lambda value: value["subject"].__setitem__("claim_id", "other"))
        self.reject(lambda value: value["subject"].__setitem__("claim_digest", "0" * 64))
        self.reject(lambda value: value["subject"].__setitem__("receipt_id", "other"))

    def test_authority_environment_and_evidence_ceiling_cannot_widen(self):
        self.reject(lambda value: value["authority"].__setitem__("owner", "kotlin-auto-webview"))
        self.reject(lambda value: value["authority"].__setitem__("environment", "PRODUCTION"))
        self.reject(lambda value: value["authority"].__setitem__("verdict_state", "UNKNOWN"))
        self.reject(lambda value: value["authority"].__setitem__("closure_digest", "x" * 64))
        self.reject(lambda value: value["authority"].__setitem__("evidence_ceiling", "USER_OUTCOME"))

    def test_exact_validation_and_verdict_preservation_are_required(self):
        for key in (
            "exact_repository", "exact_commit", "exact_tree", "exact_blob",
            "exact_content_digest", "producer_workflows_exact_head", "verdict_preserved",
        ):
            self.reject(lambda value, key=key: value["validation"].__setitem__(key, False))
        self.reject(lambda value: value["validation"].__setitem__("raw_source_imported", True))
        self.reject(lambda value: value["validation"].__setitem__("raw_evidence_imported", True))

    def test_disclosure_flags_cannot_be_enabled(self):
        for key in baseline()["disclosure"]:
            self.reject(lambda value, key=key: value["disclosure"].__setitem__(key, True))

    def test_other_lanes_user_paid_and_merge_cannot_be_promoted(self):
        self.reject(lambda value: value["evidence_boundary"].__setitem__("l3_google", "PASS"))
        self.reject(lambda value: value["evidence_boundary"].__setitem__("l4_bettor", "PASS"))
        self.reject(lambda value: value["evidence_boundary"].__setitem__("l6_physical_device", "PASS"))
        self.reject(lambda value: value["evidence_boundary"].__setitem__("l7_user_outcome", "PASS"))
        self.reject(lambda value: value["evidence_boundary"].__setitem__("paid_outcome", "PASS"))
        self.reject(lambda value: value["evidence_boundary"].__setitem__("merge_release", "PASS"))

    def test_secret_email_and_private_locator_are_rejected(self):
        self.reject(lambda value: value["execution"].__setitem__("runner_os", "Bearer ghp_example"))
        self.reject(lambda value: value["execution"].__setitem__("runner_os", "dev@example.com"))
        self.reject(lambda value: value["execution"].__setitem__("runner_os", "private.github"))


if __name__ == "__main__":
    unittest.main()
