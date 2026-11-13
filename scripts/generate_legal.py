#!/usr/bin/env python3
"""Build deterministic, offline legal documents. Use --check in CI."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
inventory = json.loads((ROOT / "third_party/dependencies.json").read_text())
parts = [
    "Yorube — Third-party notices",
    "These notices apply to third-party components, independently of the license chosen for Yorube itself. Runtime artifacts are listed conservatively before R8 shrinking.",
]
for dep in inventory["dependencies"]:
    authors = [d.get("name", "") for d in dep.get("developers", [])]
    parts.append("\n".join([
        dep["coordinate"],
        "License: " + ", ".join(dep["declared_license_spdx"]),
        "Project: " + dep["project_url"],
        "Developers: " + ", ".join(filter(None, authors)),
        "Binary SHA-256: " + dep["binary_sha256"],
    ]))
    for notice in dep["embedded_legal_files"]:
        parts.append(dep["coordinate"] + " — " + notice["path"] + "\n\n" + notice["text"])
parts.append("Additional bundled components")
for component in inventory["additional_components"]:
    parts.append("\n".join([
        component["id"] + " (via " + component["included_via"] + ")",
        "License: " + component["license_spdx"],
        component.get("copyright", ""),
        "Source: " + component["source_url"],
    ]).strip())
parts.append("Material Symbols auto_awesome (ic_hero_spark.xml)\nGoogle / Material Design icon contributors\nApache-2.0\nhttps://github.com/google/material-design-icons")
parts.append("The MPL-2.0 Public Suffix List source matching OkHttp 4.12.0 is bundled in this APK at legal/public_suffix_list.dat and accompanies releases as public_suffix_list.dat. It is also maintained at third_party/sources/public_suffix_list.dat in https://github.com/Milix-M/Yorube. This data is unmodified; OkHttp compiles it to a binary resource. MPL terms apply to this data, independently of Yorube.")
for directory in ("notices", "licenses"):
    for path in sorted((ROOT / "third_party" / directory).glob("*.txt")):
        parts.append("===== " + path.name + " =====\n\n" + path.read_text().strip())
privacy = json.loads((ROOT / "legal/privacy.json").read_text())
privacy_parts = [privacy["title"], "最終更新日: " + privacy["updated"]]
for section in privacy["sections"]:
    privacy_parts += [section["title"], *section["paragraphs"]]
license_text = (ROOT / "LICENSE").read_text()
notices = "\n\n".join(parts) + "\n"
outputs = {
    ROOT / "app/src/main/assets/legal/LICENSES.txt": "Yorube — Licenses\n\n" + license_text.rstrip() + "\n\n" + notices,
    ROOT / "app/src/main/assets/legal/THIRD_PARTY_NOTICES.txt": notices,
    ROOT / "third_party/THIRD_PARTY_NOTICES.txt": notices,
    ROOT / "app/src/main/assets/legal/PRIVACY.txt": "\n\n".join(privacy_parts) + "\n",
    ROOT / "PRIVACY.md": "# " + privacy["title"] + "\n\n最終更新日: " + privacy["updated"] + "\n\n" + "\n\n".join("## " + s["title"] + "\n\n" + "\n\n".join(s["paragraphs"]) for s in privacy["sections"]) + "\n",
    ROOT / "app/src/main/assets/legal/public_suffix_list.dat": (ROOT / "third_party/sources/public_suffix_list.dat").read_text(),
}
for path, content in outputs.items():
    if args.check:
        if not path.exists() or path.read_text() != content:
            raise SystemExit(f"Outdated legal document: {path.relative_to(ROOT)}. Run python3 scripts/generate_legal.py")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
print(f"Legal documents {'verified' if args.check else 'generated'} ({len(inventory['dependencies'])} runtime dependencies).")
