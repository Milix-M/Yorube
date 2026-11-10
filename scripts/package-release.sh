#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test -s LICENSE || { echo "Missing Yorube LICENSE; restore it before publishing." >&2; exit 1; }
python3 scripts/generate_legal.py --check
release_dir=app/build/outputs/distribution
mkdir -p "$release_dir"
cp app/build/outputs/apk/release/app-release.apk "$release_dir/yorube.apk"
cp LICENSE "$release_dir/LICENSE.txt"
cp app/src/main/assets/legal/{PRIVACY.txt,THIRD_PARTY_NOTICES.txt,public_suffix_list.dat} "$release_dir/"
(
  cd "$release_dir"
  sha256sum yorube.apk LICENSE.txt PRIVACY.txt THIRD_PARTY_NOTICES.txt public_suffix_list.dat > SHA256SUMS
)
