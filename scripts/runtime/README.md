# Native wireless transfer regression probe

This helper runs the supplied DynaTech and Slimefun JARs on a real, disposable
Paper server. It invokes the registered Wireless Item Output ticker, verifies
synchronized registration for both wireless machines, and uses real block menus,
core storage controllers and shutdown/restart persistence.
It does not contain a replacement wireless transfer implementation.

## Inputs and execution

Python 3.11 or newer, `curl`, and a complete JDK are required. The pinned native
lanes run with JDK 25; the helper compiles with `--release 21`.

Prepare the pinned public core and official Paper builds:

```sh
python3 scripts/runtime/prepare_wireless_probe_inputs.py --out /tmp/dynatech-inputs
```

The preparer checks each official Paper metadata response against
`wireless_probe_inputs.json`, then verifies size, SHA-256 and archive CRC. Its
`inputs.json` contains `core.path` and `paper[version].path`. The pinned lanes are
Paper 1.21.11 build 132 (STABLE), 26.2 build 132 (STABLE), and 26.3 build 159 (BETA).
The core is the unchanged published Slimefun Legacy 4.1.71 artifact from commit
`f68544e47d8489704d0e9d34adc65f0114b07153`. No latest-build or channel fallback is
permitted. The runner independently checks the supplied core and Paper hashes
against the same pins.

```sh
python3 scripts/runtime/run_wireless_transfer_probe.py \
  --paper /tmp/dynatech-inputs/paper-1.21.11-132.jar \
  --core /tmp/dynatech-inputs/Slimefun-Legacy4.1.71.jar \
  --addon target/SF_DynaTech1.1.04.jar \
  --java "$JAVA_HOME/bin/java" \
  --work-dir /tmp/dynatech-fixed-1.21.11 \
  --expect fixed
```

Repeat with the other pinned Paper JARs and a new work directory for each lane.
Use `--expect baseline` with the verified pre-fix DynaTech 1.1.03 JAR from the
published Legacy 4.1.71 addon bundle. The expectation flag changes acceptance
criteria; it does not select, rebuild or rewrite an addon JAR.

`--runtime-template PREVIOUS_WORK_DIR` may reuse only Paper's `cache`, `libraries`
and `versions` directories after confirming the server JAR digest matches.
Worlds, plugin data, inventories and evidence are always new. Existing work
directories are refused. `--javac` can select a separate compiler and `--timeout`
bounds helper compilation and each server-start/probe phase.

The runner uses an offline flat world and a loopback-only server port. It accepts
the EULA for that disposable process, disables plugin updates, and disables the
unrelated DynaTech Dimensional Home world. Before the first bootstrap, it writes
the normal global `plugins/bStats/config.yml` with `enabled: false`; that setting
is checked before and after every boot. Storage uses SQLite and
`LOAD_WITH_CHUNK`. Credentials-free environment HTTP proxies and the existing
system Java truststore are honored where available, with certificate verification
enabled.

## Regression contract

The 25 transfer cases and four restart cases in the runner and Java helper form
one acceptance contract. A fixed run must pass every nonempty assertion set,
report the exact case names once each, and report both registered wireless
tickers as synchronized.

The baseline must reproduce the 11 named transfer regressions and two named
transfer-persistence failures, while all 14 ordinary transfer controls and both
untouched restart controls pass. A
baseline restart report is therefore expected to have an aggregate failure flag;
that is accepted only when its precise case failures match the contract. A fatal
helper error, unrelated failed control, duplicate/missing/extra case, empty
assertion list, inconsistent pass flag, nonzero server exit, or plugin failure
cannot serve as a negative control.

The fixtures check these relationships:

| Area | Required behavior |
| --- | --- |
| Ordinary transfers | Preserve source slot order, destination slot order, full-stack admission, metadata and eight energy at each endpoint per successful source-stack transfer. |
| Capacity and power controls | Blocked transfers retain items and charge; aggregate space spread across several partial stacks does not broaden the historical admission rule. |
| Actual insertion remainder | Test-marked virtual items exercise the real core admission and maximum-stack hooks. A rejected insertion retains the complete source and energy; a partial insertion retains its remainder and preserves the total item count. Hook invocation is required. |
| Lifecycle checks | Locked or absent menus and pending endpoint data cannot transfer; off-thread calls return safely, and invalid links remain unchanged without an exception. |
| Asynchronous input-chunk loading | The existing request-and-retry behavior remains available; a loaded world/chunk and live authoritative menus are required before mutation. |
| Ordinary persistence | A transfer into an empty slot and a transfer consisting entirely of stack merges survive a separate server process. Untouched no-transfer and item-identity controls survive too. |

Persistence fixtures represent existing persisted machines. They first save
their initial inventories, await the core's acknowledgment, positively check
that both menus are clean, and let the normal delayed metadata and database
write queues drain. After the actual production tick they use ordinary
dirty-gated inventory saving. The helper again waits for normal queued metadata
and database writes to drain before reporting the completed run and allowing
shutdown. It records both pending-write counts in each report. Those counts are
point-in-time diagnostics in this quiescent fixture, not a transaction barrier.

The harness must not force-save the affected inventories after the transfer:
doing so would hide the source and destination dirty-marking regressions. The
restart checks retain all exact item, charge, link and unrelated metadata
assertions; they do not cover immediate shutdown with outstanding metadata
writes.

Ambient wireless ticking is paused so explicitly invoked production ticks are
accounted for. Fixture payloads carry metadata and native serialization evidence.
The chunk-reload case first observes actual world unload and core cache eviction.
Only after the production request causes a matching `ChunkLoadEvent` does the
fixture retain that already loaded chunk with a plugin ticket while normal core
autoload supplies a distinct menu. It does not initiate the input chunk load.
The fixture also invokes ordinary output retries every ten server ticks. This
checks the existing request and resume path with explicit fixture retention;
it does not establish that a transient Paper request keeps a remote chunk loaded
indefinitely without another ticket.
Virtual test handlers claim only uniquely marked test items and are removed in
cleanup. They do not intercept `fitAll` or `pushItem`, alter the ordinary
admission rule, or claim that unrelated production items have the same behavior.

## Evidence and limits

`WORK_DIR/evidence/manifest.json` records the supplied binary sizes/hashes, pinned
runtime identities, Java version, helper source/JAR/descriptor/runner hashes,
phase log and result hashes, case results and final acceptance. `transfers.json`
and `restart.json` retain the separate helper reports. Logs, startup checks and
compiler output remain beside them. Interrupted or infrastructure-failed runs
leave an incomplete/failing manifest rather than a passing result.

Generated Paper remap JARs are CRC-checked before the probe command. Startup and
probe timeouts retain bounded thread diagnostics where available. The log gate
rejects scheduled-task, linkage, enable, storage and energy failures attributed
to DynaTech, the helper or core in either expectation mode. Other environment or
remote-service diagnostics remain in the raw logs and the separate diagnostics
list; a pass does not imply a warning-free server log.

These checks establish the tested synchronous Paper transfer and ordinary
shutdown/restart behavior for the exact supplied artifacts. They do not establish
Folia support, a transaction across the two inventories, crash atomicity,
connected-player interactions, other database backends, or fixes for Picnic
Basket, Inventory Filter, Portable Liquid Tank or Item Bands.
