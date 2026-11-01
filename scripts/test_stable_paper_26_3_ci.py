"""Fail closed if DynaTech's 26.3 compiler lane regresses to an alpha or floating API."""
from pathlib import Path
import unittest

WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/build.yml"
STEP = "      - name: Verify Paper 26.3 stable compilation\n"
PIN = "26.3.build.205-stable"
TEST_COMMAND = "run: python3 -m unittest scripts.test_stable_paper_26_3_ci -v"


def verify(text: str) -> None:
    if text.count(STEP) != 1:
        raise ValueError("Exactly one stable 26.3 compile lane is required")
    lane = text.split(STEP, 1)[1].split("\n      - name: ", 1)[0]
    if lane.count("-Dpaper.version=") != 1 or f"-Dpaper.version={PIN}" not in lane:
        raise ValueError("26.3 compilation must use the exact pinned stable API")
    if "mvn -B clean verify --file pom.xml" not in lane:
        raise ValueError("The stable 26.3 lane must run the Maven verification lifecycle")
    if text.count(TEST_COMMAND) != 1:
        raise ValueError("The stable API policy must execute in ordinary CI")
    if "Verify native wireless transfers and restart persistence" not in text:
        raise ValueError("Existing native transfer and restart verification must remain")


class StablePaper263ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = WORKFLOW.read_text(encoding="utf-8")

    def test_current_workflow_uses_exact_stable_api(self):
        verify(self.source)

    def test_alpha_api_is_rejected(self):
        self.assertEqual(1, self.source.count(PIN))
        with self.assertRaises(ValueError):
            verify(self.source.replace(PIN, "26.3-rc-3.build.1-alpha"))

    def test_floating_api_is_rejected(self):
        with self.assertRaises(ValueError):
            verify(self.source.replace(PIN, "26.3.build.+"))

    def test_missing_maven_verification_is_rejected(self):
        with self.assertRaises(ValueError):
            verify(self.source.replace(
                "mvn -B clean verify --file pom.xml\n          -Dpaper.version=" + PIN,
                "mvn -B help:effective-pom --file pom.xml\n          -Dpaper.version=" + PIN,
            ))

    def test_policy_cannot_be_silently_disabled(self):
        with self.assertRaises(ValueError):
            verify(self.source.replace(TEST_COMMAND, "run: echo skipped"))

    def test_native_restart_lane_cannot_be_removed(self):
        with self.assertRaises(ValueError):
            verify(self.source.replace(
                "Verify native wireless transfers and restart persistence",
                "Native lane removed",
            ))


if __name__ == "__main__":
    unittest.main()
