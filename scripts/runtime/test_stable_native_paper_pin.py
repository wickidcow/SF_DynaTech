"""Fail-closed regression checks for DynaTech's exact stable Paper native fixture.

This test does not claim native transfer success; the existing workflow must
execute the real wireless/restart probe and retain its phase evidence.
"""
from copy import deepcopy
import json
from pathlib import Path
import unittest

from scripts.runtime.prepare_wireless_probe_inputs import validate_pins

ROOT = Path(__file__).resolve().parents[2]
PINS = ROOT / "scripts/runtime/wireless_probe_inputs.json"
HISTORICAL = ROOT / "scripts/runtime/paper_26_3_beta_159_historical.json"
DIGEST = "4ebaf5c9da98c654bb8f17245799943dfb05a285830be4307bfdf7bc9ca024bf"


class StablePaperNativePinTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pins = json.loads(PINS.read_text(encoding="utf-8"))
        cls.historical = json.loads(HISTORICAL.read_text(encoding="utf-8"))

    def test_exact_stable_263_identity_and_other_lanes(self):
        validate_pins(self.pins)
        pin = self.pins["paper"]["26.3"]
        self.assertEqual((pin["build"], pin["channel"]), (235, "STABLE"))
        self.assertEqual((pin["size"], pin["sha256"]), (55261122, DIGEST))
        self.assertEqual(pin["name"], "paper-26.3-235.jar")
        self.assertEqual(pin["metadata_url"],
                         "https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/235")
        self.assertEqual(pin["url"],
                         f"https://fill-data.papermc.io/v1/objects/{DIGEST}/paper-26.3-235.jar")
        self.assertEqual(self.pins["paper"]["1.21.11"]["build"], 132)
        self.assertEqual(self.pins["paper"]["26.2"]["build"], 132)

    def test_historical_beta_is_preserved_but_not_selected(self):
        self.assertTrue(self.historical["historical_only"])
        old = self.historical["paper"]
        self.assertEqual((old["build"], old["channel"]), (159, "BETA"))
        self.assertEqual(old["sha256"],
                         "2224a0b2b6b096ff4c429ad926e97977213e4f633e90cb3a49b5eeb82f94bab0")
        self.assertNotEqual(old["sha256"], self.pins["paper"]["26.3"]["sha256"])
        self.assertEqual(self.pins["core"]["source_commit"],
                         "f68544e47d8489704d0e9d34adc65f0114b07153")

    def test_beta_channel_is_rejected_without_fallback(self):
        mutated = deepcopy(self.pins)
        mutated["paper"]["26.3"]["channel"] = "BETA"
        with self.assertRaisesRegex(ValueError, "STABLE"):
            validate_pins(mutated)

    def test_unbound_download_url_is_rejected(self):
        mutated = deepcopy(self.pins)
        mutated["paper"]["26.3"]["url"] = mutated["paper"]["26.3"]["url"].replace(DIGEST, "0" * 64)
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            validate_pins(mutated)

    def test_mismatched_filename_is_rejected(self):
        mutated = deepcopy(self.pins)
        mutated["paper"]["26.3"]["name"] = "paper-26.3-latest.jar"
        with self.assertRaisesRegex(ValueError, "filename"):
            validate_pins(mutated)

    def test_changed_build_without_matching_official_metadata_is_rejected(self):
        mutated = deepcopy(self.pins)
        mutated["paper"]["26.3"]["build"] = 236
        with self.assertRaisesRegex(ValueError, "metadata URL"):
            validate_pins(mutated)

    def test_missing_official_metadata_url_is_rejected(self):
        mutated = deepcopy(self.pins)
        mutated["paper"]["26.3"]["metadata_url"] = ""
        with self.assertRaisesRegex(ValueError, "metadata URL"):
            validate_pins(mutated)

    def test_all_native_lanes_must_remain_stable(self):
        for version in ("1.21.11", "26.2"):
            with self.subTest(version=version):
                mutated = deepcopy(self.pins)
                mutated["paper"][version]["channel"] = "BETA"
                with self.assertRaisesRegex(ValueError, "STABLE"):
                    validate_pins(mutated)

    def test_workflow_runs_real_wireless_restart_and_preserves_immutable_publisher(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        self.assertIn("python3 -m unittest discover -s scripts/runtime "
                      "-p 'test_stable_native_paper_pin.py' -v", workflow)
        self.assertIn("python3 scripts/runtime/prepare_wireless_probe_inputs.py --out .native-inputs",
                      workflow)
        self.assertIn("scripts/runtime/run_wireless_transfer_probe.py", workflow)
        self.assertIn("--expect', 'fixed'", workflow)
        self.assertIn("if: github.event_name == 'workflow_dispatch'", workflow)
        self.assertNotIn("gh release upload", workflow)


if __name__ == "__main__":
    unittest.main()
