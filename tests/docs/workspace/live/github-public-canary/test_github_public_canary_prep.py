from __future__ import annotations

import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]
CHECKER_PATH = ROOT / "scripts/ci/check-workspace-live-github-public-canary.py"
spec = importlib.util.spec_from_file_location("github_public_canary_checker", CHECKER_PATH)
checker = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(checker)

CONTRACT = json.loads(
    (ROOT / "docs/workspace/live/github-public-canary/canary-contract.json").read_text(encoding="utf-8")
)
SCHEMA = json.loads(
    (ROOT / "schemas/workspace/live-github-public-canary.schema.json").read_text(encoding="utf-8")
)


class GitHubPublicCanaryPrepTest(unittest.TestCase):
    def assert_rejected(self, mutate):
        candidate = copy.deepcopy(CONTRACT)
        mutate(candidate)
        with self.assertRaises(checker.ContractError):
            checker.validate_model(candidate, SCHEMA)

    def test_baseline(self):
        checker.validate_model(copy.deepcopy(CONTRACT), copy.deepcopy(SCHEMA))

    def test_parent_head_cannot_slide(self):
        self.assert_rejected(lambda c: c["parent"].__setitem__("head_sha", "0" * 40))

    def test_parent_tree_cannot_slide(self):
        self.assert_rejected(lambda c: c["parent"].__setitem__("tree_sha", "1" * 40))

    def test_implementation_git_parent_cannot_become_docs_parent(self):
        self.assert_rejected(lambda c: c["implementation_git_parent"].__setitem__("pull_request", 177))

    def test_existing_transport_commit_cannot_slide(self):
        self.assert_rejected(lambda c: c["transport_binding"]["source_files"][0].__setitem__("commit_sha", "2" * 40))

    def test_existing_transport_blob_cannot_slide(self):
        self.assert_rejected(lambda c: c["transport_binding"]["source_files"][0].__setitem__("blob_sha", "3" * 40))

    def test_required_symbol_cannot_disappear(self):
        self.assert_rejected(lambda c: c["transport_binding"]["source_files"][0].__setitem__("required_symbols", []))

    def test_duplicate_transport_cannot_be_admitted(self):
        self.assert_rejected(lambda c: c["transport_binding"].__setitem__("new_transport_allowed", True))

    def test_repository_identity_cannot_slide(self):
        self.assert_rejected(lambda c: c["canary_subject"]["repository"].__setitem__("id", 1))

    def test_issue_identity_cannot_slide(self):
        self.assert_rejected(lambda c: c["canary_subject"]["issue"].__setitem__("node_id", "I_wrong"))

    def test_pr_head_cannot_move(self):
        self.assert_rejected(lambda c: c["canary_subject"]["pull_request"].__setitem__("head_sha", "4" * 40))

    def test_commit_tree_cannot_move(self):
        self.assert_rejected(lambda c: c["canary_subject"]["commit"].__setitem__("tree_sha", "5" * 40))

    def test_workflow_run_cannot_slide(self):
        self.assert_rejected(lambda c: c["canary_subject"]["workflow_runs"][0].__setitem__("id", 1))

    def test_stale_check_cannot_be_promoted(self):
        self.assert_rejected(lambda c: c["canary_subject"]["check_jobs"][0].__setitem__("head_sha", "6" * 40))

    def test_non_get_method_is_rejected(self):
        self.assert_rejected(lambda c: c["request_contract"].__setitem__("allowed_methods", ["POST"]))

    def test_non_public_origin_is_rejected(self):
        self.assert_rejected(lambda c: c["request_contract"].__setitem__("origin", "https://example.com"))

    def test_token_provider_cannot_be_enabled(self):
        self.assert_rejected(lambda c: c["request_contract"].__setitem__("token_provider_must_not_be_called", False))

    def test_credential_material_is_rejected(self):
        self.assert_rejected(lambda c: c["start_receipt"]["reason_codes"].append("Bearer secret"))

    def test_private_locator_is_rejected(self):
        self.assert_rejected(lambda c: c["start_receipt"]["reason_codes"].append("private-repo/path"))

    def test_safe_write_cannot_escape_lease(self):
        self.assert_rejected(lambda c: c["implementation_packet"]["first_safe_write"].append("README.md"))

    def test_private_canary_cannot_be_ready(self):
        self.assert_rejected(lambda c: c["start_receipt"].__setitem__("private_canary_ready", True))

    def test_live_evidence_cannot_be_promoted(self):
        self.assert_rejected(lambda c: c["evidence_boundary"].__setitem__("l2_live_evidence", "PASS"))

    def test_local_handoff_cannot_be_materialized_early(self):
        self.assert_rejected(lambda c: c["local_handoff"].__setitem__("state", "READY"))

    def test_void_connector_issues_remain_excluded(self):
        self.assert_rejected(lambda c: c.__setitem__("excluded_issue_numbers", []))


if __name__ == "__main__":
    unittest.main()
