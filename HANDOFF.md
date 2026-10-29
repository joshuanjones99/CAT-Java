# CAT-Java handoff

**Read this first, then `CLAUDE.md`.** This is the single record of what CAT-Java is, what
is built, what is proven, and what comes next. Update it whenever work lands.

If this document disagrees with the tree, the tree is right.

State as of **2026-10-28, end of wave 15** (orchestrated session). `main` builds clean:
**3,504 tests, 0 failures, 0 errors, 0 skipped** (Maven's total, 2026-10-28 19:20) on the CICD Java 17. The eleven wave-15
branches are merged; their worktrees under `C:\gitrepos\CAT-Java-wt\` can be removed
(`git worktree remove`). The GitHub repo exists (`origin`, joshuanjones99/CAT-Java); main is
not yet pushed from this session.

## 1. What this is

A port of the Python CAT (`C:\gitrepos\CAT`, github.com/joshuanjones99/CAT) to Java 17,
with a web UI only (no TUI). The Python CAT stays the working harness until CAT-Java
reaches parity **on a rig**; nothing here replaces it yet.

**The success criterion (rig owner, 2026-10-28):** Cadence (PETROCORE-118517) run on the lab
rig from a clean PSI install, from CAT-Java:

```
run.cmd launch --rig lab --plan PETROCORE-118517 --upgrade new_install
               --commander-branch Solutions --commander-build 134 --no-xray --no-coverage
```

Honest progress (2026-10-28): code ported ≈ 90% of the Python by module; working end to end
on the fake POS: both Charity-Indoor (39 cases) and Cadence (264 cases) run to a verdict for
every case (§4); rig-proven ≈ 5% (read-only live checks only). No launch step has yet run
against a rig from Java. Every path wave 15 added is fake-tested only.

## 2. Decisions (rig owner unless said)

| Decision | Detail |
|---|---|
| Java 17 | `C:\CICD\Tools\Java17\jdk-17.0.5+8` (Temurin 17.0.5, Java v1.2 in Artifactory). `build.cmd`/`run.cmd` use it; the Maven enforcer refuses any other major version. |
| POS over pos-shim HTTP | No RMI on the controller. pos-shim (built and provisioned by the Python CAT's tools) stays on the register; Java talks HTTP to it. |
| Web UI | Javalin 6 + htmx 2, server-rendered fragments, localhost only. Layout follows the Python CAT's `docs/web-ui.md`. |
| Build | Maven via the wrapper. Dependencies: Javalin, Jackson (JSON + YAML), slf4j-simple, Playwright for Java 1.49.0, JSch (mwiede 0.2.20), jSerialComm 2.11.0, **JNA 5.14.0 + jna-platform 5.14.0 (orchestrator, 2026-10-28: the Java Access Bridge and user32 for the `click:` simulators; Crypt32 for the DPAPI credential files)**, JUnit 5. Thin `target/cat-java.jar` + `target/lib/`. New dependencies are the orchestrator's call. |
| Rig files | Not committed here. Read from `$PETROTEST_RIGS_DIR`, else `C:\gitrepos\CAT\rigs`. |
| Own files | CAT-Java writes only under `~/.cat/java/`. It may read the Python CAT's `~/.cat` (plan cache, `last-config.json`) and `.secrets/artifactory.credentials` read-only as fallbacks, never write them. **One exception (2026-10-28):** `psi-init` renames the installer's `~/psi_message.txt` to `.changed` then `.used`, as the Python does, so both ports agree on when the PSI password is spent. |
| Nothing proprietary | No Verifone jars, petroapps sources, private keys or real credentials. The only hardcoded credentials are the Python CAT's two documented lab carve-outs (Commander CGILink login, PSI answers). |
| Simulator needs attention | FAILS Start Simulators with one concise line per simulator (stricter than the Python, which warns). |
| Blocked slices | Any slice Haiku 5.5's cyber safeguard blocks is rerun on Opus 5.5 without asking. Wave 15 ran every slice on Opus. |
| Success criterion | Cadence from a clean install (§1), un-onboarded first (the saved Config has Commander Connect off), then onboarded as the Python migration requires. |

## 3. How to work here

- Read `CLAUDE.md` for the commands and the hard rules.
- The Python source is the specification. Port behaviour faithfully; record any deviation
  in the class Javadoc and in §6 here.
- Evidence discipline is the Python CAT's: a fake-backed test is evidence about this
  repository, not about a rig. Rig-touching classes carry `UNVERIFIED AGAINST HARDWARE
  (Java port)` until run on a rig. Never present a fake pass as hardware verification.
- How this was built: one orchestrator session plans waves; Opus agents each work in a git
  worktree (`C:\gitrepos\CAT-Java-wt\<slice>`, branch `wave<N>/<slice>`) owning disjoint
  packages from a shared brief; the orchestrator reviews, merges (resolving the launch-package
  conflicts by hand or with a dedicated merge agent), runs the full build, and has a read-only
  reviewer compare the merged launch against the Python before any rig run. Lessons in §7.

## 4. Verified state

| What | Evidence |
|---|---|
| Builds and tests on the CICD Java 17 | `build.cmd test`, 3,504 tests, 0 failures, 0 errors, 2026-10-28 19:20. Four lint gates run as tests |
| **Charity-Indoor 70960 on the fake POS, from the CLI** | run `fb8382bade89`, 2026-10-28: 39 cases, every one to a verdict (0 passed; the 12 POS+pinpad-only cases stop exactly where the Python's `pytest tests/suites/charity --fake-pos` stops them; the 27 others need devices a fake run has not). Evidence about the repository only |
| **Cadence 118517 on the fake POS, from the CLI** | run `81eeb3d11883`, 2026-10-28: 264 cases, every one to a verdict in 1 h 51 m, no internal error (0 passed: RMI, pumps, DCR, Commander and most Cadence elements are absent from the fake). Evidence about the repository only |
| **First rig run from Java: steps 1 and 2 complete live, step 3 started four of five simulators** | run `e272450ee561`, 2026-10-28 15:01 (lab rig, `launch --plan PETROCORE-118517 --upgrade new_install ...`): System Checks complete (rig lines, printer COM1 held, Commander Base_Level 057.02.00 read over SSH, the five staged simulators matched master 129 by their markers, lease taken), Connections complete; **PCATS01/02/03 Loyalty started and their "Start Host" buttons pressed through the Access Bridge, confirmed in each `LoySim_Debug.log` ("Loyalty FEP Simulator is ready. Port: 9010/9020/9030 ... Started VCMG Host")**; Petro Simulator Suite started and listening. BUYPASS Host failed: CAPK re-dating not ported (ported right after: `sims.Capk`, byte-identical entries to the Python's on the real jar; first launch of the built jar pending). Cleanup stopped the four and freed the lease; nothing reached the Commander |
| **Second rig run: the PSI install of Solutions 134 from Java, and the installer-password reset** | run `ac22943e6604`, 2026-10-28 15:19 (same command): steps 1-3 complete in 23 s with all five simulators ready (BUYPASS Host from the Java-built CAPK-re-dated jar, listening); step 4: build already downloaded (Artifactory not asked), **Install the Commander build PASS in 28 m 40 s, psi-init --no-pairing PASS**, provision PASS in 22 s, then **psi-init with pairing FAILED**: no CDP endpoint at the register's 19220, because the browser override (pushed Chromium, relay, boot hook) is not ported and provision had marked it skipped while still reporting PASS (hard rule 5). Cleanup stopped the simulators and freed the lease. Rig left: Commander on Solutions 134 with the lab password set, register wiped and unpaired |
| **Third rig run: provision with the browser override brought CDP up on the wiped register; pairing reached the OTP** | run `1a158f28d9cd`, 2026-10-28 16:09: install 27 m PASS, psi-init --no-pairing PASS, **provision PASS in 46 s with the pushed Chromium, the CDP relay and the boot hook (first live run of `BrowserOverride`)**, then pairing: `cpairingsvc` true, OTP fetched, and the register's page navigated while the Java checked for the refused page: Playwright "Execution context was destroyed" ended the stage (the Python's `is_present` would throw the same). Fixed in `PairingPage` (bounded retry of the snapshot queries across a navigation, commit 9a72dc1). Rig left: Commander on Solutions 134 with the lab password, register wiped, browser override on, unpaired |
| **Fourth rig run: STEP 4 COMPLETE from a clean install (45 m 16 s); step 5 reached** | run `b34a01ad8176`, 2026-10-28 16:42: install 27 m, psi-init, provision (browser override), **pairing PASS (3 m 10 s, the register rebooted and came back to its login screen)**, provision again, commander-prep, **commander-init 5 m 35 s (snapshot restore, base import, inits)**, secondary-fep, payment-links 5 m 17 s, **emv-keys 27 s (tables + CA keys confirmed from the host packet log)**, register-app, **close-day 17 s (register pass + close confirmed in the Commander log)**, pinpad link, payment-ready, snapshot capture, installed build Solutions134. Step 5: 79588, 124034, 79590 (Fuel DCR Init, 2 m 20 s), 37043 (POS-Login), 36857 and 118535 PASSED; then every key-driven case failed: the Java named the shim client "CAT" (the Python names none) and every `-Receipt.txt`/`-Tlog.xml` reference was looked for in the pulled folder, not petroapps' `TestCases/<key>/`. Both fixed (commit on main, 2026-10-28 17:4x). Run killed by the rig owner's decision at ~60 cases; simulators left running for the next run to adopt |
| **Fifth rig run: the first Cadence cases PASS on the rig from Java** | run `f6b5c0596966`, 2026-10-28 17:55: steps 1-3 adopted the running simulators; step 4 complete again in 46 m 55 s (install, psi-init, provision, pairing 3 m 19 s, provision, prep, init 5 m 22 s, secondary-fep, payment-links 5 m 19 s, emv-keys 33 s, close-day, pinpad link, payment-ready, snapshot); step 5: **79588, 124034, 79590, 37043, 36847, 36848 PASSED** (scan, EMV card sale, receipt and TLog validated: the first key presses and validations from Java on a rig). Then three classes: 36850's receipt validation compared the interim "PLU ITEM NOT FOUND" slip, not the sale receipt; 36853's `REFUND_WITH_TRANS_MAP` had no TLog source for the STAN; and no recovery walk ran after that failure, so its open ticket failed every later case ("reads 'Error Correct'", 60 s each). Killed at ~12 cases (rig owner's standing decision for a cascade). Fixes in progress on `wave15/receipts` and `wave15/step5fix` |
| **Sixth rig run: the first WHOLE Cadence plan from Java, all seven steps complete** | run `dec5794107fa`, 2026-10-28 19:23, `--upgrade skip` (rig owner: iterate with skip, finish with a clean install): step 4 in 2 m 15 s on the warm Commander (close-day's register pass cleared the killed run's ticket); **step 5: 264/264 cases to a verdict in 2 h 12 m, 180 passed, 84 failed**; Cleanup closed the day and freed the lease. Failures by class: 26 `WAIT_DCR_PROMPT` (Petro dropped every DCR soft key, the Python's identical bytes too: the Petro instance's state, see §6), 18 `NETWORK_REPORT` (report family's register unbound; fix in progress), 12 loyalty `WAIT_FOR_PROMPT` + ~22 receipt/TLog diffs (the pulled 55.02 case variants and baselines; fixed: local suites resolve first), 36879/37073 (the Python's own expected failures), 37072 SELECT_TILL, two cascades. First live proof of: the shim key path, receipt and TLog validation, the recovery ladder (one rung cleared an open ticket), the dialog watch |
| Java Access Bridge clicks against a real Swing window | 2026-10-28, a throwaway JFrame on this controller through the real `AccessBridgeClicker`: "clicked Load Data, Load Servers" in 5.3 s; a miss lists the available buttons. Says nothing about the LoySim/MPPA windows |
| pos-shim health/screen/keys **read** from the lab register (Reg101) | live, 2026-10-24 |
| `--rig lab` loads under the strict Java rig schema | live `lab.yaml`, 2026-10-24 |
| `cat-java preflight --rig lab --no-repair` | live 2026-10-24 |
| Results page over the real `C:\CICD\Results\CAT` | live 2026-10-24 |
| Config tab over the real plan cache | live 2026-10-24 (GET only) |
| summary.html, canonical TLog, locator catalogue (852), `.feature` reading (337 actions), PATS field table (71), snapshot logic, receipt key, CGILink `?` | parity against the Python's own data, 2026-10-24 (unchanged) |
| **Not yet exercised from Java** | a green Cadence; a DCR case passing (Petro dropped the keys); any Xray send; Commander Connect |

## 5. Port map

`ported` = merged, tests pass off-rig. Nothing is `rig-verified` yet.

| Python (CAT) | Java package | Status |
|---|---|---|
| `petrotest/rig/` | `rig` | ported (strict; ppk.py skipped) |
| `devices/pos_shim.py` | `posshim` | ported incl. control; read path live-checked |
| `devices/config_shim.py`, `devices/commander.py` | `devices.commander`, `commander` | ported |
| `commander_init.py` (+recover_dcr_link, release_pump), `commander_prep.py`, `commander_trust.py`, `payment_ready.py` | `commander` | ported; the init restores the Commander snapshot before the base import; `release_pump` through Petro ported 2026-10-28 |
| `commander_snapshot.py`, `devices/suiteinfo.py` | `commander.snapshot`, `devices.commander` | ported; capture wired into step 4 (new install or `--capture-snapshot`) |
| `commander_links.py` | `commander.links` (+ read-only `devices.eps.CommanderLinks`) | ported; wired into step 4 with the drift restart |
| `commander_connect.py`, `cloud_snapshot.py` | `commander.connect` | ported; wired into step 4 behind the onboarding switch, **unreachable until the cloud tunnel is ported** |
| `cloud_tunnel.py` | `cloudtunnel` | ported except the serve loop (needs SSH reverse forwards); an onboarding run stops in step 1 |
| `secondary_fep.py`, `register_app.py` | `fep`, `commander.registerapp` | ported; wired into step 4 (no Commander Connect cloud sender) |
| `pos/emv_keys.py` | `commander.emv` | **ported 2026-10-28** (tables, cashback row, card-table undo, the downloads confirmed from the host packet log); wired into step 4 and the pinpad link |
| `close_day.py` | `closeday` | ported **including the register pass** (`PosRegisterPass`, 2026-10-28); `CloseDay.forRig` uses it |
| `upgrade/new_install.py`, `psi_init.py`, `cat/launch/upgrade.py` | `upgrade`, `launch.InstallCommanderBuild` | **ported 2026-10-28**: download (or the newest staged build), PSI install, `psi-init --no-pairing`, pairing after provision. classic (AU) and VHQ: unavailable |
| `build/` incl. `installed.py` and the DPAPI credential files | `build` | ported: discovery, listing, verified download, `Installed`, `StoredArtifactoryCredentials` (env, then `.secrets/artifactory.credentials`, then Monarch's) |
| `collect.py`, `gitutil.py` | `collect` | ported; freshness on the run's test-plan branch, fetch on |
| `cat/plans.py`, `selection.py`, `requirements.py`, seed | `plans` | ported |
| `cat/atlassian.py`, `cat/xray/` | `atlassian`, `xray` | ported; never sent live |
| `report/`, `results/`, `connections.py`, `readiness.py` | `report`, `results`, `connections` | ported; **printer readiness lines and the Receipt Printer serial row ported 2026-10-28** |
| `preflight/`, `recovery/`, `provision/` | same names | ported |
| `coverage/` | `coverage` | ported; launch wiring not done (a run asking for coverage stops in step 1) |
| `pos/` (locators, driver, pages, fake, pinpad double, receipt) | `pos.*` | ported |
| `pats/` (case, scenario, elements, feature, runner, 71 handlers, bdd) | `pats.*` | ported; **step-library `.feature` cases (`petrotest.authoring.steps`, 46 phrases) run as `CAT_STEP` actions (2026-10-28)**; `VALIDATE_TRANSACTION` reads the Commander's transactions for real; **the receipt read follows `_pos_receipt` (TRAN# rule, print mark, settle) and `REFUND_WITH_TRANS_MAP` reads the STAN from the case's validated TLog (2026-10-28, after run f6b5c0596966)** |
| `devices/petro_forecourt.py` | `devices.forecourt` | ported; **session listeners, DCR link recovery and pump teardown wired into step 5 (`PetroSession`), Petro port holds (`PetroPorts`) 2026-10-28** |
| `devices/legacy_rmi.py`, `viperpay.py`, `topaz_printer.py`, `csite.py`, `eps.py`, `suspended.py`, `buypass_host.py`, `fepsim_legacy.py` | `devices.*` | ported; **the printer port is held from step 1 to step 5 (`PrinterHold`)**; FepsimLegacy not bound at launch |
| `cat/launch/` (seven steps) | `launch` | **all seven steps run on a rig path without an "unavailable" stop for the lab rig** (viperpay pinpad, printer on COM1, no onboarding, no coverage). Step 4 follows `commander.py` stage for stage (§6 item 1 lists the order) |
| `cat/sims/` incl. `jab.py`, `java.py`, `suite_base.py`, `fetch.py` | `sims`, `sims.base` | **ported 2026-10-28**: Access Bridge clicks over JNA, window minimise, Commander base read, Java 17 set choice, simulator build fetch/replace with the `.cat-build.json` marker |
| `cat/schedule.py`, `cat/setup/`, `authoring/`, `cat/web/` | `runservice`, `setup`, `authoring`, `web` | ported as before (web: every page; Tools empty) |
| `tools/rig/run_plan.py` | `launch.LaunchCommand` + `LastConfig` | **ported 2026-10-28**: every flag; defaults from `~/.cat/java/last-config.json` then the Python's `~/.cat/last-config.json` (read-only) |
| `recorder/` (P11), `cat/telemetry` socket, `cat/state`, `startup.py`, `cat/sims/fepsim.py` (FEPSIM v2 superseding BUYPASS Host) | — | not ported |

## 6. Open items (priority order)

1. **Run Cadence from a clean install on the lab rig** (the command in §1, from
   `C:\gitrepos\CAT-Java` as the working directory: pulled tests land under `<cwd>\tests\suites\pulled`).
   Preconditions checked 2026-10-28: Access Bridge enabled (`~/.accessibility.properties`),
   `C:\CICD\Builds\Solutions134\buypass\` complete (so no Artifactory login is needed), the
   staged simulators carry `.cat-build.json` master 129 (quick pass in step 1), the Jira token
   file present, no PATS or Python CAT run live. Expected step-4 order: install → psi-init
   --no-pairing → provision → psi-init (pairing) → provision → commander-prep → commander-init
   → secondary-fep none → payment-links (+drift restart) → emv-keys --tables always →
   register-app → close-day → pinpad link → payment-ready (warn) → snapshot capture
   (`Solutions134`) → installed build. Watch first: step 3's LoySim/MPPA clicks (a "clicked"
   detail on a disabled button is not proof the host started: look at the windows), the
   pairing, and emv-keys' host-packet confirmation.
2. **Petro dropped every DCR soft key in run dec5794107fa.** The Java's bytes equal the Python's (parity test `ForecourtWireParityTest`), and after the run the Python's own sender (`petro_forecourt._send(controller, 1053, encode_dcr_key(1,'L2'))`) was dropped the same way: Petro accepted the connection and never logged `handleKeyData`. That Petro instance (started by run b34a01ad8176 at 16:42, kept across a PSI reinstall and three commander-inits) was stopped at 21:40 so the next run starts a fresh one. If a fresh Petro also drops keys, look at Petro's `DcrClientAdapter$ClientRequestHandler` (javap) for the state check.
3. **Commander Connect:** port `cloud_tunnel.py`'s serve loop (SSH reverse forwards and the
   gateway mode in `Transport`), then the onboarding path (commander-connect, csite-reset,
   cloud-snapshot restore) is reachable. Every migration plan must pass onboarded.
4. **FEPSIM v2 superseding BUYPASS Host** (`cat/sims/fepsim.py`, `simulators.py` 260-290,
   `emv-keys --host-packets`): not ported; Java always starts BUYPASS Host.
5. **Coverage** launch wiring (`cat/launch/coverage.py`); a run asking for it stops in step 1.
6. **Failed-validation evidence capture** in step 5 (`PosDriver.capture` needs an
   `ArtifactSink`; `BrowserConnector` builds none).
7. **A step-library case whose fixture is unavailable** is recorded as failed; the Python
   records a setup error. Consider an `error` outcome.
8. **`LOGIN`/`LOGOUT` on a fake-POS run** reach the pos-shim stand-in at `localhost:1`
   (`DeviceUnreachable`), and the report actions have no `RegisterPos` bound.
9. **Two `CommanderLinks` classes** (`commander.links`, `devices.eps`): consolidate.
10. **Partial runs** (`--tags/--only/--from`) are parsed but `RunLauncher` refuses them.
11. **Web UI**: its Start run gets no default simulators/connections (decided in
    `LaunchCommand`, not at run start); `UpgradeService` still says psi-init is "not built".
12. **`cat-java preflight` without `--no-repair` drives the live POS**; only with the rig
    owner's go-ahead.
13. **Step 3 is stricter than the Python**: a NEEDS_ATTENTION row fails the step (decision in
    §2). If the Access Bridge click fails on the real LoySims, the run stops before the install.
14. **Windows PowerShell 5.1 quoting** (`-EncodedCommand` in the sims port) and Playwright's
    browser download (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` in `run.cmd`/tests) as before.
14. Incidents, all harmless, kept for honesty: 2026-10-24's three (a `client=nosuch` read of
    the lab shim; one TCP connect to the example rig's documentation address; a refused
    kill-by-image command). 2026-10-28: the fakepos agent's first run moved
    `C:\gitrepos\petroapps` to `master` and back (the freshness check used `master`; fixed,
    the checkout ended on `PETROCORE-141728` and clean); a leftover CAT-Java web server from
    10-24 (PID 30372) holds `target\cat-java.jar`, so `build.cmd` cannot repackage until it is
    closed. Run from the classes instead:
    `java -cp "target\classes;target\lib\*" com.verifone.cat.Main launch ...`.

## 7. Lessons (read before orchestrating again)

- Build from **PowerShell with full paths**: `cmd /c "cd /d <dir> && <dir>\build.cmd"`.
  Through Git Bash it can exit 0 without building.
- PowerShell 5.1 `Get-Content -Raw` reads UTF-8 as ANSI; edit files with the Edit/Write
  tools or a UTF-8-safe script. Working copies are CRLF (`text=auto`); a script that edits
  them must normalise and write back LF.
- Never stop processes by image name on the controller: PATS's simulators are Java too.
- Give parallel agents **disjoint packages** and say who owns each shared file. Two slices
  rewriting `CommanderConfigurationStep` from opposite ends produced a four-file conflict
  that took a dedicated merge agent; next time, one agent owns a step file end to end.
- A background Maven run on a checkout must finish before another starts there; agents
  worked in worktrees so their builds never collided with main's.
- Have a **read-only reviewer** compare the merged launch against the Python before a rig
  run. The 2026-10-28 review found the `master` freshness checkout, the missing Artifactory
  login path, two "unavailable" guards placed after the destructive install, and the
  unheld printer port: none of which any slice's tests caught.
- Check agents' parity claims against the Python on real data; the fake-POS plan runs are
  the first evidence that the whole runner path holds together.
- Haiku 5.5's safeguard flags Commander/credential/payment-host code; Opus finishes it.

## 8. Log

- **2026-10-24** PoC, then waves 1-14 in one orchestrated session: 220 → 3,111 tests.
- **2026-10-28, wave 15** (orchestrated, 11 slices on Opus, 27 commits): psiinit (PSI
  install flow, psi-init), jab (Access Bridge over JNA), step4wire (step 4 after the inits,
  DayClose, PinpadLink, Cleanup), emvkeys, step5 (real fixtures, PetroSession, PetroPorts),
  cli (run_plan flags, LastConfig, Main table), fakepos (first whole-plan fake-POS runs,
  step-library cases), closereg (register pass), step1 (printer readiness, Commander base,
  Java sets, simulator fetch), the merge-and-wire pass, prerig (the review's fixes).
  then capk (CAPK re-dating, after the first rig run's step 3 failed on it), bringup (the browser override, after run 2), and after runs 4-5: the shim client name, petroapps reference folders, the pairing navigation race, the receipt read, the sale STAN source, the recovery ladder. 3,111 → 3,504 tests. Goal set by the rig owner: Cadence from a clean install (§1).
