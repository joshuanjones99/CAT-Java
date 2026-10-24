# CAT-Java

A proof of concept: CAT in Java 17, with a web UI, reading and driving the Topaz POS
through the register's pos-shim. It is the first slice of a possible Java rewrite of
[CAT](https://github.com/joshuanjones99/CAT), not a replacement for it yet.

## What it does

`run.cmd --rig lab` serves one page on `http://127.0.0.1:8090/`:

- **Shim health**: pos-shim's build, uptime and which POS clients are connected and drivable.
- **POS screen**: the current overlay, soft keys and totals, refreshed every 2 seconds.
- **Press a key**: one button per key the shim can build. Each press asks for confirmation,
  because it drives the live POS.

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

| | Evidence |
|---|---|
| Builds and tests on `C:\CICD\Tools\Java17\jdk-17.0.5+8` | 26 tests, 2026-10-24 |
| Health, screen and key list read from the lab register's shim (Reg101) | Live, 2026-10-24, through the running jar and a headless Chromium |
| A key press reaching the POS | **Not exercised from this client.** The route is tested against `FakeShim` only; the shim's own key path was verified from the Python CAT on 2026-08-03 |

`FakeShim` (in `src/test`) is evidence about this repository, not about a rig. Its canned
200 replies are real lab-shim replies captured on 2026-10-24.

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
run.cmd --rig lab --register topaz101 --port 8091
```

The first build downloads Maven 3.9.9 (through the Maven wrapper) and the dependencies
from Maven Central.

## Rig files

None are committed here. `--rig lab` reads `lab.yaml` from `$PETROTEST_RIGS_DIR`, the
variable the Python CAT reads, and otherwise from `C:\gitrepos\CAT\rigs`, so both CATs
read the same files. `--rig path\to\file.yaml` reads a file directly. The rig must set
`pos_shim: true`.

## Layout

```
src/main/java/com/verifone/cat/
  Main.java          command line: rig, register, port
  rig/               the rig file: Rig, RigLoader
  posshim/           PosShimClient and the shim's replies as records
  devices/           DeviceError and its three kinds (unreachable, unavailable, protocol)
  web/               Javalin app, server-rendered fragments, HTML escaping
src/main/resources/public/   htmx 2.0.4 (vendored) and cat.css
```

## Safety

- The UI binds `127.0.0.1` only.
- `POST /keys` requires htmx's `HX-Request` header, so another page in the operator's
  browser cannot press keys through it with a cross-site form post.
- No Verifone jar, petroapps source or rig file is committed.
