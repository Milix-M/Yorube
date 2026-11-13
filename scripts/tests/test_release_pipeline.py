from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import package_release
import verify_release_ref


class ReleaseRefTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'app').mkdir()
        (self.root / 'app/build.gradle.kts').write_text('versionName = "0.9.3"\nversionCode = 8\n')
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Release QA')
        self.git('config', 'user.email', 'release-qa@example.invalid')
        self.git('add', '.')
        self.git('commit', '-m', 'Initial main')
        self.git('update-ref', 'refs/remotes/origin/main', 'HEAD')

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.root, check=True, capture_output=True, text=True)

    def develop_commit(self):
        self.git('checkout', '-b', 'develop')
        (self.root / 'change.txt').write_text('Release change\n')
        self.git('add', '.')
        self.git('commit', '-m', 'Develop changes')
        self.git('update-ref', 'refs/remotes/origin/develop', 'HEAD')

    def test_main_merge_commit_is_releasable_even_when_not_in_develop(self):
        self.develop_commit()
        self.git('checkout', 'main')
        self.git('merge', '--no-ff', 'develop', '-m', 'Release merge')
        self.git('update-ref', 'refs/remotes/origin/main', 'HEAD')
        old_guard = subprocess.run(['git', 'merge-base', '--is-ancestor', 'HEAD', 'origin/develop'], cwd=self.root)
        self.assertNotEqual(old_guard.returncode, 0)
        self.assertEqual(verify_release_ref.verify(self.root, 'push', 'refs/tags/v0.9.3'), '0.9.3')

    def test_unmerged_develop_cannot_be_published(self):
        self.develop_commit()
        with self.assertRaisesRegex(ValueError, 'origin/main'):
            verify_release_ref.verify(self.root, 'push', 'refs/tags/v0.9.3')

    def test_tag_must_match_application_version(self):
        with self.assertRaisesRegex(ValueError, 'exactly match'):
            verify_release_ref.verify(self.root, 'push', 'refs/tags/v0.9.2')

    def test_manual_verification_accepts_main_and_develop_but_not_other_refs(self):
        for branch in ('main', 'develop'):
            self.assertEqual(verify_release_ref.verify(self.root, 'workflow_dispatch', 'refs/heads/' + branch), '0.9.3')
        with self.assertRaisesRegex(ValueError, 'main or develop'):
            verify_release_ref.verify(self.root, 'workflow_dispatch', 'refs/heads/unreviewed')


class ReleaseApkTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        legal = self.root / 'app/src/main/assets/legal'
        legal.mkdir(parents=True)
        self.apk = self.root / 'yorube.apk'
        with zipfile.ZipFile(self.apk, 'w') as archive:
            for name in ('LICENSES.txt', 'THIRD_PARTY_NOTICES.txt', 'public_suffix_list.dat'):
                (legal / name).write_text('Current legal content for ' + name)
                archive.writestr('assets/legal/' + name, (legal / name).read_bytes())
        self.badging = "package: name='com.hermes.client' versionCode='8' versionName='0.9.3'\n"

    def test_invalid_signature_and_wrong_signer_are_rejected(self):
        with patch('package_release.subprocess.run', return_value=subprocess.CompletedProcess([], 1, '', '')):
            with self.assertRaisesRegex(ValueError, 'signature verification failed'):
                package_release.verify_signature(self.apk, 'a' * 64, 'apksigner')
        output = 'Signer #1 certificate SHA-256 digest: ' + 'b' * 64 + '\n'
        with patch('package_release.subprocess.run', return_value=subprocess.CompletedProcess([], 0, output, '')):
            with self.assertRaisesRegex(ValueError, 'does not match'):
                package_release.verify_signature(self.apk, 'a' * 64, 'apksigner')

    def test_debug_signing_key_is_rejected_even_if_fingerprint_matches(self):
        output = 'Signer #1 certificate DN: CN=Android Debug,O=Android,C=US\nSigner #1 certificate SHA-256 digest: ' + 'a' * 64 + '\n'
        with patch('package_release.subprocess.run', return_value=subprocess.CompletedProcess([], 0, output, '')):
            with self.assertRaisesRegex(ValueError, 'debug signing key'):
                package_release.verify_signature(self.apk, 'a' * 64, 'apksigner')

    def test_sdk_range_signers_must_all_match_the_same_release_certificate(self):
        output = 'Number of signers: 1\n' + ''.join(
            f'Signer (minSdkVersion={minimum}, maxSdkVersion={maximum}) certificate SHA-256 digest: ' + 'a' * 64 + '\n'
            for minimum, maximum in [(26, 32), (33, 2147483647)])
        with patch('package_release.subprocess.run', return_value=subprocess.CompletedProcess([], 0, output, '')):
            package_release.verify_signature(self.apk, 'a' * 64, 'apksigner')
        output += 'Signer #2 certificate SHA-256 digest: ' + 'b' * 64 + '\n'
        with patch('package_release.subprocess.run', return_value=subprocess.CompletedProcess([], 0, output, '')):
            with self.assertRaisesRegex(ValueError, 'does not match'):
                package_release.verify_signature(self.apk, 'a' * 64, 'apksigner')

    def test_build_tools_37_scheme_specific_signer_output(self):
        output = 'Number of signers: 1\nV2 Signer: certificate DN: CN=Yorube\nV2 Signer: certificate SHA-256 digest: ' + 'a' * 64 + '\n'
        with patch('package_release.subprocess.run', return_value=subprocess.CompletedProcess([], 0, output, '')):
            package_release.verify_signature(self.apk, 'a' * 64, 'apksigner')

    def test_wrong_version_and_debuggable_apks_are_rejected(self):
        cases = [(self.badging.replace("versionCode='8'", "versionCode='7'"), 'version'),
                 (self.badging + 'application-debuggable\n', 'debuggable')]
        for badging, message in cases:
            with self.subTest(message=message):
                with patch('package_release.subprocess.run', return_value=subprocess.CompletedProcess([], 0, badging, '')):
                    with self.assertRaisesRegex(ValueError, message):
                        package_release.verify_apk(self.root, self.apk, '0.9.3', 8, 'aapt2')

    def test_apk_legal_contents_must_match_generated_files(self):
        with patch('package_release.subprocess.run', return_value=subprocess.CompletedProcess([], 0, self.badging, '')):
            package_release.verify_apk(self.root, self.apk, '0.9.3', 8, 'aapt2')
            (self.root / 'app/src/main/assets/legal/THIRD_PARTY_NOTICES.txt').write_text('Updated notices')
            with self.assertRaisesRegex(ValueError, 'outdated legal document'):
                package_release.verify_apk(self.root, self.apk, '0.9.3', 8, 'aapt2')

    def test_privacy_notice_cannot_be_reintroduced_in_the_apk(self):
        with zipfile.ZipFile(self.apk, 'a') as archive:
            archive.writestr('assets/legal/PRIVACY.txt', 'Obsolete notice')
        with patch('package_release.subprocess.run', return_value=subprocess.CompletedProcess([], 0, self.badging, '')):
            with self.assertRaisesRegex(ValueError, 'must not bundle a privacy notice'):
                package_release.verify_apk(self.root, self.apk, '0.9.3', 8, 'aapt2')


if __name__ == '__main__':
    unittest.main()
