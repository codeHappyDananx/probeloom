#!/usr/bin/env python3
"""Package the reviewed source and executable without local runtime material."""
import hashlib
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
output = root.parent / "releases"
output.mkdir(exist_ok=True)
main_license = root / "LICENSE"
main_license_sha256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
if not main_license.is_file() or hashlib.sha256(main_license.read_bytes()).hexdigest() != main_license_sha256:
    raise SystemExit("Provide the complete reviewed Apache-2.0 LICENSE before packaging")
directories = [".mvn", "scripts", "licenses", "native", "test-platform-app",
               "test-platform-workbook-core", "test-platform-deploy", "test-platform-ui"]
files = [root / name for name in ["pom.xml", ".gitignore", ".gitattributes", "build.ps1", "run.ps1",
                                  "run.sh", "README.md", "LICENSE", "THIRD-PARTY-NOTICES.md"]]
excluded = {"target", "node_modules", "dist", "__pycache__", ".git", ".svn"}
for directory in directories:
    files.extend(path for path in (root / directory).rglob("*")
                 if path.is_file() and not excluded.intersection(path.relative_to(root).parts)
                 and path.suffix.lower() not in {".log", ".dump", ".dumpstream", ".hprof", ".pyc"})
files = sorted(files)
jar = root / "test-platform-deploy/target/probeloom.jar"
if not jar.is_file():
    raise SystemExit("Build probeloom.jar before packaging")
native = root / "native/windows-x86_64"
if not all((native / "bin" / name).is_file() for name in ("svn.exe", "svnmucc.exe")):
    raise SystemExit("Run scripts/fetch-svn-cli.py before packaging")
native_files = sorted(path for path in native.rglob("*") if path.is_file())
native_manifest = root / "licenses/native/dependencies.json"
native_components = json.loads(native_manifest.read_text(encoding="utf-8"))["components"]
declared_native = set()
for component in native_components:
    source_files = [source for source in component["sources"] if "path" in source and "parts" not in source]
    source_files += [part for source in component["sources"] if "parts" in source for part in source["parts"]]
    for entry in component["files"] + component["licenseFiles"] + source_files:
        path = root / entry["path"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise SystemExit("Native evidence changed; rebuild and audit before packaging: " + entry["path"])
    for source in component["sources"]:
        if "parts" in source:
            combined = hashlib.sha256()
            for part in source["parts"]:
                with (root / part["path"]).open("rb") as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        combined.update(block)
            if combined.hexdigest() != source["sha256"]:
                raise SystemExit("Reassembled native source hash mismatch: " + source["path"])
    declared_native.update(entry["path"] for entry in component["files"])
if declared_native != {path.relative_to(root).as_posix() for path in native_files}:
    raise SystemExit("Native package contents do not match licenses/native/dependencies.json")
inventory = json.loads((root / "licenses/dependencies.json").read_text(encoding="utf-8"))
if inventory["inputs"]["jarSha256"] != hashlib.sha256(jar.read_bytes()).hexdigest() or inventory["native"] != native_components:
    raise SystemExit("Run the dependency audit against the current JAR and native libraries before packaging")
if any(item["missingLicenseEvidence"] for item in inventory["java"] + inventory["npm"] if not item.get("firstParty", False)):
    raise SystemExit("Complete dependency license evidence before packaging")
source_materials = []
runtime_hashes = {item["coordinate"]: item["sha256"] for item in inventory["java"]}
for name in ["logback_1.2.3", "jakarta.annotation-api_1.3.5"]:
    record = json.loads((root / "licenses/sources" / name / "SOURCE.json").read_text(encoding="utf-8"))
    for entry in record["files"]:
        path = root / entry["path"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise SystemExit("Corresponding source material changed: " + entry["path"])
    for binary in record["binaries"]:
        if runtime_hashes[binary["coordinate"]] != binary["sha256"]:
            raise SystemExit("Corresponding source does not match runtime: " + binary["coordinate"])
    source_materials.append(record)
mysql_source = json.loads((root / "licenses/sources/mysql-connector-j_8.0.33/SOURCE.json").read_text(encoding="utf-8"))
for entry in mysql_source["files"]:
    path = root / entry["path"]
    if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
        raise SystemExit("MySQL corresponding source changed: " + entry["path"])
with zipfile.ZipFile(jar) as application:
    driver = application.read("BOOT-INF/lib/mysql-connector-j-8.0.33.jar")
if hashlib.sha256(driver).hexdigest() != mysql_source["driverSha256"]:
    raise SystemExit("MySQL driver differs from its reviewed corresponding source")
with zipfile.ZipFile(root / mysql_source["releaseArchive"]) as upstream:
    if upstream.read("mysql-connector-j-8.0.33/mysql-connector-j-8.0.33.jar") != driver:
        raise SystemExit("MySQL source release does not contain the distributed driver")
    if upstream.read("mysql-connector-j-8.0.33/LICENSE") != (root / "licenses/java/com.mysql_mysql-connector-j_8.0.33/LICENSE").read_bytes():
        raise SystemExit("MySQL source and binary licensing materials differ")
with zipfile.ZipFile(output / "probeloom-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in files:
        archive.write(path, "probeloom/" + path.relative_to(root).as_posix())
source_sha256 = hashlib.sha256((output / "probeloom-source.zip").read_bytes()).hexdigest()
with zipfile.ZipFile(output / "probeloom-runtime.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    archive.write(jar, "probeloom/probeloom.jar")
    for path in native_files:
        archive.write(path, "probeloom/" + path.relative_to(root).as_posix())
    archive.write(root / "run.ps1", "probeloom/run.ps1")
    archive.write(root / "run.sh", "probeloom/run.sh")
    archive.write(root / "scripts/build-svn-serf.py", "probeloom/scripts/build-svn-serf.py")
    archive.write(main_license, "probeloom/LICENSE")
    archive.write(root / "THIRD-PARTY-NOTICES.md", "probeloom/THIRD-PARTY-NOTICES.md")
    for path in (root / "licenses").rglob("*"):
        if path.is_file():
            archive.write(path, "probeloom/" + path.relative_to(root).as_posix())
    archive.writestr("probeloom/START.txt", "ProbeLoom\nJDK 8+\nWindows x64 (SVN backup included): powershell -File run.ps1\nLinux/macOS: sh run.sh (SVN backup requires svn and svnmucc 1.14+ on PATH)\nOpen: http://localhost:18090\nFirst use: set and confirm your own admin password in the browser; setup signs you in automatically.\nLater visits: log in as admin with the password you set.\nKeep generated data and config directories private.\nSee the source README.md for configuration, samples and extension instructions.\nProbeLoom application code: Apache-2.0; see LICENSE. Third-party components retain their own terms; see THIRD-PARTY-NOTICES.md and licenses/.\nBundled SVN command-line tools are separate programs. Their replaceable runtime libraries, original notices and corresponding source accompany both archives under native/ and licenses/native/.\nDistribute this runtime together with its complete application source: probeloom-source.zip (SHA-256 " + source_sha256 + ").\n")
manifest = {"sourceFileCount": len(files), "jarSha256": inventory["inputs"]["jarSha256"],
            "applicationLicense": {"spdx": "Apache-2.0", "path": "LICENSE", "sha256": main_license_sha256},
            "thirdPartySourceMaterials": source_materials,
            "mysqlCorrespondingSource": mysql_source,
            "native": [{"path": path.relative_to(root).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                       for path in native_files], "artifacts": {}}
for name in ["probeloom-source.zip", "probeloom-runtime.zip"]:
    path = output / name
    manifest["artifacts"][name] = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                                  "bytes": path.stat().st_size}
(output / "probeloom-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(json.dumps(manifest, indent=2))
