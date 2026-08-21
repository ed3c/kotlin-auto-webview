from __future__ import annotations

import importlib.util
import json
import unittest
from copy import deepcopy
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[3]
VERIFIER_PATH = REPO_ROOT / "scripts/evidence/workspace/github/verify_live_github_receipt.py"
SUBJECT_PATH = HERE / "public-canary-subject.json"

spec = importlib.util.spec_from_file_location("verify_live_github_receipt", VERIFIER_PATH)
assert spec and spec.loader
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)

with SUBJECT_PATH.open("r", encoding="utf-8") as handle:
    SUBJECT = json.load(handle)

EXPECTED_CHECK_IDS = sorted(check["id"] for check in SUBJECT["expected_checks"])
EXPECTED_CHECK_NAMES = [check["name"] for check in sorted(SUBJECT["expected_checks"], key=lambda check: check["id"])]
EXPECTED_RUN_IDS = sorted({check["run_id"] for check in SUBJECT["expected_checks"]})


def baseline_receipt() -> dict:
    return {
        "schema": "kaw.workspace.live-github-receipt.v1",
        "lane": "L2_LIVE_GITHUB_CONNECTOR",
        "status": "PASS",
        "maximum_claim": "PUBLIC_EXACT_SUBJECT_READ_AND_LOCAL_PROJECTION",
        "transport": {
            "class": "GitHubRestMetadataSource",
            "source_commit": SUBJECT["transport"]["source_commit"],
            "source_blob": SUBJECT["transport"]["source_blob"],
            "credential_mode": "NONE",
            "credential_provider_bound": False,
        },
        "subject": {
            "repository_full_name": SUBJECT["repository"]["full_name"],
            "repository_id": SUBJECT["repository"]["id"],
            "repository_node_id": SUBJECT["repository"]["node_id"],
            "issue_number": SUBJECT["issue"]["number"],
            "issue_id": SUBJECT["issue"]["id"],
            "issue_node_id": SUBJECT["issue"]["node_id"],
            "pull_request_number": SUBJECT["pull_request"]["number"],
            "pull_request_id": SUBJECT["pull_request"]["id"],
            "pull_request_node_id": SUBJECT["pull_request"]["node_id"],
            "head_sha": SUBJECT["pull_request"]["head_sha"],
            "tree_sha": SUBJECT["commit"]["tree_sha"],
            "runtime_node_id_validation": "PREP_BINDING_ONLY_W2_MODEL_ABSENT",
        },
        "execution": {
            "implementation_head_sha": "1" * 40,
            "workflow_run_id": 1,
            "workflow_run_attempt": 1,
            "runner_os": "Linux",
            "started_at": "2026-08-21T00:00:00Z",
            "ended_at": "2026-08-21T00:00:01Z",
            "observation_sequence": 1,
        },
        "result": {
            "subjects_applied": 12,
            "edges_applied": 20,
            "active_subjects_after_reopen": 12,
            "w1_subject_read_back": True,
            "w1_edge_read_back": True,
            "all_returned_checks_exact_head": True,
            "exact_successful_check_ids": EXPECTED_CHECK_IDS,
            "exact_successful_check_names": EXPECTED_CHECK_NAMES,
            "source_workflow_run_ids": EXPECTED_RUN_IDS,
        },
        "disclosure": {
            "authorization_header_persisted": False,
            "token_persisted": False,
            "cookie_persisted": False,
            "email_persisted": False,
            "response_body_persisted": False,
            "private_locator_persisted": False,
        },
        "cleanup": {
            "temporary_database_removed": True,
            "credential_cleanup_required": False,
            "temporary_response_files_persisted": False,
        },
        "evidence_boundary": {
            "public_repository": "PASS",
            "private_repository": "NOT_EXERCISED",
            "github_mutation": "NOT_IMPLEMENTED",
            "merge_release": "NOT_AUTHORIZED",
            "l3_to_l6": "NOT_EXERCISED",
            "l7_user_outcome": "ABSENT",
        },
    }


class LiveGitHubReceiptTest(unittest.TestCase):
    def assert_rejected(self, mutate):
        receipt = deepcopy(baseline_receipt())
        mutate(receipt)
        with self.assertRaises(verifier.ReceiptValidationError):
            verifier.validate_receipt(receipt, SUBJECT, expected_implementation_head="1" * 40)

    def test_baseline(self):
        digest = verifier.validate_receipt(baseline_receipt(), SUBJECT, expected_implementation_head="1" * 40)
        self.assertEqual(64, len(digest))

    def test_extra_top_level_key_is_rejected(self):
        self.assert_rejected(lambda r: r.__setitem__("response_body", "hidden"))

    def test_transport_class_drift_is_rejected(self):
        self.assert_rejected(lambda r: r["transport"].__setitem__("class", "DuplicateClient"))

    def test_transport_commit_drift_is_rejected(self):
        self.assert_rejected(lambda r: r["transport"].__setitem__("source_commit", "2" * 40))

    def test_transport_blob_drift_is_rejected(self):
        self.assert_rejected(lambda r: r["transport"].__setitem__("source_blob", "2" * 40))

    def test_credential_mode_is_rejected(self):
        self.assert_rejected(lambda r: r["transport"].__setitem__("credential_mode", "TOKEN"))

    def test_bound_credential_provider_is_rejected(self):
        self.assert_rejected(lambda r: r["transport"].__setitem__("credential_provider_bound", True))

    def test_repository_identity_drift_is_rejected(self):
        self.assert_rejected(lambda r: r["subject"].__setitem__("repository_id", 1))

    def test_issue_identity_drift_is_rejected(self):
        self.assert_rejected(lambda r: r["subject"].__setitem__("issue_id", 1))

    def test_pr_identity_drift_is_rejected(self):
        self.assert_rejected(lambda r: r["subject"].__setitem__("pull_request_id", 1))

    def test_pr_head_drift_is_rejected(self):
        self.assert_rejected(lambda r: r["subject"].__setitem__("head_sha", "2" * 40))

    def test_tree_drift_is_rejected(self):
        self.assert_rejected(lambda r: r["subject"].__setitem__("tree_sha", "2" * 40))

    def test_runtime_node_claim_cannot_widen(self):
        self.assert_rejected(lambda r: r["subject"].__setitem__("runtime_node_id_validation", "PASS"))

    def test_implementation_head_must_match_expected(self):
        self.assert_rejected(lambda r: r["execution"].__setitem__("implementation_head_sha", "2" * 40))

    def test_workflow_run_id_must_be_positive(self):
        self.assert_rejected(lambda r: r["execution"].__setitem__("workflow_run_id", 0))

    def test_end_time_cannot_precede_start(self):
        self.assert_rejected(lambda r: r["execution"].__setitem__("ended_at", "2025-01-01T00:00:00Z"))

    def test_check_denominator_cannot_shrink(self):
        self.assert_rejected(lambda r: r["result"].__setitem__("exact_successful_check_ids", EXPECTED_CHECK_IDS[:-1]))

    def test_check_names_cannot_drift(self):
        self.assert_rejected(lambda r: r["result"]["exact_successful_check_names"].__setitem__(0, "other"))

    def test_workflow_run_denominator_cannot_shrink(self):
        self.assert_rejected(lambda r: r["result"].__setitem__("source_workflow_run_ids", EXPECTED_RUN_IDS[:-1]))

    def test_stale_check_promotion_is_rejected(self):
        self.assert_rejected(lambda r: r["result"].__setitem__("all_returned_checks_exact_head", False))

    def test_w1_subject_readback_is_required(self):
        self.assert_rejected(lambda r: r["result"].__setitem__("w1_subject_read_back", False))

    def test_w1_edge_readback_is_required(self):
        self.assert_rejected(lambda r: r["result"].__setitem__("w1_edge_read_back", False))

    def test_disclosure_flag_is_rejected(self):
        self.assert_rejected(lambda r: r["disclosure"].__setitem__("token_persisted", True))

    def test_credential_string_is_rejected(self):
        self.assert_rejected(lambda r: r["execution"].__setitem__("runner_os", "Bearer ghp_example"))

    def test_email_is_rejected(self):
        self.assert_rejected(lambda r: r["execution"].__setitem__("runner_os", "dev@example.com"))

    def test_cleanup_residue_is_rejected(self):
        self.assert_rejected(lambda r: r["cleanup"].__setitem__("temporary_database_removed", False))

    def test_private_scope_promotion_is_rejected(self):
        self.assert_rejected(lambda r: r["evidence_boundary"].__setitem__("private_repository", "PASS"))

    def test_mutation_authority_promotion_is_rejected(self):
        self.assert_rejected(lambda r: r["evidence_boundary"].__setitem__("github_mutation", "PASS"))

    def test_user_outcome_promotion_is_rejected(self):
        self.assert_rejected(lambda r: r["evidence_boundary"].__setitem__("l7_user_outcome", "PASS"))


if __name__ == "__main__":
    unittest.main()
