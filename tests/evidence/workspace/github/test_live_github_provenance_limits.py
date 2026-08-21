from __future__ import annotations

import json
import unittest
from copy import deepcopy
from pathlib import Path

HERE = Path(__file__).resolve().parent
LIMITS_PATH = HERE.parents[3] / "receipts/workspace/live/github/provenance-limits.json"

with LIMITS_PATH.open("r", encoding="utf-8") as handle:
    BASELINE = json.load(handle)


class LiveGitHubProvenanceLimitsTest(unittest.TestCase):
    def validate(self, value: dict) -> None:
        self.assertEqual(
            {
                "schema", "runtime_validated", "prep_bound_only",
                "forbidden_promotions", "maximum_claim",
            },
            set(value),
        )
        self.assertEqual("kaw.workspace.live-github-provenance-limits.v1", value["schema"])
        self.assertEqual(
            "PUBLIC_EXACT_SUBJECT_READ_AND_LOCAL_PROJECTION",
            value["maximum_claim"],
        )
        runtime = value["runtime_validated"]
        self.assertTrue(runtime)
        self.assertTrue(all(flag is True for flag in runtime.values()))
        prep = value["prep_bound_only"]
        self.assertEqual("W2_MODEL_ABSENT", prep["repository_graphql_node_id"])
        self.assertEqual("W2_MODEL_ABSENT", prep["issue_graphql_node_id"])
        self.assertEqual("W2_MODEL_ABSENT", prep["pull_request_graphql_node_id"])
        self.assertEqual(
            "W2_CHECK_MODEL_DOES_NOT_RETAIN_WORKFLOW_RUN_ID",
            prep["source_workflow_run_ids"],
        )
        promotions = set(value["forbidden_promotions"])
        self.assertEqual(
            {
                "PREP_BOUND_NODE_ID_TO_RUNTIME_PASS",
                "PREP_BOUND_WORKFLOW_RUN_ID_TO_RUNTIME_PASS",
                "CHECK_SUCCESS_TO_USER_OUTCOME",
                "PUBLIC_ACCESS_TO_PRIVATE_SCOPE",
                "READ_ACCESS_TO_MUTATION_AUTHORITY",
            },
            promotions,
        )

    def assert_rejected(self, mutate) -> None:
        value = deepcopy(BASELINE)
        mutate(value)
        with self.assertRaises(AssertionError):
            self.validate(value)

    def test_baseline(self):
        self.validate(BASELINE)

    def test_node_id_cannot_be_promoted(self):
        self.assert_rejected(
            lambda value: value["prep_bound_only"].__setitem__(
                "repository_graphql_node_id", "PASS",
            ),
        )

    def test_workflow_run_id_cannot_be_promoted(self):
        self.assert_rejected(
            lambda value: value["prep_bound_only"].__setitem__(
                "source_workflow_run_ids", "PASS",
            ),
        )

    def test_runtime_check_id_cannot_be_demoted(self):
        self.assert_rejected(
            lambda value: value["runtime_validated"].__setitem__(
                "check_run_id", False,
            ),
        )

    def test_required_forbidden_promotion_cannot_disappear(self):
        self.assert_rejected(
            lambda value: value["forbidden_promotions"].remove(
                "PREP_BOUND_WORKFLOW_RUN_ID_TO_RUNTIME_PASS",
            ),
        )

    def test_maximum_claim_cannot_widen(self):
        self.assert_rejected(
            lambda value: value.__setitem__("maximum_claim", "LIVE_ALL_GITHUB"),
        )


if __name__ == "__main__":
    unittest.main()
