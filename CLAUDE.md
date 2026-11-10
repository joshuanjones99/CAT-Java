# CAT-Java

A Java 17 port of the Python CAT (`C:\gitrepos\CAT`), with a web UI. **Read `HANDOFF.md`
first**: it holds the verified state, the port map, the open items in priority order and
the lessons from the build-out. Do not re-derive what it records.

The Python CAT is the specification and the reference. When porting or fixing, read the
Python module named in the class Javadoc or in HANDOFF §5, and its tests in
`C:\gitrepos\CAT\tests\unit\`. The Python CAT's own `CLAUDE.md` and `HANDOFF.md` explain the
rig, the devices and the history; grep them rather than reading them whole.

## Commands

Run builds from **PowerShell** with full paths. Through Git Bash, `build.cmd` can exit 0
without building anything.

```powershell
cmd /c "cd /d C:\gitrepos\CAT-Java && C:\gitrepos\CAT-Java\build.cmd"          # compile, test, package
cmd /c "cd /d C:\gitrepos\CAT-Java && C:\gitrepos\CAT-Java\build.cmd test"     # tests only
cmd /c "cd /d C:\gitrepos\CAT-Java && C:\gitrepos\CAT-Java\build.cmd test -Dtest=SomeTest"
```

`run.cmd` rebuilds the jar itself (`package -DskipTests`) when it is missing or older than the
sources, and both scripts stage Java 17 from Artifactory when it is missing (`bootstrap.ps1`).
A failing test means `build.cmd` makes no jar; it lists the failing classes.

Results are in `target\surefire-reports\TEST-*.xml` (the quiet build prints little). Test
classes run in parallel JVM forks (one per two cores; `-Dcat.test.forks=1` for one JVM), so
the full suite takes two to three minutes; a few browser tests drive Playwright's Chromium
1148 and skip when it is absent.

```bat
run.cmd --rig lab                       :: web UI on http://127.0.0.1:8090/
run.cmd preflight --rig lab             :: read-only preflight (--repair drives the POS; the Python default)
run.cmd launch --rig lab --plan PETROCORE-70960   :: add --fake-pos for the off-rig fake POS
run.cmd provision --rig lab
run.cmd xray --run <run-id> --dry-run
```

`build.cmd` and `run.cmd` always use `C:\CICD\Tools\Java17\jdk-17.0.5+8`; the Maven enforcer
refuses any other major version. Java 17 only: no pattern-matching `switch`, no virtual
threads.

## Hard rules

1. **Never contact a real device or the lab from a test.** Use the loopback fakes
   (`FakeShim`, `FakeCommander`, the forecourt/ViperPay/Artifactory/Atlassian fakes),
   in-memory transports and `@TempDir`. Never read or write the real `C:\CICD`,
   `C:\gitrepos\petroapps`, `~/.cat` or credentials in tests.
2. **Never stop processes by image name** on the controller. PATS's simulators are Java.
   The simulator supervisor stops only what it started; the red Stop matches by port or
   exact token.
3. **The four lint gates run as tests** (`src/test/java/com/verifone/cat/lint/`): no
   `Thread.sleep` in main code (use bounded parks/waits), no raw element IDs or XPaths
   outside `pos/Locators.java`, no IP literals in `src/main`, Javalin/UI only in `web/`.
   The one IP carve-out is the lab's registers 101 and 102 and its Commander .11 in
   `tools/LabRegisters.java` (the POS page and POS Pairing use them with no rig; user 2026-11-10);
   the gate allows only those three, only there.
4. **Never hand-edit `pos/Locators.java`.** It is generated from
   `src/main/resources/pos/locators.json` by `pos.gen.GenLocators`; a staleness test fails
   otherwise. `LocatorsParityTest` checks it against the Python catalogue.
5. **Unimplemented device surfaces throw `DeviceUnavailable`, never return false or
   succeed.** ERROR means the probe could not tell; FAIL means the rig is wrong.
6. **CAT-Java writes only under `~/.cat/java/`.** It must never write the Python CAT's
   `~/.cat` files.
7. **No proprietary material or credentials.** No Verifone jars, petroapps sources,
   private keys or tokens. The only hardcoded credentials are the Python CAT's documented
   lab carve-outs (Commander CGILink login, PSI answers), each citing that rule.
8. **Rig files are not committed.** They are read from `$PETROTEST_RIGS_DIR`, else
   `C:\gitrepos\CAT\rigs`. Tests use documentation addresses or `.invalid` names.
9. **No new dependencies** without a deliberate decision recorded in HANDOFF §2.
10. Edit files with the Edit/Write tools. PowerShell 5.1 `Get-Content -Raw` reads UTF-8
    as ANSI and garbles non-ASCII characters.

## Evidence discipline

A fake-backed test is evidence about this repository, not about a rig. Rig-touching
classes carry `UNVERIFIED AGAINST HARDWARE (Java port)` in their Javadoc; keep that honest
and never present a fake or `--fake-pos` pass as hardware verification. When something is
verified live, record what, when and how in HANDOFF §4.

## Layout

```
src/main/java/com/verifone/cat/
  Main.java        subcommands: [web] (default), preflight, launch, provision, xray
  core/            shared by every package: Json.MAPPER (the one default mapper; never configure it),
                   Waits (bounded pause/until/poll), Hashes (SHA-256), CatHome (~/.cat/java paths).
                   Use these; do not add a package-local copy
  rig/             rig files (strict schema), the one duration grammar, the run lease
  posshim/         register pos-shim HTTP client (read + control)
  pos/             locator catalogue (generated), driver (Playwright CDP), pages, fake POS,
                   pinpad double, receipt capture
  pats/            PATS case/scenario/feature reading; runner/ (CaseRun, registry, fields,
                   handlers/* families, StandardHandlers)
  launch/          the seven run steps, RunLauncher, LaunchCommand, StandardRegistry
  devices/         commander (CGILink), forecourt, legacyrmi, viperpay, printer, csite,
                   eps, suspended, fep, vats (physical pinpad: BTT, KM4 relay, agent.py shipped
                   as a resource and spawned under the staged Python 3.9)
  commander/       init, prep, trust, links, snapshot, connect, registerapp
  preflight/ recovery/ provision/ upgrade/ build/ collect/ closeday/ coverage/
  plans/ runconfig/ runservice/ report/ results/ validate/ xray/ atlassian/
  authoring/ setup/ sims/ connections/ fep/ cloudtunnel/ prefs/
  gitauth/         petroapps' Bitbucket login through git's credential store (Setup's Verifone card)
  desktop/         CAT's own window (Edge/Chrome app mode), the default browser, the CAT-Java.lnk shortcut
  shell/           the web shell's state: toasts, loading line, the one busy flag, page watch, auto-close
  recorder/        record (CDP tap listener), the recording file, collapse, generate (.feature)
  publish/         a suite onto a local petroapps branch (petrotest publish)
  tools/           the Tools page's operator tools: POS pairing, Commander Connect onboarding, reboots
  web/             Javalin + htmx pages (localhost only)
```
