#!/usr/bin/env python3
"""Verify the signed APK and create the complete distribution with checksums."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import zipfile

from verify_release_ref import app_version, app_version_code


ROOT = Path(__file__).resolve().parents[1]


def build_tool(name, override):
    if os.getenv(override):
        return os.environ[override]
    sdk = os.getenv('ANDROID_SDK_ROOT') or os.getenv('ANDROID_HOME')
    if sdk:
        candidates = list((Path(sdk) / 'build-tools').glob('*/' + name))
        candidates.sort(key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.parent.name)))
        if candidates:
            return str(candidates[-1])
    found = shutil.which(name)
    if found:
        return found
    raise ValueError(f'Cannot find {name}; configure the Android SDK or {override}')


def verify_signature(apk, expected, apksigner):
    result = subprocess.run([apksigner, 'verify', '--verbose', '--print-certs', str(apk)],
                            capture_output=True, text=True)
    if result.returncode:
        raise ValueError('APK signature verification failed')
    # Build Tools 37 uses "V2 Signer:"; older tools use "Signer #1" or SDK ranges.
    digests = re.findall(r'^(?:Signer[^\n]*|V\d+(?:\.\d+)? Signer[^\n]*) certificate SHA-256 digest:\s*([0-9a-fA-F]+)',
                         result.stdout, re.MULTILINE)
    actual = {digest.lower() for digest in digests}
    signers = re.search(r'^Number of signers:\s*(\d+)', result.stdout, re.MULTILINE)
    if actual != {expected} or (signers and int(signers.group(1)) != 1):
        raise ValueError('APK signing certificate does not match the public release certificate '
                         f'(expected {expected}; actual {sorted(actual) or "unparsed"}; tool {apksigner})')
    if 'CN=Android Debug' in result.stdout or 'CN=AndroidDebug' in result.stdout:
        raise ValueError('An Android debug signing key cannot be used for distribution')


def verify_apk(root, apk, version, version_code, aapt2):
    result = subprocess.run([aapt2, 'dump', 'badging', str(apk)], capture_output=True, text=True)
    if result.returncode:
        raise ValueError('Cannot inspect the APK manifest')
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
                        result.stdout, re.MULTILINE)
    if not package or package.groups() != ('com.hermes.client', str(version_code), version):
        raise ValueError('APK application ID or version does not match the release metadata')
    if re.search(r'^application-debuggable', result.stdout, re.MULTILINE):
        raise ValueError('A debuggable APK cannot be distributed')
    with zipfile.ZipFile(apk) as archive:
        for name in ('LICENSES.txt', 'PRIVACY.txt', 'THIRD_PARTY_NOTICES.txt', 'public_suffix_list.dat'):
            expected = (root / 'app/src/main/assets/legal' / name).read_bytes()
            if archive.read('assets/legal/' + name) != expected:
                raise ValueError('APK contains an outdated legal document: ' + name)


def package(root):
    subprocess.run(['python3', 'scripts/generate_legal.py', '--check'], cwd=root, check=True)
    expected_cert = (root / 'legal/release-signing-certificate.sha256').read_text().strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected_cert):
        raise ValueError('Missing or invalid public signing certificate fingerprint')
    certificate = ssl.PEM_cert_to_DER_cert((root / 'legal/release-signing-certificate.pem').read_text())
    if hashlib.sha256(certificate).hexdigest() != expected_cert:
        raise ValueError('Public certificate and fingerprint do not match')
    apk_dir = root / 'app/build/outputs/apk/release'
    metadata = json.loads((apk_dir / 'output-metadata.json').read_text())
    elements = metadata.get('elements', [])
    if metadata.get('variantName') != 'release' or len(elements) != 1:
        raise ValueError('Expected one release APK')
    element = elements[0]
    filename = element['outputFile']
    if filename != 'app-release.apk':
        raise ValueError('Expected the signed app-release.apk; unsigned builds cannot be packaged')
    version = app_version(root)
    if element.get('versionName') != version:
        raise ValueError('Stale APK output metadata; rebuild the release')
    code = app_version_code(root)
    if element.get('versionCode') != code:
        raise ValueError('Stale APK version code; rebuild the release')
    apk = apk_dir / filename
    verify_signature(apk, expected_cert, build_tool('apksigner', 'YORUBE_APKSIGNER'))
    verify_apk(root, apk, version, code, build_tool('aapt2', 'YORUBE_AAPT2'))

    output = root / 'app/build/outputs/distribution'
    files = {
        'yorube.apk': apk,
        'LICENSE.txt': root / 'LICENSE',
        'PRIVACY.txt': root / 'app/src/main/assets/legal/PRIVACY.txt',
        'THIRD_PARTY_NOTICES.txt': root / 'app/src/main/assets/legal/THIRD_PARTY_NOTICES.txt',
        'public_suffix_list.dat': root / 'app/src/main/assets/legal/public_suffix_list.dat',
        'release-signing-certificate.pem': root / 'legal/release-signing-certificate.pem',
        'release-signing-certificate.sha256': root / 'legal/release-signing-certificate.sha256',
    }
    output.mkdir(parents=True, exist_ok=True)
    allowed = set(files) | {'RELEASE.json', 'SHA256SUMS'}
    if any(p.name not in allowed or not p.is_file() for p in output.iterdir()):
        raise ValueError('Unexpected files in distribution directory; inspect and remove them before packaging')
    for name, source in files.items():
        shutil.copyfile(source, output / name)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    release = {'application_id': 'com.hermes.client', 'version_name': version, 'version_code': code,
               'source_commit': commit, 'certificate_sha256': expected_cert}
    (output / 'RELEASE.json').write_text(json.dumps(release, indent=2) + '\n')
    names = sorted(set(files) | {'RELEASE.json'})
    checksums = ''.join(hashlib.sha256((output / name).read_bytes()).hexdigest() + '  ' + name + '\n'
                        for name in names)
    (output / 'SHA256SUMS').write_text(checksums)
    print(f'Verified and packaged Yorube {version} ({code}): {output}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try:
        package(ROOT)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
