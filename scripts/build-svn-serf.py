#!/usr/bin/env python3
"""Rebuild the bundled Apache Serf DLL with the enclosed empty-peek fix."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LEGAL = ROOT / "licenses/native/msys2"
BUILD = ROOT / ".build/serf-source-build"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    manifest = json.loads((ROOT / "licenses/native/dependencies.json").read_text(encoding="utf-8"))
    serf = next(item for item in manifest["components"] if item["name"] == "libserf")
    for item in serf["licenseFiles"]:
        path = ROOT / item["path"]
        if not path.is_file() or sha(path) != item["sha256"]:
            raise RuntimeError("Missing or changed source/build evidence: " + item["path"])
    cache = BUILD / "packages"
    environment = BUILD / "msys"
    work = BUILD / "source"
    for path in (cache, environment, work, environment / "tmp"):
        path.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener()
    packages = json.loads((LEGAL / "build/tool-packages.json").read_text())
    for package in packages:
        archive = cache / package["filename"]
        if not archive.exists() or sha(archive) != package["sha256"]:
            temporary = archive.with_suffix(archive.suffix + ".download")
            print("Downloading " + package["filename"], flush=True)
            with opener.open(package["url"], timeout=120) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            if sha(temporary) != package["sha256"]:
                temporary.unlink()
                raise RuntimeError("Build package SHA-256 mismatch")
            os.replace(str(temporary), str(archive))
        subprocess.check_call(["tar", "-xf", str(archive), "-C", str(environment)])
    subprocess.check_call(["tar", "-xf", str(LEGAL / "sources/serf-1.3.10-4.src.tar.zst"), "-C", str(work)])
    recipe = work / "serf"
    source = recipe / "serf-1.3.10"
    if source.exists():
        shutil.rmtree(str(source))
    subprocess.check_call(["tar", "-xf", str(recipe / "serf-1.3.10.tar.bz2"), "-C", str(recipe)])
    shutil.copy2(str(LEGAL / "build/serf-empty-peek.patch"), str(recipe / "probeloom-empty-peek.patch"))
    script = BUILD / "build.sh"
    with script.open("w", encoding="utf-8", newline="\n") as output:
        output.write("""set -e
export PATH=/usr/bin:$PATH
cd "$(cygpath -u "$1")"
for p in ../0*.patch; do patch -p1 -i "$p"; done
patch -p1 -i ../probeloom-empty-peek.patch
python /usr/bin/scons PREFIX=/usr GSSAPI=/usr/bin/krb5-config CFLAGS='-O2 -g -D_XOPEN_SOURCE=700' -j4
strip --strip-unneeded msys-serf-1-0.dll
""")
    env = os.environ.copy()
    env["PATH"] = str(environment / "usr/bin") + os.pathsep + env["PATH"]
    subprocess.check_call([str(environment / "usr/bin/bash.exe"), str(script), str(source)], env=env)
    target = ROOT / "native/windows-x86_64/bin/msys-serf-1-0.dll"
    shutil.copy2(str(source / target.name), str(target))
    print("Built " + str(target))
    print("SHA-256 " + sha(target))


if __name__ == "__main__":
    main()
