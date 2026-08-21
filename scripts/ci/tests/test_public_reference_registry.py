import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "check_public_reference_registry.py"
SPEC = importlib.util.spec_from_file_location("registry_checker", MODULE_PATH)
checker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = checker
SPEC.loader.exec_module(checker)


def write_registry(root: Path, references, extras=None):
    payload = {"schema": "reference-index.public@1", "references": references}
    if extras:
        payload.update(extras)
    (root / "reference-index.public.json").write_text(json.dumps(payload), encoding="utf-8")


class PublicReferenceRegistryTest(unittest.TestCase):
    def run_case(self, references, extras=None, private_ids=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_registry(root, references, extras)
            snapshot = None
            if private_ids is not None:
                snapshot = root / "private.json"
                snapshot.write_text(json.dumps({"ref_ids": private_ids}), encoding="utf-8")
            return checker.validate_registry(root, snapshot)

    def base_ref(self, **updates):
        row = {
            "id": "REF-0101",
            "title": "Android WebView",
            "url": "https://developer.android.com/reference/android/webkit/WebView",
            "visibility": "PUBLIC",
            "freshness": "URL_INDEXED",
        }
        row.update(updates)
        return row

    def private_opaque_ref(self, **updates):
        row = {
            "id": "REF-1001",
            "title": "Private architecture source",
            "visibility": "PRIVATE_OPAQUE",
            "freshness": "URL_INDEXED",
            "role": "PRIVATE_SOURCE_REFERENCE",
        }
        row.update(updates)
        return row

    def test_valid_public_url_only_registry_passes(self):
        result = self.run_case([self.base_ref()])
        self.assertTrue(result.ok)
        self.assertEqual("NOT_EXERCISED", result.private_parity)

    def test_private_opaque_placeholder_without_locator_passes_public_only(self):
        result = self.run_case([self.private_opaque_ref()])
        self.assertTrue(result.ok)
        self.assertEqual({"REF-1001"}, result.opaque_private_refs)
        self.assertEqual("NOT_EXERCISED", result.private_parity)

    def test_private_opaque_placeholder_with_locator_fails(self):
        result = self.run_case(
            [self.private_opaque_ref(url="https://docs.google.com/document/d/private/edit")]
        )
        self.assertFalse(result.ok)

    def test_private_opaque_placeholder_with_revision_or_digest_fails(self):
        result = self.run_case([self.private_opaque_ref(revision="secret-revision")])
        self.assertFalse(result.ok)
        result = self.run_case([self.private_opaque_ref(digest="a" * 64)])
        self.assertFalse(result.ok)

    def test_duplicate_ref_id_fails(self):
        result = self.run_case([self.base_ref(), self.base_ref(url="https://example.com/other")])
        self.assertFalse(result.ok)

    def test_duplicate_url_identity_fails(self):
        result = self.run_case(
            [
                self.base_ref(),
                self.base_ref(
                    id="REF-0102",
                    url="https://developer.android.com/reference/android/webkit/WebView/",
                ),
            ]
        )
        self.assertFalse(result.ok)

    def test_google_drive_locator_leak_fails(self):
        result = self.run_case(
            [self.base_ref(url="https://drive.google.com/file/d/private/view")]
        )
        self.assertFalse(result.ok)

    def test_private_github_repository_url_leak_fails(self):
        result = self.run_case([self.base_ref(url="https://github.com/ed3c/skills-shared")])
        self.assertFalse(result.ok)

    def test_credential_query_key_fails(self):
        result = self.run_case(
            [self.base_ref(url="https://example.com/doc?access_token=secret")]
        )
        self.assertFalse(result.ok)

    def test_non_https_url_fails(self):
        result = self.run_case([self.base_ref(url="http://example.com")])
        self.assertFalse(result.ok)

    def test_verified_freshness_without_revision_fails(self):
        result = self.run_case([self.base_ref(freshness="CURRENT")])
        self.assertFalse(result.ok)

    def test_opaque_private_ref_requires_snapshot_when_exercised(self):
        result = self.run_case([self.private_opaque_ref()], private_ids=[])
        self.assertFalse(result.ok)
        self.assertEqual("FAIL", result.private_parity)

    def test_opaque_private_ref_snapshot_can_pass_without_locator(self):
        result = self.run_case(
            [self.private_opaque_ref()],
            private_ids=["REF-1001"],
        )
        self.assertTrue(result.ok)
        self.assertEqual("PASS", result.private_parity)

    def test_unresolved_opaque_mention_also_participates_in_parity(self):
        result = self.run_case(
            [self.base_ref(used_by=["REF-1999"])],
            private_ids=["REF-1999"],
        )
        self.assertTrue(result.ok)
        self.assertIn("REF-1999", result.opaque_private_refs)


if __name__ == "__main__":
    unittest.main()
