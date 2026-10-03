"""Publish an immutable bundle, then advance the Manager descriptor on main."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def api(path, payload=None):
    command = ["gh", "api", path]
    if payload is not None:
        command += ["--method", "PUT", "--input", "-"]
    result = subprocess.run(command, input=None if payload is None else json.dumps(payload),
                            text=True, check=True, capture_output=True)
    return json.loads(result.stdout)


def version_of(text):
    matches = re.findall(r"^version\s*=\s*(\d+\.\d+\.\d+)\s*$", text, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError("Expected one stable semantic version in gradle.properties")
    return matches[0]


def main():
    version = version_of(Path("gradle.properties").read_text())
    tag = "v" + version
    ref = os.environ["GITHUB_REF"]
    sha = os.environ["GITHUB_SHA"]
    repo = os.environ["GITHUB_REPOSITORY"]
    if ref not in ("refs/heads/main", "refs/tags/" + tag):
        raise ValueError("Release must run on main or the tag matching the source version")
    notes = Path("releases") / (version + ".md")
    if not notes.is_file():
        raise ValueError("Release notes are missing")
    if "--validate-only" in sys.argv:
        print(f"Validated {tag} release inputs")
        return

    name = f"redflagdeals-revanced-patches-{version}.rvp"
    bundle = Path("patches/build/libs") / name
    digest = "sha256:" + hashlib.sha256(bundle.read_bytes()).hexdigest()
    root = f"repos/{repo}"
    # A tag must never be moved, including when resuming an interrupted release.
    refs = api(root + "/git/matching-refs/tags/" + tag)
    if any(item["ref"] == "refs/tags/" + tag for item in refs):
        if api(root + "/commits/" + tag)["sha"] != sha:
            raise ValueError("Existing tag does not identify this source commit")

    releases = json.loads(gh("release", "list", "--repo", repo, "--limit", "1000",
                             "--json", "tagName,isDraft"))
    existing = next((item for item in releases if item["tagName"] == tag), None)
    if existing is None:
        gh("release", "create", tag, str(bundle), "--repo", repo, "--target", sha,
           "--title", tag, "--notes-file", str(notes), "--draft")
    # The REST tag endpoint excludes drafts; gh resolves both drafts and releases.
    release_id = json.loads(gh("release", "view", tag, "--repo", repo,
                               "--json", "databaseId"))["databaseId"]
    release_path = root + "/releases/" + str(release_id)
    release = api(release_path)
    if release["draft"] and release["target_commitish"] != sha:
        raise ValueError("Existing draft does not target this source commit")
    asset = next((item for item in release["assets"] if item["name"] == name), None)
    if asset is None and release["draft"]:
        gh("release", "upload", tag, str(bundle), "--repo", repo)
        release = api(release_path)
        asset = next(item for item in release["assets"] if item["name"] == name)
    if asset is None or asset.get("digest") != digest:
        raise ValueError("Published asset checksum differs; refusing to replace it")
    if release["draft"]:
        gh("release", "edit", tag, "--repo", repo, "--draft=false")
        release = api(release_path)
    if release["draft"] or not release["published_at"]:
        raise ValueError("Release publication is not confirmed")
    # Draft download URLs use an untagged placeholder; obtain the public URL afresh.
    asset = next(item for item in release["assets"] if item["name"] == name)
    if asset.get("digest") != digest:
        raise ValueError("Published asset checksum differs after publication")

    # An old tag rebuild must not roll Manager back after a newer version merges.
    properties = api(root + "/contents/gradle.properties?ref=main")
    main_version = version_of(base64.b64decode(properties["content"]).decode())
    if main_version != version:
        print(f"Published {tag}; main is {main_version}, leaving Manager source unchanged")
        return
    source = api(root + "/contents/source.json?ref=main")
    descriptor = json.loads(base64.b64decode(source["content"]))
    if tuple(map(int, descriptor["version"].split("."))) > tuple(map(int, version.split("."))):
        raise ValueError("Refusing to downgrade Manager source")
    descriptor.update(download_url=asset["browser_download_url"], version=version,
                      created_at=release["published_at"].removesuffix("Z"))
    content = json.dumps(descriptor, indent=2) + "\n"
    if base64.b64decode(source["content"]).decode() != content:
        api(root + "/contents/source.json", {
            "message": f"chore: point ReVanced Manager to {tag}", "branch": "main",
            "sha": source["sha"], "content": base64.b64encode(content.encode()).decode(),
        })
    print(f"Published {tag}: {asset['browser_download_url']} ({digest})")


if __name__ == "__main__":
    main()
