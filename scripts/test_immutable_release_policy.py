"""Fail-closed controls for DynaTech's manual and immutable publication workflow."""
from pathlib import Path
import unittest

WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/build.yml"
STEP = "      - name: Publish explicitly confirmed NEW immutable release\n"
CONDITION = (
    "        if: github.event_name == 'workflow_dispatch' && "
    "github.ref == 'refs/heads/main' && inputs.publish_release == true"
)


def verify(text: str) -> None:
    def require(expression: bool, why: str) -> None:
        if not expression:
            raise ValueError(why)

    require(text.count(STEP) == 1, "Missing unique manually gated publication step")
    require("  workflow_dispatch:\n    inputs:\n      publish_release:" in text,
            "Manual publication input missing")
    require("      publish_release:\n        description:" in text and
            "        type: boolean\n        default: false" in text,
            "Publication must default to false")
    require("      release_version:\n        description:" in text and
            "        type: string\n        default: ''" in text,
            "Explicit release version required")
    require("      - name: Verify manual-only immutable publishing policy\n"
            "        run: python3 -m unittest scripts.test_immutable_release_policy -v" in text,
            "Policy regression must execute during ordinary CI")
    require(text.count("gh release create ") == 1, "Exactly one release creation point")
    for unsafe in ("gh release upload", "gh release edit", "gh release delete", "--clobber"):
        require(unsafe not in text, "Unsafe publication operation: " + unsafe)
    step = text.split(STEP, 1)[1].split("\n      - name: ", 1)[0]
    require(CONDITION + "\n" in step, "Publication must require manual main and true input")
    require("startsWith(github.ref" not in step and "github.ref_name" not in step,
            "Publisher must not allow push/tag/default-branch bypasses")
    require("CONFIRMED_VERSION: ${{ inputs.release_version }}" in step,
            "Missing manually confirmed version")
    require('test "$CONFIRMED_VERSION" = "$LEGACY_VERSION"' in step,
            "Confirmed version must match current build")
    require('TAG="v${LEGACY_VERSION}"' in step and 'JAR="dist/${OUTPUT_NAME}"' in step,
            "Artifact identity or release tag changed")
    require('test -s "$JAR"' in step, "Empty JAR must not be published")
    require('if gh api "$endpoint"' in step and
            "if ! grep -Fq 'HTTP 404'" in step and
            "exit 1" in step,
            "API uncertainty and existing asset must fail closed")
    ref = 'refuse_existing "repos/${{ github.repository }}/git/ref/tags/${TAG}"'
    release = 'refuse_existing "repos/${{ github.repository }}/releases/tags/${TAG}"'
    require(ref in step and release in step, "Existing tag and release must both block")
    creator = 'gh release create "$TAG" "$JAR" --target "$GITHUB_SHA"'
    require(creator in step and step.index(ref) < step.index(release) < step.index(creator),
            "Only a missing tag and release can precede new publication")
    require("          sha256sum \"dist/${OUTPUT_NAME}\"" in text,
            "Existing exact JAR checksum must remain")
    require("          if max_major > 65:" in text,
            "Existing Java21 bytecode ceiling must remain")


class ImmutableReleasePolicyTest(unittest.TestCase):
    def setUp(self):
        self.source = WORKFLOW.read_text(encoding="utf-8")

    def mutate(self, old: str, new: str):
        self.assertEqual(1, self.source.count(old), "Mutation must affect exactly one target")
        with self.assertRaises(ValueError):
            verify(self.source.replace(old, new))

    def test_current_policy_passes(self):
        verify(self.source)

    def test_default_branch_push_bypass_is_rejected(self):
        self.mutate(CONDITION, "        if: github.ref_name == github.event.repository.default_branch")

    def test_tag_push_bypass_is_rejected(self):
        self.mutate(CONDITION, "        if: startsWith(github.ref, 'refs/tags/')")

    def test_unconditional_publisher_is_rejected(self):
        self.mutate(CONDITION, "        if: always()")

    def test_existing_asset_clobber_is_rejected(self):
        self.mutate('gh release create "$TAG"', 'gh release upload "$TAG" "$JAR" --clobber\n          gh release create "$TAG"')

    def test_missing_tag_probe_is_rejected(self):
        self.mutate('refuse_existing "repos/${{ github.repository }}/git/ref/tags/${TAG}"', "echo skip_tag_probe")

    def test_missing_release_probe_is_rejected(self):
        self.mutate('refuse_existing "repos/${{ github.repository }}/releases/tags/${TAG}"', "echo skip_release_probe")

    def test_accepting_any_api_error_is_rejected(self):
        self.mutate("if ! grep -Fq 'HTTP 404'", "if false")

    def test_missing_manual_version_verification_is_rejected(self):
        self.mutate('test "$CONFIRMED_VERSION" = "$LEGACY_VERSION"', "true")

    def test_missing_explicit_input_is_rejected(self):
        self.mutate('CONFIRMED_VERSION: ${{ inputs.release_version }}', "CONFIRMED_VERSION: fixed")

    def test_policy_tests_cannot_be_silently_disabled(self):
        self.mutate('run: python3 -m unittest scripts.test_immutable_release_policy -v', "run: echo disabled")

    def test_java21_ceiling_cannot_be_removed(self):
        self.mutate("if max_major > 65:", "if False:")

    def test_checksum_step_cannot_be_removed(self):
        self.mutate('          sha256sum "dist/${OUTPUT_NAME}"', '          echo checksum_disabled')

    def test_release_creation_must_reference_exact_commit(self):
        self.mutate('gh release create "$TAG" "$JAR" --target "$GITHUB_SHA"',
                    'gh release create "$TAG" "$JAR" --target master')


if __name__ == "__main__":
    unittest.main()
