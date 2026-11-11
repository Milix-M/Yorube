#!/usr/bin/env python3
"""Verify that the saved editable PSL source matches an OkHttp resource.

Usage: python3 verify-publicsuffix.py public_suffix_list-okhttp-4.12.0.dat okhttp-4.12.0.jar
This reimplements the serialization documented by OkHttp's
PublicSuffixListGenerator.java: ignore comments/blank lines, split exception
rules, sort unsigned UTF-8 bytes, append newlines, prefix each section with a
big-endian int32 byte length. Compare decompressed payloads because gzip
headers and compression settings can differ without changing the data.
"""
import gzip
import hashlib
import pathlib
import struct
import sys
import zipfile

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
rules, exceptions = [], []
for line in source.splitlines():
    if not line.strip() or line.startswith("//"):
        continue
    target = exceptions if line.startswith("!") else rules
    target.append((line[1:] if line.startswith("!") else line).encode("utf-8"))
if len(rules) != len(set(rules)) or len(exceptions) != len(set(exceptions)):
    raise SystemExit("Duplicate rules are not supported by this verifier")

def section(items):
    data = b"".join(rule + b"\n" for rule in sorted(items))
    return struct.pack(">I", len(data)) + data

payload = section(rules) + section(exceptions)
with zipfile.ZipFile(sys.argv[2]) as archive:
    resource = archive.read("okhttp3/internal/publicsuffix/publicsuffixes.gz")
if payload != gzip.decompress(resource):
    raise SystemExit("MISMATCH: the source does not reproduce the packaged payload")
print(f"MATCH: {len(rules)} rules, {len(exceptions)} exceptions")
print("Uncompressed payload SHA-256:", hashlib.sha256(payload).hexdigest())
