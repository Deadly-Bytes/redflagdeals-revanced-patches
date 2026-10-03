"""Offline contract tests for scripts/publish-release.py."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "publish-release.py"
SPEC = importlib.util.spec_from_file_location("publish_release", SCRIPT)
PUBLISH_RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISH_RELEASE)


class PublishReleaseTest(unittest.TestCase):
    VERSION = "1.1.0"
    TAG = "v" + VERSION
    REPO = "example/redflagdeals"

    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.previous_directory = Path.cwd()
        self.addCleanup(os.chdir, self.previous_directory)
        os.chdir(self.temporary_directory.name)

        Path("gradle.properties").write_text("version = 1.1.0\n")
        Path("releases").mkdir()
        Path("releases/1.1.0.md").write_text("release notes\n")
        bundle_directory = Path("patches/build/libs")
        bundle_directory.mkdir(parents=True)
        self.bundle = bundle_directory / "redflagdeals-revanced-patches-1.1.0.rvp"
        self.bundle.write_bytes(b"release bundle")
        self.digest = "sha256:" + hashlib.sha256(self.bundle.read_bytes()).hexdigest()
        self.environment = {
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": "source-sha",
            "GITHUB_REPOSITORY": self.REPO,
        }

    def run_main(self, api, gh):
        with patch.dict(os.environ, self.environment, clear=False), \
                patch.object(PUBLISH_RELEASE, "api", side_effect=api), \
                patch.object(PUBLISH_RELEASE, "gh", side_effect=gh), \
                patch.object(sys, "argv", [str(SCRIPT)]):
            PUBLISH_RELEASE.main()

    def release(self, digest=None, draft=False, url=None, target="source-sha"):
        return {
            "draft": draft,
            "target_commitish": target,
            "published_at": "2026-10-03T12:00:00Z",
            "assets": [{
                "name": self.bundle.name,
                "digest": self.digest if digest is None else digest,
                "browser_download_url": url or "https://example.invalid/" + self.bundle.name,
            }],
        }

    def gh_for_release(self, releases="[]", release_id=42):
        def gh(*args):
            if args[:2] == ("release", "list"):
                return releases
            if args[:2] == ("release", "view"):
                return json.dumps({"databaseId": release_id})
            return ""

        return Mock(side_effect=gh)

    @staticmethod
    def content(value, sha="content-sha"):
        return {"sha": sha, "content": base64.b64encode(value.encode()).decode()}

    def test_mismatched_tag_and_version_refuses_before_publication(self):
        self.environment["GITHUB_REF"] = "refs/tags/v1.0.0"
        api = Mock()
        gh = Mock()

        with self.assertRaisesRegex(ValueError, "tag matching the source version"):
            self.run_main(api, gh)

        api.assert_not_called()
        gh.assert_not_called()

    def test_existing_tag_at_another_commit_refuses_before_publication(self):
        api = Mock(side_effect=[
            [{"ref": "refs/tags/v1.1.0"}],
            {"sha": "other-sha"},
        ])
        gh = Mock()

        with self.assertRaisesRegex(ValueError, "Existing tag does not identify"):
            self.run_main(api, gh)

        gh.assert_not_called()

    def test_digest_mismatch_refuses_source_update(self):
        writes = []

        def api(path, payload=None):
            if payload is not None:
                writes.append((path, payload))
            if path.endswith("/git/matching-refs/tags/v1.1.0"):
                return []
            if path.endswith("/releases/42"):
                return self.release("sha256:not-the-bundle")
            self.fail("unexpected API call: " + path)

        with self.assertRaisesRegex(ValueError, "checksum differs"):
            self.run_main(api, self.gh_for_release())

        self.assertEqual([], writes)

    def test_published_asset_updates_source_descriptor(self):
        writes = []
        source = json.dumps({
            "download_url": "https://example.invalid/old.rvp",
            "created_at": "2026-01-01T00:00:00",
            "description": "RedFlagDeals Forums compatibility patches",
            "version": "1.0.0",
        }, indent=2) + "\n"

        def api(path, payload=None):
            if payload is not None:
                writes.append((path, payload))
                return {"sha": "updated"}
            if path.endswith("/git/matching-refs/tags/v1.1.0"):
                return []
            if path.endswith("/releases/42"):
                return self.release()
            if path.endswith("/contents/gradle.properties?ref=main"):
                return self.content("version = 1.1.0\n", "properties-sha")
            if path.endswith("/contents/source.json?ref=main"):
                return self.content(source, "source-sha")
            self.fail("unexpected API call: " + path)

        self.run_main(api, self.gh_for_release())

        self.assertEqual(1, len(writes))
        path, payload = writes[0]
        self.assertEqual("repos/example/redflagdeals/contents/source.json", path)
        self.assertEqual("source-sha", payload["sha"])
        self.assertEqual("main", payload["branch"])
        descriptor = json.loads(base64.b64decode(payload["content"]))
        self.assertEqual(self.VERSION, descriptor["version"])
        self.assertEqual(self.release()["assets"][0]["browser_download_url"],
                         descriptor["download_url"])
        self.assertEqual("2026-10-03T12:00:00", descriptor["created_at"])

    def test_older_release_when_main_is_newer_leaves_source_unchanged(self):
        writes = []

        def api(path, payload=None):
            if payload is not None:
                writes.append((path, payload))
                return {"sha": "updated"}
            if path.endswith("/git/matching-refs/tags/v1.1.0"):
                return []
            if path.endswith("/releases/42"):
                return self.release()
            if path.endswith("/contents/gradle.properties?ref=main"):
                return self.content("version = 1.2.0\n", "properties-sha")
            self.fail("unexpected API call: " + path)

        self.run_main(api, self.gh_for_release())

        self.assertEqual([], writes)

    def test_created_draft_publishes_and_uses_final_asset_url(self):
        writes = []
        draft_url = "https://example.invalid/drafts/asset.rvp"
        final_url = "https://example.invalid/releases/v1.1.0/asset.rvp"
        source = json.dumps({
            "download_url": "https://example.invalid/old.rvp",
            "created_at": "2026-01-01T00:00:00",
            "description": "RedFlagDeals Forums compatibility patches",
            "version": "1.0.0",
        }, indent=2) + "\n"
        releases = [
            self.release(draft=True, url=draft_url),
            self.release(draft=False, url=final_url),
        ]

        def api(path, payload=None):
            if payload is not None:
                writes.append((path, payload))
                return {"sha": "updated"}
            if path.endswith("/git/matching-refs/tags/v1.1.0"):
                return []
            if path.endswith("/releases/42"):
                return releases.pop(0)
            if path.endswith("/contents/gradle.properties?ref=main"):
                return self.content("version = 1.1.0\n", "properties-sha")
            if path.endswith("/contents/source.json?ref=main"):
                return self.content(source, "source-sha")
            self.fail("unexpected API call: " + path)

        gh = self.gh_for_release()
        self.run_main(api, gh)

        calls = [call.args[:2] for call in gh.call_args_list]
        self.assertIn(("release", "create"), calls)
        self.assertIn(("release", "edit"), calls)
        descriptor = json.loads(base64.b64decode(writes[0][1]["content"]))
        self.assertEqual(final_url, descriptor["download_url"])

    def test_resumed_matching_draft_is_published_without_creating_another_release(self):
        releases = [
            self.release(draft=True),
            self.release(draft=False),
        ]

        def api(path, payload=None):
            if payload is not None:
                self.fail("source should remain unchanged when main is newer")
            if path.endswith("/git/matching-refs/tags/v1.1.0"):
                return []
            if path.endswith("/releases/42"):
                return releases.pop(0)
            if path.endswith("/contents/gradle.properties?ref=main"):
                return self.content("version = 1.2.0\n", "properties-sha")
            self.fail("unexpected API call: " + path)

        gh = self.gh_for_release(json.dumps([{"tagName": self.TAG, "isDraft": True}]))
        self.run_main(api, gh)

        calls = [call.args[:2] for call in gh.call_args_list]
        self.assertNotIn(("release", "create"), calls)
        self.assertIn(("release", "edit"), calls)

    def test_draft_for_another_commit_refuses_before_publication(self):
        writes = []

        def api(path, payload=None):
            if payload is not None:
                writes.append((path, payload))
            if path.endswith("/git/matching-refs/tags/v1.1.0"):
                return []
            if path.endswith("/releases/42"):
                return self.release(draft=True, target="other-sha")
            self.fail("unexpected API call: " + path)

        gh = self.gh_for_release(json.dumps([{"tagName": self.TAG, "isDraft": True}]))
        with self.assertRaisesRegex(ValueError, "Existing draft does not target"):
            self.run_main(api, gh)

        calls = [call.args[:2] for call in gh.call_args_list]
        self.assertNotIn(("release", "upload"), calls)
        self.assertNotIn(("release", "edit"), calls)
        self.assertEqual([], writes)


if __name__ == "__main__":
    unittest.main()
