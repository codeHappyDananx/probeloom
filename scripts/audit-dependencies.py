#!/usr/bin/env python3
"""Inventory built dependencies and preserved upstream legal evidence locally."""
import argparse
import hashlib
import io
import json
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_name(value):
    return re.sub(r"[^A-Za-z0-9._-]", "_", value)


def legal_file(name):
    basename = Path(name).name.lower()
    if basename.endswith((".class", ".java", ".js", ".mjs", ".map", ".ts", ".json")):
        return False
    return bool(re.search(r"(?:^|[-_.])(licen[cs]es?|notices?|copying|copyright)(?:$|[-_.])", basename))


def xml_text(node, name):
    value = node.find(name)
    return value.text.strip() if value is not None and value.text else ""


def pom_root(path):
    return ET.fromstring(re.sub(r' xmlns="[^"]+"', "", path.read_text(encoding="utf-8")))


def pom_evidence(path, repo, output, seen=None):
    seen = set() if seen is None else seen
    if path in seen or not path.exists():
        return [], []
    seen.add(path)
    root = pom_root(path)
    licenses = [{child.tag: (child.text or "").strip() for child in item}
                for item in root.findall("licenses/license")]
    parts = path.relative_to(repo).parts
    coordinate = ":".join((".".join(parts[:-3]), parts[-3], parts[-2]))
    target = output / "poms" / (safe_name(coordinate) + ".pom")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(path.read_bytes())
    evidence = [target.as_posix()]
    if licenses:
        return licenses, evidence
    parent = root.find("parent")
    if parent is not None:
        group = xml_text(parent, "groupId")
        artifact = xml_text(parent, "artifactId")
        version = xml_text(parent, "version")
        if all((group, artifact, version)) and "${" not in version:
            parent_path = repo / group.replace(".", "/") / artifact / version / (artifact + "-" + version + ".pom")
            inherited, ancestors = pom_evidence(parent_path, repo, output, seen)
            return inherited, evidence + ancestors
    return [], evidence


def save_bytes(output, relative, data):
    target = output / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return target


def audit_java(jar, repo, output):
    by_name = {}
    for path in repo.rglob("*.jar"):
        by_name.setdefault(path.name, []).append(path)
    records = []
    with zipfile.ZipFile(jar) as application:
        for name in sorted(application.namelist()):
            if not name.startswith("BOOT-INF/lib/") or not name.endswith(".jar"):
                continue
            data = application.read(name)
            filename = Path(name).name
            matches = [path for path in by_name.get(filename, []) if digest(path.read_bytes()) == digest(data)]
            coordinate = None
            pom = None
            if len(matches) == 1:
                parts = matches[0].relative_to(repo).parts
                coordinate = ":".join((".".join(parts[:-3]), parts[-3], parts[-2]))
                pom = matches[0].parent / (parts[-3] + "-" + parts[-2] + ".pom")
            with zipfile.ZipFile(io.BytesIO(data)) as dependency:
                if coordinate is None:
                    candidates = []
                    for entry in dependency.namelist():
                        if entry.startswith("META-INF/maven/") and entry.endswith("/pom.properties"):
                            properties = {key: value.strip() for key, value in re.findall(r"^([^#!\s=]+)\s*=\s*(.*)$", dependency.read(entry).decode("utf-8"), re.M)}
                            if all(key in properties for key in ("groupId", "artifactId", "version")):
                                candidates.append(":".join(properties[key] for key in ("groupId", "artifactId", "version")))
                    if len(candidates) == 1:
                        coordinate = candidates[0]
                        group, artifact, version = coordinate.split(":")
                        pom = repo / group.replace(".", "/") / artifact / version / (artifact + "-" + version + ".pom")
                identity = coordinate or filename
                artifact_output = output / "java" / safe_name(identity)
                files = []
                for entry in dependency.namelist():
                    if not entry.endswith("/") and legal_file(entry):
                        target = save_bytes(artifact_output, Path(entry), dependency.read(entry))
                        files.append(target.as_posix())
                licenses, evidence = pom_evidence(pom, repo, artifact_output) if pom and pom.exists() else ([], [])
            records.append({"coordinate": coordinate, "jar": name, "sha256": digest(data),
                            "scope": "runtime", "firstParty": bool(coordinate and coordinate.startswith("dev.probeloom:")),
                            "repositoryMatch": len(matches) == 1, "licenses": licenses,
                            "pomEvidence": evidence, "licenseFiles": files,
                            "missingLicenseMetadata": not licenses, "missingLicenseText": not files})
    return records


def package_dirs(ui):
    packages = []
    for modules in (ui / "node_modules" / ".pnpm").glob("*/node_modules"):
        for path in modules.iterdir():
            if path.is_symlink():
                continue
            if path.name.startswith("@"):
                packages.extend(child for child in path.iterdir() if not child.is_symlink() and (child / "package.json").is_file())
            elif (path / "package.json").is_file():
                packages.append(path)
    return packages


def resolve_package(start, name, boundary):
    directory = start
    while directory == boundary or boundary in directory.parents:
        candidate = directory / "node_modules" / name
        if (candidate / "package.json").is_file():
            return candidate.resolve()
        directory = directory.parent
    return None


def audit_npm(ui, output):
    root_manifest = json.loads((ui / "package.json").read_text(encoding="utf-8"))
    runtime = set()
    queue = [resolve_package(ui, name, ui) for name in root_manifest.get("dependencies", {})]
    while queue:
        package = queue.pop()
        if package is None or package in runtime:
            continue
        runtime.add(package)
        manifest = json.loads((package / "package.json").read_text(encoding="utf-8"))
        for name in set(manifest.get("dependencies", {})) | set(manifest.get("optionalDependencies", {})) | set(manifest.get("peerDependencies", {})):
            queue.append(resolve_package(package, name, ui))
    records = []
    seen = set()
    for package in sorted(package_dirs(ui)):
        manifest_bytes = (package / "package.json").read_bytes()
        manifest = json.loads(manifest_bytes)
        coordinate = manifest["name"] + "@" + manifest["version"]
        manifest_hash = digest(manifest_bytes)
        identity = coordinate + "-" + manifest_hash[:12]
        if identity in seen:
            continue
        seen.add(identity)
        package_output = output / "npm" / safe_name(identity)
        metadata = save_bytes(package_output, Path("package.json"), manifest_bytes)
        files = []
        for path in package.rglob("*"):
            if path.is_file() and not path.is_symlink() and "node_modules" not in path.relative_to(package).parts and legal_file(path.name):
                files.append(save_bytes(package_output, path.relative_to(package), path.read_bytes()).as_posix())
        records.append({"coordinate": coordinate, "manifestSha256": manifest_hash,
                        "scope": "runtime-dependency" if package.resolve() in runtime else "development-or-build",
                        "license": manifest.get("license"), "legacyLicenses": manifest.get("licenses"),
                        "manifestEvidence": metadata.as_posix(), "licenseFiles": files,
                        "missingLicenseMetadata": not (manifest.get("license") or manifest.get("licenses")),
                        "missingLicenseText": not files})
    return records


def audit_native(root, output):
    manifest = output / "native/dependencies.json"
    if not manifest.exists():
        return []
    components = json.loads(manifest.read_text(encoding="utf-8"))["components"]
    for component in components:
        for key in ("name", "version", "license", "sources", "files", "licenseFiles"):
            if not component[key]:
                raise ValueError("Missing native dependency field: " + key)
        source_files = [source for source in component["sources"] if "path" in source and "parts" not in source]
        source_files += [part for source in component["sources"] if "parts" in source for part in source["parts"]]
        for entry in component["files"] + component["licenseFiles"] + source_files:
            relative = Path(entry["path"])
            path = (root / relative).resolve()
            if relative.is_absolute() or root not in path.parents:
                raise ValueError("Native evidence path escapes project: " + entry["path"])
            if digest(path.read_bytes()) != entry["sha256"]:
                raise ValueError("Native dependency hash mismatch: " + entry["path"])
        for source in component["sources"]:
            if not source["url"].startswith("https://") or not re.fullmatch(r"[0-9a-f]{64}", source["sha256"]):
                raise ValueError("Native dependency needs exact source URL and SHA-256")
            if "parts" in source:
                combined = hashlib.sha256()
                for part in source["parts"]:
                    with (root / part["path"]).open("rb") as stream:
                        for block in iter(lambda: stream.read(1024 * 1024), b""):
                            combined.update(block)
                if combined.hexdigest() != source["sha256"]:
                    raise ValueError("Reassembled native source hash mismatch: " + source["path"])
    return components


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--jar", type=Path, default=Path("test-platform-deploy/target/probeloom.jar"))
    parser.add_argument("--repository", type=Path, default=Path(".build/m2"))
    args = parser.parse_args()
    root = args.root.resolve()
    jar = (root / args.jar).resolve()
    repo = (root / args.repository).resolve()
    output = root / "licenses"
    if not jar.is_file() or not repo.is_dir():
        parser.error("Build the application using its isolated Maven repository first.")
    # These two directories contain only generated evidence; preserve any other license material.
    for name in ("java", "npm"):
        target = output / name
        if target.resolve().parent != output.resolve():
            raise ValueError("License output must stay inside the project's licenses directory")
        if target.exists():
            shutil.rmtree(target)
    java = audit_java(jar, repo, output)
    npm = audit_npm(root / "test-platform-ui", output)
    native = audit_native(root, output)
    supplemental_path = output / "upstream/evidence.json"
    supplemental = {}
    if supplemental_path.exists():
        supplemental = {item["coordinate"]: item for item in json.loads(supplemental_path.read_text(encoding="utf-8"))["dependencies"]}
    for item in java + npm:
        extra = supplemental.get(item["coordinate"])
        item["supplementalEvidence"] = extra
        if extra:
            for source in extra["sources"]:
                if digest((root / source["output"]).read_bytes()) != source["sha256"]:
                    raise ValueError("Supplemental evidence hash mismatch: " + source["output"])
        item["packagedTextIsNoticeOnly"] = bool(extra and extra.get("packagedTextIsNoticeOnly"))
        item["missingLicenseEvidence"] = (item["missingLicenseMetadata"] and not (extra and extra.get("upstreamLicenseDeclaration"))) or ((item["missingLicenseText"] or item["packagedTextIsNoticeOnly"]) and not (extra and extra["resolvedLicenseText"]))
    # Store portable project-relative evidence paths, never local workstation paths.
    def portable(value):
        if isinstance(value, dict):
            return {key: portable(item) for key, item in value.items()}
        if isinstance(value, list):
            return [portable(item) for item in value]
        if isinstance(value, str) and value.startswith(output.as_posix() + "/"):
            return Path(value).relative_to(root).as_posix()
        return value
    java, npm = portable(java), portable(npm)
    font_source = root / "test-platform-ui/src/features/statistics/fonts/OFL.txt"
    font_target = output / "fonts/barlow/OFL.txt"
    font_target.parent.mkdir(parents=True, exist_ok=True)
    font_target.write_bytes(font_source.read_bytes())
    report = {"schemaVersion": 1, "inputs": {"jar": jar.relative_to(root).as_posix(), "jarSha256": digest(jar.read_bytes()),
              "mavenRepository": repo.relative_to(root).as_posix(), "npmLockSha256": digest((root / "test-platform-ui/pnpm-lock.yaml").read_bytes())},
              "java": java, "npm": npm, "native": native, "fonts": [{"name": "Barlow", "license": "SIL Open Font License 1.1",
              "licenseFile": font_target.relative_to(root).as_posix(), "sha256": digest(font_source.read_bytes())}]}
    (output / "dependencies.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    third_party = [item for item in java if not item["firstParty"]]
    lines = ["# Third-party dependency evidence", "", "Generated by `python scripts/audit-dependencies.py` from the built executable JAR, isolated Maven repository, installed pnpm packages and preserved versioned upstream evidence. Run `python scripts/fetch-license-evidence.py` to retrieve supplemental official evidence, then rerun the audit. Upstream evidence includes source URLs, archive-entry provenance and SHA-256 hashes; it is distinguished from packaged legal files.", "",
             "| Inventory | Count | Missing metadata | Missing packaged text | Unresolved evidence |", "|---|---:|---:|---:|---:|",
             "| Java runtime | %d | %d | %d | %d |" % (len(third_party), sum(item["missingLicenseMetadata"] for item in third_party), sum(item["missingLicenseText"] for item in third_party), sum(item["missingLicenseEvidence"] for item in third_party)),
             "| npm runtime and build | %d | %d | %d | %d |" % (len(npm), sum(item["missingLicenseMetadata"] for item in npm), sum(item["missingLicenseText"] for item in npm), sum(item["missingLicenseEvidence"] for item in npm)), "",
             "The machine-readable inventory is [licenses/dependencies.json](licenses/dependencies.json). Original JAR LICENSE/NOTICE/COPYING files, Maven POM evidence (including parent POMs supplying license declarations), and npm package metadata and legal files are preserved under `licenses/java/` and `licenses/npm/`. Versioned supplemental texts and source copyright notices are under `licenses/upstream/`. JARs containing only license references/notices rather than full terms have supplemental full texts and are marked `packagedTextIsNoticeOnly`. npm build-only packages are distinguished from the runtime dependency graph; inclusion in this inventory does not claim every npm package is embedded in the frontend bundle.", "",
             "## Release review", "", "ProbeLoom application code uses Apache-2.0; see the root LICENSE. Third-party components retain their own license terms. Component-specific distribution requirements and corresponding source are documented in `licenses/release-review.md`.", "",
             "Other dependency declarations, including multi-license expressions and GPL-related terms, must be reviewed individually. See `licenses/release-review.md` for unresolved publication decisions and evidence gaps. A collected license text does not certify compliance with its distribution conditions. The Barlow font's original OFL text is copied to `licenses/fonts/barlow/OFL.txt` for distribution with the font files.", "",
             "## Java declarations", "", "| Coordinate | Declared license | Packaged legal files | Supplemental files |", "|---|---|---:|---:|"]
    for item in third_party:
        names = "; ".join(license.get("name", "") for license in item["licenses"]) or "MISSING"
        extra = item["supplementalEvidence"]
        if extra and extra.get("upstreamLicenseDeclaration"):
            names += "; upstream source: " + extra["upstreamLicenseDeclaration"]
        lines.append("| `%s` | %s | %d | %d |" % (item["coordinate"] or item["jar"], names.replace("|", "\\|"), len(item["licenseFiles"]), len(extra["files"]) if extra else 0))
    lines += ["", "## npm declarations", "", "| Package | Declared license | Usage | Packaged legal files | Supplemental files |", "|---|---|---|---:|---:|"]
    for item in npm:
        declared = item["license"] or item["legacyLicenses"] or "MISSING"
        if not isinstance(declared, str):
            declared = json.dumps(declared, ensure_ascii=False)
        extra = item["supplementalEvidence"]
        lines.append("| `%s` | %s | %s | %d | %d |" % (item["coordinate"], declared.replace("|", "\\|"), item["scope"], len(item["licenseFiles"]), len(extra["files"]) if extra else 0))
    if native:
        lines += ["", "## Native declarations", "", "Source archives, build settings, original legal files and binary hashes are preserved in `licenses/native/dependencies.json`; every referenced local file is hash-checked by this audit.", "", "| Component | License | Binary files | Legal files |", "|---|---|---:|---:|"]
        for item in native:
            lines.append("| `%s %s` | %s | %d | %d |" % (item["name"], item["version"], item["license"].replace("|", "\\|"), len(item["files"]), len(item["licenseFiles"])))
    detail_start = lines.index("## Java declarations")
    details = ["# Dependency license declarations", "", "Declarations copied from the local POM/package metadata. Full evidence and unresolved gaps are listed in `dependencies.json`.", ""] + lines[detail_start:]
    (output / "inventory.md").write_text("\n".join(details) + "\n", encoding="utf-8")
    summary = lines[:detail_start]
    summary += ["MySQL Connector/J 8.0.33 is distributed unmodified under GPLv2 with Universal FOSS Exception 1.0. Its original release archive, build scripts and additional interface-source materials accompany both source and runtime distributions under `licenses/sources/mysql-connector-j_8.0.33/`; see that directory's README.md and SOURCE.json for exact versions, original terms, hashes and build instructions. The original driver LICENSE is preserved under `licenses/java/com.mysql_mysql-connector-j_8.0.33/`. The complete Apache-2.0 application source is supplied in `probeloom-source.zip` alongside `probeloom-runtime.zip`; the runtime START.txt records the matching source archive's SHA-256. Provide both archives together.", ""]
    summary += ["Unmodified Logback 1.2.3 is distributed under the selected EPL 1.0 terms; unmodified jakarta.annotation-api 1.3.5 uses the selected EPL 2.0 terms. Their complete official source archives, build files, original legal texts and source-access instructions accompany both distributions under `licenses/sources/logback_1.2.3/` and `licenses/sources/jakarta.annotation-api_1.3.5/`. Additional component-specific notices and usage boundaries are in `licenses/supplemental-distribution-notices.md` and `licenses/native/DISTRIBUTION-NOTICES.txt`.", ""]
    if native:
        summary += ["The bundled Apache SVN command-line tools and their replaceable runtime libraries contain %d inventoried native components. Exact package versions, binary hashes, original legal texts and matching source packages are recorded in `licenses/native/dependencies.json`. These tools run as separate processes; ProbeLoom's application license remains Apache-2.0. Preserve all corresponding source, build instructions and individual library terms under `licenses/native/`. HTTPS uses the JVM trust store or an explicitly configured certificate fingerprint." % len(native), ""]
    summary += ["See [licenses/inventory.md](licenses/inventory.md) for every dependency's declared license and packaged legal-file count."]
    (root / "THIRD-PARTY-NOTICES.md").write_text("\n".join(summary) + "\n", encoding="utf-8")
    print(json.dumps({"javaRuntime": len(third_party), "npm": len(npm), "native": len(native),
                      "missingMetadata": [item["coordinate"] for item in third_party + npm if item["missingLicenseMetadata"]],
                      "missingPackagedText": [item["coordinate"] for item in third_party + npm if item["missingLicenseText"]],
                      "unresolvedEvidence": [item["coordinate"] for item in third_party + npm if item["missingLicenseEvidence"]]}, indent=2))


if __name__ == "__main__":
    main()

