# CAT-Java

A Java 17 port of [CAT](https://github.com/joshuanjones99/CAT), the Python test harness
that drives the Topaz POS and the Commander on a lab rig, with a web UI only. It is not a
replacement for the Python CAT yet: most of the code is ported and tested off-rig, but no
plan has completed a Java run and almost nothing has been proven on hardware.

**Start with [`HANDOFF.md`](HANDOFF.md)** (state, port map, open items in priority order)
and [`CLAUDE.md`](CLAUDE.md) (commands and hard rules).

## What it does

`run.cmd --rig lab` serves the web UI on `http://127.0.0.1:8090/`: Run (Overview, Config,
Log, scheduled and continuous runs), Results, Authoring, Install Upgrade with Downloads,
Launch Simulators, Setup, Settings, and a POS page (live pos-shim screen and key presses,
each behind a confirmation). The command line runs preflight, a whole plan (`launch`),
provisioning and the Xray reporter.

## How it reaches the POS

There is no RMI in this repository, and no Topaz class is loaded on the controller.
The POS is an RMI *client*: it pushes its screen state to whatever `tokenRMI.prop` names,
and on a CAT rig that is pos-shim, running on the register beside the POS on the register's
own Java 17 (Corretto 17.0.5). pos-shim serves that state as JSON on port 8801, and
`PosShimClient` reads it over HTTP, as the Python CAT's `petrotest.devices.pos_shim` does.
pos-shim itself is built and provisioned by the Python CAT (`bridge/pos-shim/`,
`petrotest provision`).

```
controller (Java 17)                        register 192.168.x.101
┌──────────────────────────┐   HTTP :8801   ┌───────────────────────────────┐
│ browser ── Javalin + htmx │ ─────────────► │ pos-shim ◄── RMI push ── POS │
│            PosShimClient  │                │                               │
└──────────────────────────┘                └───────────────────────────────┘
```

## What has been proven, and what has not

See HANDOFF.md §4. In short: the full suite passes off-rig (the count is in HANDOFF.md), the Java port
matches the Python on real data where that could be checked, and since 2026-10-28 the whole seven-step
run has been proven on the lab rig: Solutions 134 installed from a clean PSI install, the register paired
and provisioned, the Commander configured, and Cadence (264 cases) run end to end from Java, the best run
at 258/264 (equal to the Python CAT's best; the two failures it shares are known product defects).
Not yet proven on a rig: Commander Connect, Xray reporting, FEPSIM v2, and every plan other than Cadence.

Fake-backed tests are evidence about this repository, not about a rig.

## Java

The controller's JDK is `C:\CICD\Tools\Java17\jdk-17.0.5+8` (Temurin 17.0.5, Java v1.2 in
Artifactory). `build.cmd` and `run.cmd` use it whatever `JAVA_HOME` says, and the Maven
enforcer refuses to build on any other major version. `$CAT_TOOLS_ROOT` moves the tools
root, as it does for the Python CAT.

## Build, test and run

```bat
build.cmd                 :: compile, test, and package target\cat-java.jar
build.cmd test            :: tests only
run.cmd --rig lab         :: the web UI on http://127.0.0.1:8090/
run.cmd web --rig lab --register topaz101 --port 8091   :: the same, with the word "web"
run.cmd preflight --rig lab               :: the preflight checks; exit 0 when the rig is testable
run.cmd preflight --rig lab --register topaz101 --no-repair
run.cmd launch --rig lab --plan PETROCORE-135127   :: one run, headless, through the seven steps
run.cmd provision --rig lab               :: push, verify and stamp the pos-shim payload; exit 0 when provisioned
run.cmd xray --run <run-id> [--once] [--dry-run]    :: the Xray reporting sidecar for one run
run.cmd close-day --rig lab               :: close the Commander's day (petrotest close-day)
run.cmd commander-snapshot ACTION ...     :: capture, restore or check a Commander config snapshot
run.cmd commander-connect --rig lab --lab Prod --email E --service-id ID   :: onboard to Commander Connect
run.cmd cloud-tunnel --rig lab [--stop]   :: the Commander's cloud tunnel (petrotest cloud-tunnel)
```

`web` is the default, so `run.cmd --rig lab` and `run.cmd web --rig lab` are the same. The web UI
knows the rig it was started with, so its Start run launches on it. `preflight`
takes `--rig`, `--register`, `--shim-port` and `--no-repair`, and exits 0 when the rig is testable,
1 when it is not, 2 for a usage or load error.

`launch` takes the Python `tools/rig/run_plan.py`'s flags: `--rig`, `--plan` (one or more keys, repeatable,
run in the order given), `--suite`, `--simulator`, `--upgrade`, `--exec dev|BDD`, `--dist`, `--branch` (the
petroapps test-plan branch), `--fep` (repeatable), `--no-xray`, `--coverage`/`--no-coverage`, `--cc-onboard`,
`--waive-cc-requirement`, `--capture-snapshot`, `--snapshot-update`, `--buypass-host`, `--cache-only`,
`--tags`/`--only KEY...`/`--from KEY` (one at a time; a partial run is still refused), `--fake-pos` and
`--fake-pinpad`. A flag not given falls back to the Config tab's saved choices (`~/.cat/java/last-config.json`,
then the Python CAT's `~/.cat/last-config.json`, read-only), then the Python's defaults. The plan's cases are
read from Xray first, the cache only with `--cache-only`. A rig run's simulators and probed devices are the
plan's Supported CAT Test Plans row, or else every launchable simulator and every device but the pinpad.
It exits 0 only when every step completed, 1 when a step failed or is unavailable, and 2 for a usage
error. Step 4 still reports parts unavailable on a rig (HANDOFF.md §6), so a rig run cannot complete yet. `xray` takes the run's id and exits 0
whatever Xray did, and 2 for a usage error.

The first build downloads Maven 3.9.9 (through the Maven wrapper) and the dependencies
from Maven Central.

## Rig files

None are committed here. `--rig lab` reads `lab.yaml` from `$PETROTEST_RIGS_DIR`, the
variable the Python CAT reads, and otherwise from `C:\gitrepos\CAT\rigs`, so both CATs
read the same files. `--rig path\to\file.yaml` reads a file directly. The rig must set
`pos_shim: true`.

## Layout

See CLAUDE.md for the package map. In brief: `launch/` runs the seven steps, `pats/` reads
and runs PATS cases, `pos/` drives the POS browser, `devices/` and `commander/` talk to the
rig, `web/` is the UI, and `src/main/resources/public/` holds htmx 2.0.4 and cat.css.

## Safety

- The UI binds `127.0.0.1` only.
- `POST /keys` requires htmx's `HX-Request` header, so another page in the operator's
  browser cannot press keys through it with a cross-site form post.
- No Verifone jar, petroapps source or rig file is committed.
