#!/usr/bin/env python3
"""Convert AboutLibraries + resolved release binaries into deterministic notices."""
import argparse
import gzip
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = set(json.loads((ROOT / "third_party/license-policy.json").read_text())["allowed_spdx_ids"])

def json_text(value):
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"

def embedded_notices(data, prefix="", depth=0):
    found = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in sorted(archive.namelist()):
            base = Path(name).name.lower()
            if name.endswith("/"):
                continue
            if depth < 2 and base.endswith(".jar"):
                found.extend(embedded_notices(archive.read(name), prefix + name + "!/", depth + 1))
            elif re.match(r"^(license|notice|copying|copyright|third[-_]party)([._-].*)?$", base) or base in {"al2.0", "lgpl2.1"}:
                if Path(base).suffix in {".class", ".png", ".jpg", ".so"}:
                    continue
                raw = archive.read(name)
                text = raw.decode("utf-8-sig").strip()
                if not text:
                    raise ValueError("Empty embedded legal file: " + name)
                found.append({"path": prefix + name, "sha256": hashlib.sha256(raw).hexdigest(), "text": text})
    return found

def collect(metadata, artifacts, supplemental, root):
    libraries = {lib["uniqueId"] + ":" + lib["artifactVersion"]: lib for lib in metadata["libraries"]}
    dependencies, license_files, selected = [], {}, []
    binaries = {a["coordinate"]: a for a in artifacts}
    if len(binaries) != len(artifacts):
        raise ValueError("Duplicate resolved release artifact coordinates")
    for coordinate, artifact in sorted(binaries.items()):
        if coordinate not in libraries:
            raise ValueError("Uncollected runtime dependency: " + coordinate)
        lib = libraries[coordinate]
        ids = sorted(lib.get("licenses", []))
        if not ids:
            raise ValueError("No license: " + coordinate)
        declared = []
        for identifier in ids:
            definition = metadata["licenses"].get(identifier, {})
            spdx = definition.get("spdxId") or identifier
            if spdx not in ALLOWED:
                raise ValueError("Unrecognized/unapproved license: " + identifier + " for " + coordinate)
            content = definition.get("content", "").strip()
            if len(content) < 100:
                raise ValueError("Missing license text: " + spdx)
            license_files[spdx + ".txt"] = content + "\n"
            declared.append(spdx)
        binary = Path(artifact["file"]).read_bytes()
        sha = hashlib.sha256(binary).hexdigest()
        selected.append(lib)
        dependencies.append({
            "coordinate": coordinate,
            "packaging": Path(artifact["file"]).suffix.lstrip("."),
            "binary_sha256": sha,
            "project_name": lib.get("name") or coordinate,
            "project_url": lib.get("website") or lib.get("scm", {}).get("url", ""),
            "developers": lib.get("developers", []),
            "declared_license_spdx": sorted(set(declared)),
            "embedded_legal_files": embedded_notices(binary),
            "metadata_source": "AboutLibraries 15.3.0 / resolved Gradle POM metadata",
        })
    hashes = {d["coordinate"]: d["binary_sha256"] for d in dependencies}
    for pin in supplemental["artifact_pins"]:
        if hashes.get(pin["coordinate"]) != pin["sha256"]:
            raise ValueError("Re-review bundled-component evidence after dependency change: " + pin["coordinate"])
    for file in supplemental["files"]:
        actual = hashlib.sha256((root / file["path"]).read_bytes()).hexdigest()
        if actual != file["sha256"]:
            raise ValueError("Supplemental notice/source changed: " + file["path"])
    for component in supplemental["additional_components"]:
        if component["included_via"] not in hashes:
            raise ValueError("Stale bundled component: " + component["id"])
        if component["id"] == "public-suffix-list":
            binary = binaries[component["included_via"]]
            with zipfile.ZipFile(binary["file"]) as jar:
                data = gzip.decompress(jar.read("okhttp3/internal/publicsuffix/publicsuffixes.gz"))
            if hashlib.sha256(data).hexdigest() != component["payload_sha256"]:
                raise ValueError("Public Suffix List payload no longer matches supplied source evidence")
    # Do not carry compile-only / KMP forwarding modules into the APK inventory.
    used = {identifier for lib in selected for identifier in lib["licenses"]}
    exported = {"libraries": sorted(selected, key=lambda x: x["uniqueId"]), "licenses": {k: metadata["licenses"][k] for k in sorted(used)}}
    result = {
        "schema_version": 2,
        "generator": "AboutLibraries 15.3.0 + scripts/collect_licenses.py",
        "scope": "resolved releaseRuntimeClasspath binary artifacts before R8",
        "dependencies": dependencies,
        "additional_components": supplemental["additional_components"],
    }
    return result, license_files, exported

def write_outputs(outputs, check):
    for path, content in outputs.items():
        if check:
            if not path.exists() or path.read_text() != content:
                raise ValueError("Outdated generated license file: " + str(path) + ". Run :app:generateThirdPartyNotices")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    build = ROOT / "app/build/reports/legal"
    inventory, texts, exported = collect(
        json.loads((build / "aboutlibraries.json").read_text()),
        json.loads((build / "release-artifacts.json").read_text()),
        json.loads((ROOT / "third_party/supplemental.json").read_text()),
        ROOT,
    )
    outputs = {
        ROOT / "third_party/dependencies.json": json_text(inventory),
        ROOT / "third_party/aboutlibraries.json": json_text(exported),
        **{ROOT / "third_party/licenses" / name: text for name, text in texts.items()},
    }
    write_outputs(outputs, args.check)
    print("Automatically collected and " + ("verified" if args.check else "generated") + " licenses for " + str(len(inventory["dependencies"])) + " release artifacts.")

if __name__ == "__main__":
    main()
