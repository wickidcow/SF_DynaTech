#!/usr/bin/env python3
"""Run real DynaTech wireless transfers in a new disposable Paper directory.

Supplied JARs are never rebuilt or rewritten. The probe invokes the registered
output ticker and real core persistence. A baseline run must reproduce the
exact transfer and persistence failures while its ordinary controls pass.
"""

from __future__ import annotations

import argparse
import atexit
import hashlib
import json
import os
from pathlib import Path
import shutil
import re
import signal
import socket
import subprocess
import sys
import time
from urllib.parse import urlparse
import zipfile


NEGATIVE_CONTROLS = {
    "persist-empty-output", "persist-merge-output", "locked-input", "locked-output",
    "absent-input-menu", "absent-output-menu",
    "off-thread-output", "unavailable-world-link", "malformed-link",
    "insertion-denied", "insertion-remainder",
}
ORDINARY_CONTROLS = {
    "normal-empty-output", "normal-merge-output", "source-slot-order",
    "insufficient-input-energy", "insufficient-output-energy", "full-output",
    "distributed-capacity-waits", "unlinked-output", "non-input-endpoint", "missing-input",
    "legacy-coordinate-link", "async-chunk-reload", "pending-input", "pending-output",
}
RESTART_NEGATIVE_CONTROLS = {"restart-persist-empty-output", "restart-persist-merge-output"}
RESTART_ORDINARY_CONTROLS = {"restart-no-transfer-control", "restart-item-identity-control"}
EXPECTED_CASES = NEGATIVE_CONTROLS | ORDINARY_CONTROLS
EXPECTED_RESTART_CASES = RESTART_NEGATIVE_CONTROLS | RESTART_ORDINARY_CONTROLS
EXPECTED_TICKERS = {"DYNATECH_WIRELESS_ITEM_INPUT": True, "DYNATECH_WIRELESS_ITEM_OUTPUT": True}
PINS_FILE = Path(__file__).with_name("wireless_probe_inputs.json")


def inspect_log(path: Path) -> dict:
    """Classify concrete plugin failures separately from retained host/network diagnostics."""
    clean = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", path.read_text(encoding="utf-8", errors="replace"))
    lines = clean.splitlines()
    failures = []
    diagnostics = []
    for index, line in enumerate(lines):
        task = re.search(
            r"(?:Task #\d+ for (?:DynaTech|Slimefun|DynaTechWirelessProbe) v.* generated an exception|"
            r"Plugin (?:DynaTech|Slimefun|DynaTechWirelessProbe) v\S+ generated an exception while executing task \d+)",
            line,
        )
        if task:
            context = "\n".join(lines[index:index + 28])
            failures.append({"kind": "scheduled-task", "line": line.strip(), "trace": context})
        elif re.search(r"(?:Error occurred while enabling|Error occurred while disabling|Could not load plugin|Failed to load plugin|"
                       r"Could not pass event .* to (?:DynaTech|Slimefun|DynaTechWirelessProbe)|"
                       r"NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError|"
                       r"ExceptionInInitializerError|UnsupportedClassVersionError|ClassNotFoundException|"
                       r"Exception thrown while executing write task|An Exception occurred while saving a backpack|"
                       r"Failed to persist Slimefun block inventory|Could not stage backpack|"
                       r"acknowledgement-aware inventory save chain|shutdown is not clean|A error occurred in database thread|"
                       r"Exception while trying to .*energy-charge)", line) or re.search(
                           r"(?:ERROR|SEVERE)\]:\s*\[(?:Slimefun|DynaTech|DynaTechWirelessProbe|SF-[^\]]+)\]", line):
            failures.append({"kind": "plugin-linkage-storage-enable", "line": line.strip()})
        elif re.search(r"(?:WARN|ERROR|SEVERE)\]", line) and not re.search(r"\bat (?:java\.|org\.|io\.|com\.|audit\.)", line):
            diagnostics.append(line.strip())
    return {"plugin_failures": failures, "other_diagnostics": diagnostics}


def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def jar_input(path: Path) -> dict:
    if not path.is_file():
        raise RuntimeError(f"Missing supplied JAR: {path}")
    with zipfile.ZipFile(path) as archive:
        bad = archive.testzip()
        if bad is not None:
            raise RuntimeError(f"Corrupt supplied JAR {path}: {bad}")
    return {"path": str(path), "size": path.stat().st_size, "sha256": digest(path)}


def verify_pinned_inputs(supplied: dict) -> dict:
    """The runner independently ties the supplied core and Paper bytes to the reviewed pins."""
    pins = json.loads(PINS_FILE.read_text(encoding="utf-8"))
    core = pins["core"]
    if any(supplied["core"][key] != core[key] for key in ("size", "sha256")):
        raise RuntimeError("Supplied core does not match the pinned public release bytes")
    matches = [pin for pin in pins["paper"].values()
               if all(supplied["paper"][key] == pin[key] for key in ("size", "sha256"))]
    if len(matches) != 1:
        raise RuntimeError("Supplied Paper does not match exactly one pinned runtime lane")
    return {"pins_path": str(PINS_FILE.resolve()), "pins_sha256": digest(PINS_FILE),
            "core": core, "paper": matches[0]}


def require_metrics_disabled(config: Path) -> None:
    if not config.is_file():
        raise RuntimeError("Disposable native runs require bStats disabled before every server boot")
    enabled = re.findall(r"(?m)^enabled:[ \t]*([^#\r\n]*)(?:#.*)?$", config.read_text(encoding="utf-8"))
    if [value.strip() for value in enabled] != ["false"]:
        raise RuntimeError("Disposable native runs require exactly one bStats enabled: false setting")


def inspect_prerequisites(report: dict, phase: str) -> list[str]:
    """Require actual metadata reads and an observed idle window in nonfatal reports."""
    problems: list[str] = []
    expected_stages = ([("initial-seed", 2)] * 5 + [("before-stop", 8)]
                       if phase == "run" else [("restart", 1)] * 8)
    stages = report.get("metadata_readbacks")
    locations_by_stage: list[list[str]] = []
    if not isinstance(stages, list) or len(stages) != len(expected_stages):
        problems.append(f"Wrong {phase} metadata readback stage count")
    if isinstance(stages, list):
        for index, stage in enumerate(stages):
            label = f"{phase} metadata readback {index + 1}"
            if not isinstance(stage, dict) or index >= len(expected_stages):
                problems.append(f"Malformed or extra {label}")
                continue
            expected_stage, expected_count = expected_stages[index]
            observations = stage.get("observations")
            if (stage.get("stage") != expected_stage or not isinstance(observations, list)
                    or len(observations) != expected_count):
                problems.append(f"Wrong stage or observation count: {label}")
                continue
            locations: list[str] = []
            for observation in observations:
                if not isinstance(observation, dict):
                    problems.append(f"Malformed observation: {label}")
                    continue
                location = observation.get("location")
                expected_id = observation.get("expected_id")
                expected = observation.get("expected_values")
                actual = observation.get("actual_values")
                if not isinstance(location, str) or not location:
                    problems.append(f"Missing observation location: {label}")
                else:
                    locations.append(location)
                valid_expected = (isinstance(expected, dict) and bool(expected)
                                  and all(isinstance(key, str) and isinstance(value, str)
                                          for key, value in expected.items()))
                valid_actual = (isinstance(actual, dict)
                                and all(isinstance(key, str) and isinstance(value, str)
                                        for key, value in actual.items()))
                if (observation.get("passed") is not True or observation.get("mismatches") != []
                        or type(observation.get("actual_record_count")) is not int
                        or observation["actual_record_count"] != 1
                        or not isinstance(expected_id, str) or expected_id not in EXPECTED_TICKERS
                        or observation.get("actual_id") != expected_id
                        or not valid_expected or not valid_actual or actual != expected):
                    problems.append(f"Failed or inconsistent actual-database observation: {label}")
                if valid_expected:
                    if (not re.fullmatch(r"\d+", expected.get("energy-charge", ""))
                            or not expected.get("wireless-probe-sentinel")):
                        problems.append(f"Missing explicit charge or sentinel row: {label}")
                    if expected_id == "DYNATECH_WIRELESS_ITEM_OUTPUT" and not expected.get("wireless-input-location"):
                        problems.append(f"Missing stored output link: {label}")
                    if expected_id == "DYNATECH_WIRELESS_ITEM_INPUT" and "wireless-input-location" in expected:
                        problems.append(f"Unexpected stored input link: {label}")
            if len(set(locations)) != len(locations):
                problems.append(f"Duplicate observation location: {label}")
            locations_by_stage.append(locations)
        if len(locations_by_stage) == len(expected_stages):
            initial = [location for group in locations_by_stage[:-1] for location in group] if phase == "run" else []
            if phase == "run" and len(set(initial)) != len(initial):
                problems.append("Duplicate initial metadata seed locations")
            if phase == "run" and set(locations_by_stage[-1]) != set(initial[2:]):
                problems.append("Before-stop metadata locations differ from the four seeded restart pairs")
            if phase == "read":
                restarted = [location for group in locations_by_stage for location in group]
                if len(set(restarted)) != 8:
                    problems.append("Restart metadata does not cover eight distinct machines")

    quiet = report.get("github_background_quiescence")
    if not isinstance(quiet, dict):
        problems.append(f"Missing {phase} GitHub background quiescence observation")
    else:
        counts = ("observed_quiet_ticks", "enabled_at_tick", "finished_at_tick")
        valid_counts = all(type(quiet.get(key)) is int for key in counts)
        if (quiet.get("observed") is not True or quiet.get("initial_eligibility_ticks") != 620
                or quiet.get("quiet_window_ticks") != 40 or not valid_counts):
            problems.append(f"Malformed or unobserved {phase} GitHub background quiescence")
        elif (quiet["observed_quiet_ticks"] < 40
              or quiet["finished_at_tick"] - quiet["enabled_at_tick"] < 620
              or quiet["finished_at_tick"] - quiet["enabled_at_tick"] - quiet["observed_quiet_ticks"] < 620
              or quiet["observed_quiet_ticks"] > quiet["finished_at_tick"] - quiet["enabled_at_tick"]):
            problems.append(f"Insufficient observed {phase} GitHub background quiet window")
    return problems


def inspect_report(report: dict, phase: str, expected_names: set[str]) -> tuple[dict, list[str]]:
    """Reject missing, duplicate, empty or internally inconsistent case evidence."""
    problems: list[str] = []
    cases: dict = {}
    rows = report.get("cases")
    if report.get("phase") != phase or not isinstance(rows, list):
        return cases, [f"Invalid {phase} report phase or case list"]
    if report.get("fatal") is not None:
        problems.append(f"Fatal {phase} helper failure: {report.get('fatal')}")
    else:
        problems.extend(inspect_prerequisites(report, phase))
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("name"), str):
            problems.append(f"Malformed {phase} case row")
            continue
        name = row["name"]
        if name in cases:
            problems.append(f"Duplicate {phase} case: {name}")
        cases[name] = row
        assertions = row.get("assertions")
        if (not isinstance(assertions, list) or not assertions
                or any(not isinstance(assertion, dict) or type(assertion.get("passed")) is not bool
                       for assertion in assertions)):
            problems.append(f"Missing or malformed assertion evidence: {name}")
            continue
        expected_pass = all(assertion["passed"] for assertion in assertions)
        if type(row.get("passed")) is not bool or row["passed"] != expected_pass:
            problems.append(f"Inconsistent case pass flag: {name}")
    if set(cases) != expected_names or len(rows) != len(expected_names):
        problems.append(f"Wrong {phase} case set: missing={sorted(expected_names - set(cases))}; "
                        f"extra={sorted(set(cases) - expected_names)}")
    expected_pass = bool(rows) and report.get("fatal") is None and all(row.get("passed") is True for row in cases.values())
    if type(report.get("passed")) is not bool or report["passed"] != expected_pass:
        problems.append(f"Inconsistent {phase} aggregate pass flag")
    return cases, problems


def free_port() -> int:
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        return server.getsockname()[1]


def java_proxy_arguments() -> list[str]:
    """Honor an existing credentials-free environment proxy; never invent a route."""
    value = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    if not value:
        return []
    proxy = urlparse(value)
    if proxy.scheme != "http" or not proxy.hostname or not proxy.port or proxy.username or proxy.password:
        return []
    arguments = [f"-Dhttps.proxyHost={proxy.hostname}", f"-Dhttps.proxyPort={proxy.port}",
                 f"-Dhttp.proxyHost={proxy.hostname}", f"-Dhttp.proxyPort={proxy.port}",
                 "-Dhttp.nonProxyHosts=localhost|127.*|[::1]"]
    system_trust = Path("/etc/ssl/certs/java/cacerts")
    if system_trust.is_file():
        arguments.append(f"-Djavax.net.ssl.trustStore={system_trust}")
    return arguments


def capture_threads(work: Path, java: Path, label: str, process: subprocess.Popen) -> None:
    """Leave a bounded startup/fixture diagnostic before the launcher times out."""
    target = work / "evidence" / f"{label}.threads.txt"
    jcmd = java.with_name("jcmd")
    if not jcmd.is_file():
        return
    try:
        result = subprocess.run([str(jcmd), str(process.pid), "Thread.print", "-l"],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=8)
        target.write_text(result.stdout, encoding="utf-8")
    except (OSError, subprocess.TimeoutExpired) as failure:
        target.write_text(f"Thread diagnostic unavailable: {failure}\n", encoding="utf-8")


def server_cycle(work: Path, java: Path, label: str, action: str | None, timeout: int) -> dict:
    log_path = work / "evidence" / f"{label}.log"
    result_path = work / "plugins" / "DynaTechWirelessProbe" / f"{action}-result.json" if action else None
    if result_path and result_path.exists():
        raise RuntimeError(f"Refusing stale phase evidence: {result_path}")
    metrics_config = work / "plugins" / "bStats" / "config.yml"
    require_metrics_disabled(metrics_config)
    command = [str(java), *java_proxy_arguments(), "-Xms512M", "-Xmx1536M", "-jar", "server.jar", "--nogui"]
    print(f"[{label}] Starting a disposable Paper process", flush=True)
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=work, stdin=subprocess.PIPE, stdout=log,
                                   stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + timeout
            diagnostic_at = time.monotonic() + min(45, timeout / 2)
            diagnosed = False
            ready = False
            while process.poll() is None and time.monotonic() < deadline:
                text = log_path.read_text(encoding="utf-8", errors="replace")
                if "Done (" in text:
                    ready = True
                    break
                if "Failed to start the minecraft server" in text:
                    break
                if not diagnosed and time.monotonic() > diagnostic_at:
                    capture_threads(work, java, f"{label}-startup", process)
                    diagnosed = True
                time.sleep(0.5)
            if not ready:
                if process.poll() is None:
                    process.send_signal(signal.SIGQUIT)
                    time.sleep(0.5)
                raise RuntimeError(f"Paper did not become ready; see {log_path}")
            startup_problem = None
            startup_checks = {"logs": inspect_log(log_path), "remapped_jars": []}
            for remapped in sorted((work / "plugins" / ".paper-remapped").glob("*.jar")):
                try:
                    startup_checks["remapped_jars"].append(jar_input(remapped))
                except (RuntimeError, zipfile.BadZipFile) as failure:
                    startup_problem = f"Paper-generated remap is invalid: {remapped}: {failure}"
            if startup_checks["logs"]["plugin_failures"]:
                startup_problem = startup_problem or f"Plugin startup failed; see {log_path}"
            (work / "evidence" / f"{label}-startup-checks.json").write_text(
                json.dumps({**startup_checks, "fatal": startup_problem}, indent=2) + "\n", encoding="utf-8")
            if action and not startup_problem:
                assert process.stdin is not None
                process.stdin.write(f"dynatechwirelessprobe {action}\n")
                process.stdin.flush()
                deadline = time.monotonic() + timeout
                diagnostic_at = time.monotonic() + min(90, timeout / 2)
                diagnosed = False
                while process.poll() is None and time.monotonic() < deadline:
                    if result_path and result_path.is_file():
                        break
                    if "Command exception: /dynatechwirelessprobe" in log_path.read_text(encoding="utf-8", errors="replace"):
                        startup_problem = f"Native helper command failed; see {log_path}"
                        break
                    if not diagnosed and time.monotonic() > diagnostic_at:
                        capture_threads(work, java, f"{label}-probe", process)
                        diagnosed = True
                    time.sleep(0.2)
                if not startup_problem and (not result_path or not result_path.is_file()):
                    if process.poll() is None:
                        process.send_signal(signal.SIGQUIT)
                        time.sleep(0.5)
                    raise RuntimeError(f"Probe did not produce {action} evidence; see {log_path}")
            assert process.stdin is not None
            process.stdin.write("stop\n")
            process.stdin.flush()
            try:
                code = process.wait(timeout=60)
            except subprocess.TimeoutExpired as failure:
                raise RuntimeError(f"Paper did not shut down normally; see {log_path}") from failure
            if code != 0:
                raise RuntimeError(f"Paper exited {code}; see {log_path}")
            if startup_problem:
                raise RuntimeError(startup_problem)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            if process.stdin:
                process.stdin.close()
    evidence = {"label": label, "log": str(log_path), "log_sha256": digest(log_path), "exit_code": 0}
    require_metrics_disabled(metrics_config)
    evidence["bstats_enabled"] = False
    evidence["log_checks"] = inspect_log(log_path)
    if result_path:
        evidence["result"] = json.loads(result_path.read_text(encoding="utf-8"))
        copied = work / "evidence" / f"{label}.json"
        shutil.copy2(result_path, copied)
        evidence["result_path"] = str(copied)
        evidence["result_sha256"] = digest(copied)
    print(f"[{label}] Normal shutdown", flush=True)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paper", required=True, type=Path)
    parser.add_argument("--core", required=True, type=Path)
    parser.add_argument("--addon", required=True, type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--java", type=Path, default=Path(shutil.which("java") or "java"))
    parser.add_argument("--javac", type=Path)
    parser.add_argument("--expect", choices=("baseline", "fixed"), required=True)
    parser.add_argument("--runtime-template", type=Path,
                        help="Copy only Paper's cache/libraries/versions from a prior run of the same server JAR")
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error("--timeout must be positive")
    work = args.work_dir.resolve()
    if work.exists():
        parser.error("The disposable work directory must not exist; previous evidence is never overwritten")
    supplied = {name: jar_input(getattr(args, name).resolve()) for name in ("paper", "core", "addon")}
    pinned_inputs = verify_pinned_inputs(supplied)
    java = args.java.resolve()
    javac = args.javac.resolve() if args.javac else java.with_name("javac")
    if not java.is_file() or not javac.is_file():
        parser.error("A complete JDK is required; supply --java and optionally --javac")
    work.mkdir(parents=True)
    (work / "evidence").mkdir()
    (work / "plugins").mkdir()
    metrics_config = work / "plugins" / "bStats" / "config.yml"
    metrics_config.parent.mkdir()
    metrics_config.write_text(
        "enabled: false\nlogFailedRequests: false\nlogSentData: false\nlogResponseStatusText: false\n", encoding="utf-8")
    shutil.copy2(supplied["paper"]["path"], work / "server.jar")
    manifest = {"schema": 1, "expected_mode": args.expect, "inputs": supplied, "pinned_inputs": pinned_inputs,
                "java_version": subprocess.check_output([str(java), "-version"], stderr=subprocess.STDOUT, text=True),
                "scope": "Disposable real Paper inventories, registered addon tickers and core persistence; no connected player or Folia claim",
                "phases": [], "result": "INCOMPLETE", "bstats_enabled_before_first_boot": False}
    manifest_path = work / "evidence" / "manifest.json"
    atexit.register(lambda: manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8"))
    if args.runtime_template:
        template = args.runtime_template.resolve()
        if digest(template / "server.jar") != supplied["paper"]["sha256"]:
            raise RuntimeError("Runtime template uses a different Paper JAR")
        for name in ("cache", "libraries", "versions"):
            if (template / name).is_dir():
                shutil.copytree(template / name, work / name)
        manifest["runtime_template"] = str(template)
    (work / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (work / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={free_port()}\nonline-mode=false\n"
        "level-name=probe-world\nlevel-type=minecraft:flat\ngenerate-structures=false\n"
        'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"features":false,"lakes":false}\n'
        "spawn-protection=0\nview-distance=2\nsimulation-distance=2\n"
        "max-players=1\nspawn-animals=false\nspawn-monsters=false\n"
        "pause-when-empty-seconds=-1\nenable-rcon=false\nenable-query=false\n",
        encoding="utf-8")
    (work / "bukkit.yml").write_text("settings:\n  allow-end: false\n", encoding="utf-8")
    # Bootstrap without plugins supplies the exact Paper API/runtime dependencies.
    manifest["phases"].append(server_cycle(work, java, "bootstrap", None, args.timeout))
    shutil.copy2(supplied["core"]["path"], work / "plugins" / "Slimefun-Legacy.jar")
    shutil.copy2(supplied["addon"]["path"], work / "plugins" / "DynaTech.jar")
    core_config = work / "plugins" / "Slimefun"
    core_config.mkdir(exist_ok=True)
    manifest["storage_fixture_configs"] = {}
    with zipfile.ZipFile(supplied["core"]["path"]) as archive:
        for filename in ("profile-storage.yml", "block-storage.yml"):
            original = archive.read(filename)
            config = original.decode("utf-8")
            config, count = re.subn(r"(?m)^storageType:\s*[^\r\n]+$", "storageType: SQLITE", config)
            if count != 1:
                raise RuntimeError(f"Expected exactly one storageType in packaged {filename}")
            if filename == "block-storage.yml":
                config, count = re.subn(r"(?m)^dataLoadMode:\s*[^\r\n]+$", "dataLoadMode: LOAD_WITH_CHUNK", config)
                if count != 1:
                    raise RuntimeError("Expected exactly one dataLoadMode in packaged block-storage.yml")
            target = core_config / filename
            target.write_text(config, encoding="utf-8")
            manifest["storage_fixture_configs"][filename] = {
                "packaged_sha256": hashlib.sha256(original).hexdigest(), "fixture_sha256": digest(target),
                "storageType": "SQLITE", **({"dataLoadMode": "LOAD_WITH_CHUNK"} if filename == "block-storage.yml" else {})}
    (core_config / "config.yml").write_text(
        "options:\n  auto-update: false\n  auto-save-delay-in-minutes: 10\n"
        "researches:\n  enable-researching: false\n"
        "stability:\n  item-doctor:\n    repair-player-on-join: false\n"
        "    repair-opened-inventories: false\n    repair-chunks-on-load: false\n"
        "    repair-picked-up-items: false\n", encoding="utf-8")
    addon_config = work / "plugins" / "DynaTech"
    addon_config.mkdir(exist_ok=True)
    (addon_config / "config.yml").write_text(
        "options:\n  auto-update: false\n  disable-dimensionalhome-world: true\n", encoding="utf-8")
    source_root = Path(__file__).resolve().parent
    source = source_root / "src" / "audit" / "WirelessItemTransferProbe.java"
    classes = work / "probe-classes"
    classes.mkdir()
    libraries = sorted({p.resolve() for name in ("libraries", "versions") for p in (work / name).rglob("*.jar")})
    if not libraries:
        raise RuntimeError("Paper bootstrap did not provide its runtime libraries")
    classpath = os.pathsep.join([supplied["core"]["path"], supplied["addon"]["path"], *map(str, libraries)])
    compile_command = [str(javac), "--release", "21", "-encoding", "UTF-8", "-cp", classpath,
                       "-d", str(classes), str(source)]
    compiled = subprocess.run(compile_command, text=True, capture_output=True, timeout=args.timeout)
    (work / "evidence" / "probe-compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
    if compiled.returncode:
        raise RuntimeError(f"Native helper compilation failed; see {work / 'evidence/probe-compile.log'}")
    shutil.copy2(source_root / "plugin.yml", classes / "plugin.yml")
    helper = work / "plugins" / "DynaTechWirelessProbe.jar"
    with zipfile.ZipFile(helper, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(classes.rglob("*")):
            if file.is_file():
                archive.write(file, file.relative_to(classes).as_posix())
    manifest["helper"] = {"source_sha256": digest(source), "descriptor_sha256": digest(source_root / "plugin.yml"),
                          "runner_sha256": digest(Path(__file__).resolve()), **jar_input(helper)}
    manifest["phases"].append(server_cycle(work, java, "transfers", "run", args.timeout))
    if manifest["phases"][-1]["result"].get("fatal"):
        manifest["result"] = "FAIL"
        manifest["infrastructure_failure"] = manifest["phases"][-1]["result"]["fatal"]
        print(json.dumps({"result": "FAIL", "fatal": manifest["infrastructure_failure"], "evidence": str(manifest_path)}, indent=2))
        return 2
    manifest["phases"].append(server_cycle(work, java, "restart", "read", args.timeout))
    transfer = manifest["phases"][-2]["result"]
    restart = manifest["phases"][-1]["result"]
    cases, transfer_problems = inspect_report(transfer, "run", EXPECTED_CASES)
    restart_cases, restart_problems = inspect_report(restart, "read", EXPECTED_RESTART_CASES)
    if not transfer_problems and not restart_problems:
        before_stop = {row["location"]: (row["expected_id"], row["expected_values"])
                       for row in transfer["metadata_readbacks"][-1]["observations"]}
        after_restart = {row["location"]: (row["expected_id"], row["expected_values"])
                         for stage in restart["metadata_readbacks"] for row in stage["observations"]}
        if before_stop != after_restart:
            restart_problems.append("Restart metadata expectations differ from the observed pre-stop fixtures")
    failures = {name for name, case in cases.items() if case.get("passed") is not True}
    restart_failures = {name for name, case in restart_cases.items() if case.get("passed") is not True}
    case_set_ok = not transfer_problems and not restart_problems
    plugin_failures = [issue for phase in manifest["phases"] for issue in phase["log_checks"]["plugin_failures"]]
    log_ok = not plugin_failures
    infrastructure_ok = case_set_ok and log_ok
    if args.expect == "baseline":
        accepted = (infrastructure_ok and failures == NEGATIVE_CONTROLS
                    and restart_failures == RESTART_NEGATIVE_CONTROLS)
        manifest["baseline_reproduced"] = {"transfers": sorted(NEGATIVE_CONTROLS & failures),
                                           "restart": sorted(RESTART_NEGATIVE_CONTROLS & restart_failures)}
    else:
        synchronized = transfer.get("registered_synchronized_tickers", {})
        accepted = (infrastructure_ok and not failures and not restart_failures
                    and synchronized == EXPECTED_TICKERS)
    manifest["observed_failed_cases"] = sorted(failures)
    manifest["observed_failed_restart_cases"] = sorted(restart_failures)
    manifest["expected_case_set_present"] = case_set_ok
    manifest["report_validation_problems"] = transfer_problems + restart_problems
    manifest["plugin_log_gate_passed"] = log_ok
    manifest["result"] = "PASS" if accepted else "FAIL"
    installed = {"core": work / "plugins" / "Slimefun-Legacy.jar", "addon": work / "plugins" / "DynaTech.jar", "paper": work / "server.jar"}
    manifest["binary_identity_retained"] = all(
        digest(Path(row["path"])) == row["sha256"] and digest(installed[name]) == row["sha256"]
        for name, row in supplied.items())
    if not manifest["binary_identity_retained"]:
        manifest["result"] = "FAIL"
    (work / "evidence" / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": manifest["result"], "mode": args.expect,
                      "failed_cases": sorted(failures), "failed_restart_cases": sorted(restart_failures),
                      "evidence": str(work / 'evidence/manifest.json')}, indent=2))
    return 0 if manifest["result"] == "PASS" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError, zipfile.BadZipFile) as failure:
        print(f"Native wireless probe failed: {failure}", file=sys.stderr)
        sys.exit(2)
