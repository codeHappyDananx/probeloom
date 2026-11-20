#!/usr/bin/env python3
"""Fetch versioned upstream legal evidence; no project source is copied."""
import hashlib
import io
import json
import re
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "licenses/upstream"
CACHE = ROOT / ".build/license-sources"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def safe(value):
    return re.sub(r"[^A-Za-z0-9._-]", "_", value)


def download(url):
    cached = CACHE / ("http-" + sha(url.encode("utf-8")))
    if cached.exists():
        return cached.read_bytes()
    request = urllib.request.Request(url, headers={"User-Agent": "ProbeLoom-license-evidence"})
    data = urllib.request.urlopen(request, timeout=40).read()
    cached.write_bytes(data)
    return data


def main():
    inventory = json.loads((ROOT / "licenses/dependencies.json").read_text(encoding="utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    records = []
    apache_url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
    apache = download(apache_url)
    notice_only = {"com.fasterxml:classmate:1.5.1", "com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.15.2",
                   "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2",
                   "com.fasterxml.jackson.module:jackson-module-parameter-names:2.15.2"}
    fragment_evidence = {"com.fasterxml.jackson.core:jackson-core:2.15.2"}
    for item in inventory["java"]:
        if item["firstParty"] or not (item["missingLicenseText"] or item["coordinate"] in notice_only | fragment_evidence):
            continue
        coordinate = item["coordinate"]
        group, artifact, version = coordinate.split(":")
        target = OUT / safe(coordinate)
        target.mkdir(exist_ok=True)
        record = {"coordinate": coordinate, "ecosystem": "java", "sources": [], "files": [],
                  "resolvedLicenseText": False, "packagedTextIsNoticeOnly": coordinate in notice_only}

        def save(name, data, source, **extra):
            path = target / name
            path.write_bytes(data)
            record["files"].append(path.relative_to(ROOT).as_posix())
            record["sources"].append({"url": source, "output": path.relative_to(ROOT).as_posix(),
                                      "sha256": sha(data), **extra})

        url = ("https://repo.maven.apache.org/maven2/" + group.replace(".", "/") + "/" + artifact + "/" +
               version + "/" + artifact + "-" + version + "-sources.jar")
        cached = CACHE / (artifact + "-" + version + "-sources.jar")
        if not cached.exists():
            cached.write_bytes(download(url))
        archive_hash = sha(cached.read_bytes())
        notices = {}
        with zipfile.ZipFile(cached) as archive:
            for name in archive.namelist():
                if name.endswith("/"):
                    continue
                if re.search(r"(^|/)(LICENSE|NOTICE|COPYING|COPYRIGHT)", name, re.I):
                    save("sources-" + safe(name), archive.read(name), url,
                         archiveEntry=name, archiveSha256=archive_hash)
                if not name.endswith(".java"):
                    continue
                text = archive.read(name).decode("utf-8", errors="replace")
                comments = re.findall(r"/\*.*?\*/|(?:(?m:^\s*//[^\n]*(?:\n|$)))+", text, re.S)
                for comment in comments:
                    if re.search(r"copyright|licensed under|redistribution and use|permission (?:is hereby granted|to use)|license:|SPDX-License-Identifier", comment, re.I):
                        notices.setdefault(comment, []).append(name)
        if notices:
            lines = []
            for comment, entries in notices.items():
                lines += ["Source entries: " + ", ".join(entries), comment, ""]
            save("SOURCE-NOTICES.txt", "\n".join(lines).encode("utf-8"), url,
                 archiveSha256=archive_hash, kind="Unmodified legal comment blocks; source code omitted")
        if any("Apache" in entry.get("name", "") for entry in item["licenses"]):
            save("Apache-2.0.txt", apache, apache_url, kind="License authority text named by the artifact POM")
            record["resolvedLicenseText"] = True
        # Full BSD/MIT terms are embedded in these versioned source comments.
        if (group == "com.jcraft" or coordinate.startswith(("org.ow2.asm:",
                "com.github.virtuald:", "com.google.protobuf:", "org.slf4j:jul-to-slf4j:", "org.slf4j:slf4j-api:"))):
            record["resolvedLicenseText"] = bool(notices)
        official = []
        if group == "ch.qos.logback":
            official = [("LICENSE.txt", "https://raw.githubusercontent.com/qos-ch/logback/v_1.2.3/LICENSE.txt"),
                        ("LGPL-2.1.txt", "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt"),
                        ("EPL-1.0.html", "https://www.eclipse.org/legal/epl-v10.html")]
        elif group == "net.i2p.crypto":
            official = [("CC0-1.0.txt", "https://raw.githubusercontent.com/str4d/ed25519-java/v0.3.0/LICENSE.txt")]
        elif group == "com.microsoft.sqlserver":
            official = [("LICENSE", "https://raw.githubusercontent.com/microsoft/mssql-jdbc/v7.4.1/LICENSE")]
        elif group == "org.eclipse.jgit":
            official = [("LICENSE", "https://raw.githubusercontent.com/eclipse-jgit/jgit/v5.13.3.202401111512-r/LICENSE")]
        elif coordinate == "com.fasterxml.jackson.core:jackson-core:2.15.2":
            official = [("FastDoubleParser-MIT-LICENSE", "https://raw.githubusercontent.com/wrandelshofer/FastDoubleParser/522be16e145f43308c43b23094e31d5efcaa580e/LICENSE")]
        for name, official_url in official:
            save(name, download(official_url), official_url)
        if official:
            record["resolvedLicenseText"] = True
        records.append(record)
        print(coordinate, len(record["files"]), flush=True)

    npm_sources = {
        "@oxlint/binding-win32-x64-msvc@1.78.0": "https://raw.githubusercontent.com/oxc-project/oxc/oxlint_v1.78.0/LICENSE",
        "@rolldown/binding-win32-x64-msvc@1.2.4": "https://raw.githubusercontent.com/rolldown/rolldown/v1.2.4/LICENSE",
    }
    for item in inventory["npm"]:
        if not item["missingLicenseText"]:
            continue
        coordinate = item["coordinate"]
        target = OUT / safe(coordinate)
        target.mkdir(exist_ok=True)
        record = {"coordinate": coordinate, "ecosystem": "npm", "sources": [], "files": [],
                  "resolvedLicenseText": coordinate in npm_sources}
        if coordinate in npm_sources:
            url = npm_sources[coordinate]
            data = download(url)
            path = target / "LICENSE"
            path.write_bytes(data)
            record["files"].append(path.relative_to(ROOT).as_posix())
            record["sources"].append({"url": url, "sha256": sha(data), "output": path.relative_to(ROOT).as_posix()})
        elif coordinate == "react-remove-scroll-bar@2.3.8":
            # The reviewed comparison is a pinned input, not an upstream legal text.
            proof_path = target / "code-equivalence-proof.json"
            proof_data = proof_path.read_bytes()
            if sha(proof_data) != "2bb6b2bb7c2b798ce4d8f88fd61ec480eb624eeb19a848595fc38466f22cc0cb":
                raise ValueError("Scrollbar source review changed; review the evidence before accepting it")
            proof = json.loads(proof_data)
            record["files"].append(proof_path.relative_to(ROOT).as_posix())
            record["sources"].append({"url": proof["repositoryCompare"]["url"],
                "output": proof_path.relative_to(ROOT).as_posix(), "sha256": sha(proof_data),
                "kind": "Reviewed derived comparison; publisher/source inputs verified below, not an upstream license"})
            evidence = []
            record["artifactSources"] = []
            for version, published in proof["publishedArchiveComparison"].items():
                metadata = download(published["registryUrl"])
                registry = json.loads(metadata)
                if registry["license"] != "MIT" or registry["gitHead"] != published["gitHead"] or registry["dist"]["tarball"] != published["tarballUrl"]:
                    raise ValueError("Scrollbar publisher declaration changed: " + version)
                archive_data = download(published["tarballUrl"])
                if sha(archive_data) != published["sha256"]:
                    raise ValueError("Scrollbar published archive changed: " + version)
                with tarfile.open(fileobj=io.BytesIO(archive_data), mode="r:gz") as archive:
                    actual = {entry.name: sha(archive.extractfile(entry).read()) for entry in archive.getmembers() if entry.isfile()}
                expected = {entry["path"]: entry["sha256_" + version] for entry in proof["files"]}
                if actual != expected:
                    raise ValueError("Scrollbar archive does not match reviewed files: " + version)
                if version == "2.3.8":
                    if item["manifestSha256"] != actual["package/package.json"]:
                        raise ValueError("Installed scrollbar manifest differs from the reviewed archive")
                    installed = list((ROOT / "test-platform-ui/node_modules/.pnpm").glob(
                        "react-remove-scroll-bar@2.3.8*/node_modules/react-remove-scroll-bar"))
                    if not installed or any(sha((package / name[len("package/"):]).read_bytes()) != value
                                            for package in installed for name, value in actual.items()):
                        raise ValueError("Installed scrollbar files differ from the reviewed archive")
                record["artifactSources"].append({"url": published["tarballUrl"], "sha256": sha(archive_data)})
                evidence.append(("registry-" + version + ".json", metadata, published["registryUrl"]))
            for source in proof["officialSourceArchives"]:
                url = "https://api.github.com/repos/theKashey/react-remove-scroll-bar/zipball/" + source["commit"]
                data = download(url)
                if sha(data) != source["sha256"]:
                    raise ValueError("Scrollbar source archive differs from the reviewed tree")
                record["artifactSources"].append({"url": url, "sha256": sha(data)})
            tag_url = "https://api.github.com/repos/theKashey/react-remove-scroll-bar/git/tags/7fe7bbe11f85b8a616c51c2331849537cdbe0549"
            tag = download(tag_url)
            if json.loads(tag)["object"]["sha"] != proof["publishedArchiveComparison"]["2.3.7"]["gitHead"]:
                raise ValueError("Scrollbar official tag does not match the publisher declaration")
            compare_url = proof["repositoryCompare"]["url"]
            comparison = download(compare_url)
            if [entry["filename"] for entry in json.loads(comparison)["files"]] != ["LICENSE"]:
                raise ValueError("Scrollbar official source comparison differs from the reviewed change")
            license_url = "https://raw.githubusercontent.com/theKashey/react-remove-scroll-bar/" + proof["license"]["commit"] + "/LICENSE"
            license_data = download(license_url)
            if sha(license_data) != proof["license"]["sha256"]:
                raise ValueError("Scrollbar official license differs from the reviewed original")
            evidence += [("official-v2.3.7-tag-object.json", tag, tag_url),
                         ("official-source-compare.json", comparison, compare_url),
                         ("LICENSE", license_data, license_url)]
            for name, data, url in evidence:
                path = target / name
                path.write_bytes(data)
                record["files"].append(path.relative_to(ROOT).as_posix())
                record["sources"].append({"url": url, "sha256": sha(data), "output": path.relative_to(ROOT).as_posix()})
            record["resolvedLicenseText"] = True
            record["upstreamLicenseDeclaration"] = "MIT; publisher declaration and reviewed unchanged implementation with fixed official copyright/license text"
            record["provenanceLimit"] = proof["sourceDistributionReview"]["limit"] + " The 2.3.8 gitHead remains unavailable."
        records.append(record)
    (OUT / "evidence.json").write_text(json.dumps({"schemaVersion": 1, "dependencies": records}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    retained = {ROOT / name for item in records for name in item["files"]} | {OUT / "evidence.json"}
    for path in OUT.rglob("*"):
        if path.is_file() and path not in retained:
            path.unlink()
    print("Unresolved:", [x["coordinate"] for x in records if not x["resolvedLicenseText"]])


if __name__ == "__main__":
    main()
