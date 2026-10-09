#!/usr/bin/env python3
"""Prepare pinned, verified Paper and core JARs for the native wireless-transfer probe.

The output directory must be new. Official Paper metadata is checked on every
invocation, including cache hits. No build is selected by "latest" or channel
fallback. Cache directories are read-only inputs; a cached file is copied only
after its size and SHA-256 match the pin, then checked again at its destination.

Example:
    python3 scripts/runtime/prepare_wireless_probe_inputs.py --out /tmp/probe-inputs

inputs.json contains a core object and a paper object keyed by Minecraft version.
Each object includes an absolute path, source identity, URL, size, and checksum.
The companion runner accepts --core inputs["core"]["path"] and
--paper inputs["paper"][version]["path"].
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlparse
import zipfile


PINS_FILE = Path(__file__).with_name("wireless_probe_inputs.json")
USER_AGENT = "Slimefun-Legacy-DynaTech-CI (https://github.com/wickidcow/SF_DynaTech)"
PAPER_VERSIONS = ("1.21.11", "26.2", "26.3")


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fetch(url: str, destination: Path) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.username or parsed.password or not parsed.hostname:
        raise ValueError(f"Expected an HTTPS download URL without credentials: {url}")
    temporary = destination.with_name(destination.name + ".download")
    if destination.exists() or temporary.exists():
        raise RuntimeError(f"Refusing to overwrite an existing download: {destination}")
    command = [
        "curl", "--fail", "--show-error", "--silent", "--location",
        "--proto", "=https", "--proto-redir", "=https", "--tlsv1.2",
        "--connect-timeout", "20", "--max-time", "240",
        "--retry", "2", "--retry-delay", "1", "--retry-max-time", "600",
        "--user-agent", USER_AGENT, "--output", str(temporary), url,
    ]
    try:
        subprocess.run(command, check=True, timeout=630)
        temporary.rename(destination)
    finally:
        temporary.unlink(missing_ok=True)


def matches_pin(path: Path, pin: dict) -> bool:
    return path.is_file() and path.stat().st_size == pin["size"] and sha256(path) == pin["sha256"]


def validate_jar(path: Path, pin: dict) -> None:
    if not matches_pin(path, pin):
        raise RuntimeError(f"Downloaded bytes do not match the size/SHA-256 pin: {path}")
    with zipfile.ZipFile(path) as archive:
        bad_entry = archive.testzip()
        if bad_entry is not None:
            raise RuntimeError(f"Invalid JAR CRC in {path}: {bad_entry}")


def prepare_jar(pin: dict, out: Path, cache_dirs: list[Path]) -> dict:
    destination = out / pin["name"]
    cache_source = None
    for cache_dir in cache_dirs:
        candidate = cache_dir / pin["name"]
        if matches_pin(candidate, pin):
            cache_source = candidate
            shutil.copyfile(candidate, destination)
            break
        if candidate.exists():
            print(f"Ignoring cache file with mismatched bytes: {candidate}", file=sys.stderr, flush=True)
    if cache_source is None:
        fetch(pin["url"], destination)
    validate_jar(destination, pin)
    result = {**pin, "path": str(destination), "origin": "cache" if cache_source else "download"}
    if cache_source:
        result["cache_source"] = str(cache_source)
    print(f"Validated {pin['name']} ({result['origin']})", flush=True)
    return result


def validate_core(core: dict) -> None:
    with zipfile.ZipFile(core["path"]) as archive:
        descriptor = archive.read("plugin.yml").decode("utf-8")
        expected_version = re.escape(core["version"])
        if not re.search(rf"(?m)^version:\s*['\"]?{expected_version}['\"]?\s*$", descriptor):
            raise RuntimeError("The published core descriptor has an unexpected version")
        properties = {}
        for line in archive.read("git.properties").decode("utf-8").splitlines():
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                properties[key] = value
        expected = {
            "git.build.version": core["version"],
            "git.commit.id": core["source_commit"],
            "git.source.commit": core["source_commit"],
        }
        for key, value in expected.items():
            if properties.get(key) != value:
                raise RuntimeError(f"The published core has unexpected {key}")


def prepare_paper(pin: dict, out: Path, cache_dirs: list[Path]) -> dict:
    metadata_path = out / f"paper-{pin['version']}-{pin['build']}-metadata.json"
    fetch(pin["metadata_url"], metadata_path)
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    download = metadata.get("downloads", {}).get("server:default", {})
    actual = {
        "build": metadata.get("id"),
        "channel": metadata.get("channel"),
        "name": download.get("name"),
        "url": download.get("url"),
        "size": download.get("size"),
        "sha256": download.get("checksums", {}).get("sha256"),
    }
    for key, value in actual.items():
        if value != pin[key]:
            raise RuntimeError(f"Official Paper {pin['version']} metadata differs from the pinned {key}")
    result = prepare_jar(pin, out, cache_dirs)
    result["metadata_path"] = str(metadata_path)
    result["metadata_sha256"] = sha256(metadata_path)
    return result


def validate_pins(pins: dict) -> None:
    if pins.get("schema") != 1 or set(pins.get("paper", {})) != set(PAPER_VERSIONS):
        raise ValueError("Unexpected native-probe input manifest schema or Paper lanes")
    for pin in [pins["core"], *pins["paper"].values()]:
        if Path(pin["name"]).name != pin["name"] or not pin["name"].endswith(".jar"):
            raise ValueError("Pinned artifact names must be JAR basenames")
        if not isinstance(pin["size"], int) or pin["size"] <= 0:
            raise ValueError("Pinned artifacts must have a positive byte size")
        if not re.fullmatch(r"[0-9a-f]{64}", pin["sha256"]):
            raise ValueError("Pinned artifacts must have a SHA-256 digest")
    for version, pin in pins["paper"].items():
        expected_metadata = (
            f"https://fill.papermc.io/v3/projects/paper/versions/{version}/builds/{pin['build']}"
        )
        if pin["project"] != "paper" or pin["version"] != version or pin["metadata_url"] != expected_metadata:
            raise ValueError("Pinned Paper identity does not match its official metadata URL")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="New output directory; must not already exist")
    parser.add_argument("--paper-version", choices=PAPER_VERSIONS, help="Prepare one lane; default is all three")
    parser.add_argument("--cache-dir", type=Path, action="append", default=[],
                        help="Read-only directory of previously downloaded JARs; may be supplied more than once")
    args = parser.parse_args()
    if not shutil.which("curl"):
        parser.error("curl is required for HTTPS downloads")
    pins_path = PINS_FILE.resolve()
    pins = json.loads(pins_path.read_text(encoding="utf-8"))
    validate_pins(pins)
    out = args.out.resolve()
    if out.exists():
        parser.error("The output directory must not exist; previous evidence is never overwritten")
    cache_dirs = [path.resolve() for path in args.cache_dir]
    versions = [args.paper_version] if args.paper_version else list(PAPER_VERSIONS)
    out.mkdir(parents=True)
    with ThreadPoolExecutor(max_workers=4) as executor:
        core_future = executor.submit(prepare_jar, pins["core"], out, cache_dirs)
        paper_futures = {
            version: executor.submit(prepare_paper, pins["paper"][version], out, cache_dirs)
            for version in versions
        }
        core = core_future.result()
        validate_core(core)
        paper = {version: future.result() for version, future in paper_futures.items()}
    result = {
        "schema": 1,
        "result": "PASS",
        "prepared_at": datetime.now(timezone.utc).isoformat(),
        "pins_path": str(pins_path),
        "pins_sha256": sha256(pins_path),
        "core": core,
        "paper": paper,
    }
    result_path = out / "inputs.json"
    result_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": "PASS", "inputs": str(result_path), "paper_versions": versions}), flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(f"Native probe input preparation failed: {error}", file=sys.stderr)
        sys.exit(1)
