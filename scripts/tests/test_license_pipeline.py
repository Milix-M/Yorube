import copy
import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("collector", ROOT / "scripts/collect_licenses.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)

def archive(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as z:
        for name, content in files.items():
            z.writestr(name, content)
    return output.getvalue()

class LicensePipelineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.binary = self.root / "example.aar"
        self.binary.write_bytes(archive({"classes.jar": archive({"META-INF/NOTICE": "Copyright Example"})}))
        self.artifacts = [{"coordinate": "example:library:1.0", "file": str(self.binary)}]
        self.metadata = {
            "libraries": [{"uniqueId": "example:library", "artifactVersion": "1.0", "name": "Example", "licenses": ["Apache-2.0"]}],
            "licenses": {"Apache-2.0": {"name": "Apache 2.0", "content": (ROOT / "third_party/licenses/Apache-2.0.txt").read_text()}},
        }
        self.supplement = {"artifact_pins": [], "files": [], "additional_components": []}

    def collect(self):
        return collector.collect(self.metadata, self.artifacts, self.supplement, self.root)

    def test_nested_aar_notice_is_retained_and_compile_only_metadata_is_excluded(self):
        other = copy.deepcopy(self.metadata["libraries"][0])
        other["uniqueId"] = "example:compile-only"
        self.metadata["libraries"].append(other)
        inventory, licenses, exported = self.collect()
        self.assertEqual(len(inventory["dependencies"]), 1)
        self.assertEqual(len(exported["libraries"]), 1)
        notices = inventory["dependencies"][0]["embedded_legal_files"]
        self.assertEqual(notices[0]["path"], "classes.jar!/META-INF/NOTICE")
        self.assertEqual(notices[0]["text"], "Copyright Example")
        self.assertIn("Apache-2.0.txt", licenses)

    def test_missing_runtime_dependency_is_fatal(self):
        self.metadata["libraries"].clear()
        with self.assertRaisesRegex(ValueError, "Uncollected runtime dependency"):
            self.collect()

    def test_unidentified_license_is_fatal(self):
        self.metadata["libraries"][0]["licenses"] = []
        with self.assertRaisesRegex(ValueError, "No license"):
            self.collect()

    def test_unapproved_license_is_fatal(self):
        self.metadata["libraries"][0]["licenses"] = ["NOASSERTION"]
        with self.assertRaisesRegex(ValueError, "Unrecognized/unapproved license"):
            self.collect()

    def test_missing_full_license_text_is_fatal(self):
        self.metadata["licenses"]["Apache-2.0"].pop("content")
        with self.assertRaisesRegex(ValueError, "Missing license text"):
            self.collect()

    def test_dependency_upgrade_invalidates_embedded_component_evidence(self):
        self.supplement["artifact_pins"] = [{"coordinate": "example:library:0.9", "sha256": "old"}]
        with self.assertRaisesRegex(ValueError, "Re-review bundled-component evidence"):
            self.collect()

    def test_changed_supplemental_file_is_fatal(self):
        (self.root / "NOTICE.txt").write_text("changed")
        self.supplement["files"] = [{"path": "NOTICE.txt", "sha256": "reviewed-old-hash"}]
        with self.assertRaisesRegex(ValueError, "Supplemental notice/source changed"):
            self.collect()

    def test_generation_is_stable_and_verification_rejects_stale_output(self):
        first = collector.json_text(self.collect()[0])
        self.assertEqual(first, collector.json_text(self.collect()[0]))
        path = self.root / "generated.json"
        collector.write_outputs({path: first}, False)
        collector.write_outputs({path: first}, True)
        path.write_text("stale")
        with self.assertRaisesRegex(ValueError, "Outdated generated license file"):
            collector.write_outputs({path: first}, True)

if __name__ == "__main__":
    unittest.main()
