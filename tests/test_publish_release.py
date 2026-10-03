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

    def release(self, digest=None):
        return {
            "draft": False,
            "published_at": "2026-10-03T12:00:00Z",
            "assets": [{
                "name": self.bundle.name,
                "digest": self.digest if digest is None else digest,
                "browser_download_url": "https://example.invalid/" + self.bundle.name,
            }],
        }

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
            if path.endswith("/releases/tags/v1.1.0"):
                return self.release("sha256:not-the-bundle")
            self.fail("unexpected API call: " + path)

        with self.assertRaisesRegex(ValueError, "checksum differs"):
            self.run_main(api, Mock(return_value="[]"))

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
            if path.endswith("/releases/tags/v1.1.0"):
                return self.release()
            if path.endswith("/contents/gradle.properties?ref=main"):
                return self.content("version = 1.1.0\n", "properties-sha")
            if path.endswith("/contents/source.json?ref=main"):
                return self.content(source, "source-sha")
            self.fail("unexpected API call: " + path)

        self.run_main(api, Mock(return_value="[]"))

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
            if path.endswith("/releases/tags/v1.1.0"):
                return self.release()
            if path.endswith("/contents/gradle.properties?ref=main"):
                return self.content("version = 1.2.0\n", "properties-sha")
            self.fail("unexpected API call: " + path)

        self.run_main(api, Mock(return_value="[]"))

        self.assertEqual([], writes)


if __name__ == "__main__":
    unittest.main()
