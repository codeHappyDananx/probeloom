#!/usr/bin/env python3
"""Restore the pinned Windows SVN CLI and verify its distribution evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "licenses/native/dependencies.json"


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def check(reference):
    path = ROOT / reference["path"]
    if not path.is_file() or digest(path) != reference["sha256"]:
        raise RuntimeError("Missing or changed distribution file: " + reference["path"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="verify without downloading")
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    cache = ROOT / ".build/svn-cli-packages"
    if not args.verify:
        cache.mkdir(parents=True, exist_ok=True)
        opener = urllib.request.build_opener()
        for component in manifest["components"]:
            archive = component["binaryPackage"]
            path = cache / archive["filename"]
            if not path.exists() or digest(path) != archive["sha256"]:
                temporary = path.with_suffix(path.suffix + ".download")
                print("Downloading " + archive["filename"], flush=True)
                with opener.open(archive["url"], timeout=120) as response, temporary.open("wb") as output:
                    for block in iter(lambda: response.read(1024 * 1024), b""):
                        output.write(block)
                if digest(temporary) != archive["sha256"]:
                    temporary.unlink()
                    raise RuntimeError("Binary package SHA-256 mismatch: " + archive["filename"])
                os.replace(str(temporary), str(path))
            for item in component["files"]:
                if item.get("builtFromSource"):
                    continue
                target = ROOT / item["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                data = subprocess.check_output(["tar", "-xOf", str(path), item["archiveEntry"]])
                if hashlib.sha256(data).hexdigest() != item["sha256"]:
                    raise RuntimeError("Extracted file SHA-256 mismatch: " + item["path"])
                target.write_bytes(data)
    count = 0
    for component in manifest["components"]:
        for item in component["files"] + component["licenseFiles"]:
            check(item)
            count += 1
    print("Verified {} files across {} pinned packages.".format(count, len(manifest["components"])))


if __name__ == "__main__":
    main()
