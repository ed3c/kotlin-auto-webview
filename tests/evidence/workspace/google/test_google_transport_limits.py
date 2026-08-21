from __future__ import annotations

import importlib.util
import json
import unittest
from copy import deepcopy
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
VERIFIER = ROOT / "scripts/evidence/workspace/google/verify_google_transport_limits.py"
LIMITS = ROOT / "receipts/workspace/live/google/transport-limits.json"

spec = importlib.util.spec_from_file_location("verify_google_transport_limits", VERIFIER)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

with LIMITS.open("r", encoding="utf-8") as handle:
    BASELINE = json.load(handle)


class GoogleTransportLimitsTest(unittest.TestCase):
    def assert_rejected(self, mutate):
        candidate = deepcopy(BASELINE)
        mutate(candidate)
        with self.assertRaises(module.TransportLimitError):
            module.validate(candidate)

    def test_baseline(self):
        module.validate(deepcopy(BASELINE))

    def test_live_account_cannot_be_promoted(self):
        self.assert_rejected(lambda x: x["evidence"].__setitem__("live_google_account", "PASS"))

    def test_live_docs_file_cannot_be_promoted(self):
        self.assert_rejected(lambda x: x["evidence"].__setitem__("live_docs_file", "PASS"))

    def test_sheets_race_cannot_be_promoted(self):
        self.assert_rejected(lambda x: x["implementation"].__setitem__("sheets_write_state", "PASS"))

    def test_docs_revision_precondition_is_required(self):
        self.assert_rejected(lambda x: x["implementation"].__setitem__("docs_revision_precondition", "read_then_write"))

    def test_second_pre_write_read_is_required(self):
        self.assert_rejected(lambda x: x["implementation"].__setitem__("second_pre_write_read", False))

    def test_authenticated_readback_is_required(self):
        self.assert_rejected(lambda x: x["implementation"].__setitem__("authenticated_read_back_required_by_saga", False))

    def test_foreign_target_overwrite_is_forbidden(self):
        self.assert_rejected(lambda x: x["implementation"].__setitem__("foreign_target_overwrite", "ALLOWED"))

    def test_oauth_cannot_move_into_webview(self):
        self.assert_rejected(lambda x: x["implementation"].__setitem__("oauth_ui_location", "WEBVIEW"))

    def test_api_origin_cannot_widen(self):
        self.assert_rejected(lambda x: x["implementation"].__setitem__("api_origin", "https://example.com"))

    def test_scope_set_cannot_widen(self):
        self.assert_rejected(lambda x: x["allowed_scopes"].append("https://www.googleapis.com/auth/cloud-platform"))

    def test_w3_parent_cannot_slide(self):
        self.assert_rejected(lambda x: x["git_parent"].__setitem__("head_sha", "1" * 40))

    def test_w3_contract_blob_cannot_slide(self):
        self.assert_rejected(lambda x: x["w3_contracts"][0].__setitem__("blob_sha", "1" * 40))

    def test_w3_contract_cannot_be_modified(self):
        self.assert_rejected(lambda x: x["w3_contracts"][1].__setitem__("modified", True))

    def test_maximum_claim_cannot_widen(self):
        self.assert_rejected(lambda x: x["evidence"].__setitem__("maximum_claim", "LIVE_GOOGLE_PROJECTION_ACCOUNT_PASS"))

    def test_external_content_rights_authority_is_required(self):
        self.assert_rejected(lambda x: x["evidence"].__setitem__("content_rights", "PASS"))

    def test_secret_flags_cannot_be_enabled(self):
        self.assert_rejected(lambda x: x["secrets"].__setitem__("access_token_in_source", True))

    def test_forbidden_claim_denominator_cannot_shrink(self):
        self.assert_rejected(lambda x: x.__setitem__("forbidden_claims", x["forbidden_claims"][:-1]))

    def test_unknown_top_level_field_is_rejected(self):
        self.assert_rejected(lambda x: x.__setitem__("account_email", "hidden@example.com"))


if __name__ == "__main__":
    unittest.main()
