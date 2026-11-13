#!/usr/bin/env python3
"""Gate publishing on the tagged version and main ancestry; dispatch only builds."""
import argparse
import os
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def app_version(root):
    source = (root / 'app/build.gradle.kts').read_text()
    match = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', source, re.MULTILINE)
    if not match:
        raise ValueError('Cannot read the application version')
    return match.group(1)


def app_version_code(root):
    source = (root / 'app/build.gradle.kts').read_text()
    match = re.search(r'^\s*versionCode\s*=\s*(\d+)', source, re.MULTILINE)
    if not match:
        raise ValueError('Cannot read the application version code')
    return int(match.group(1))


def verify(root, event, ref):
    version = app_version(root)
    if event == 'workflow_dispatch':
        if ref not in ('refs/heads/main', 'refs/heads/develop'):
            raise ValueError('Verification builds must use main or develop')
        return version
    if event != 'push' or ref != 'refs/tags/v' + version:
        raise ValueError('The release tag must exactly match v' + version)
    result = subprocess.run(
        ['git', 'merge-base', '--is-ancestor', 'HEAD', 'refs/remotes/origin/main'],
        cwd=root, capture_output=True,
    )
    if result.returncode:
        raise ValueError('Release commit must already belong to origin/main')
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event', default=os.getenv('GITHUB_EVENT_NAME', ''))
    parser.add_argument('--ref', default=os.getenv('GITHUB_REF', ''))
    args = parser.parse_args()
    try:
        version = verify(ROOT, args.event, args.ref)
    except ValueError as error:
        parser.exit(1, str(error) + '\n')
    print(f'Verified {args.ref}: {version} ({args.event})')


if __name__ == '__main__':
    main()
