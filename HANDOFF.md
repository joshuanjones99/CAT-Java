# CAT-Java handoff

**Read this first, then `CLAUDE.md`.** This is the single record of what CAT-Java is, what
is built, what is proven, and what comes next. Update it whenever work lands.

If this document disagrees with the tree, the tree is right.

State as of **2026-10-29, late** (the wave-16 session). `main` builds clean: **4,139 tests, 0 failures,
0 errors, 0 skipped**. Wave 16 ported the rest of the Python CAT off-rig (§6a): all ten slices are merged and
their cross-slice hooks wired. **Everything wave 16 added is fake-backed evidence about this repository; none of
it has run on a rig.** **The lab Commander is still down** (§4's last rows, §6 item 1): its AAA service never came
back after the final proof run's PSI install, so the clean-install proof is not yet recorded; the best Cadence run
from Java is 258/264 (run `a4eda2bf8602`), the Python's own count. `cat-java install-resume` (new) is the way back
once the rig owner power-cycles it.

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

Honest progress (2026-10-29, after wave 16): every Python module is ported or recorded as not applicable (§5),
but wave 16's half of that is fake-backed only. **Rig-proven: the whole seven-step run** (before wave 16). Step 4 from a clean PSI install ran live three times (runs b34a01ad8176,
f6b5c0596966 and the final proof run, §4), and Cadence ran end to end from Java four times, the
last at 258/264 (run a4eda2bf8602), equal to the Python CAT's best. Not yet proven on a rig:
everything wave 16 added (Commander Connect and its tunnel, FEPSIM v2, VATS, coverage, classic AU, install-resume,
the web Start), Xray, every other plan.

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
| Builds and tests on the CICD Java 17 | `build.cmd`, 4,139 tests, 0 failures, 0 errors, 2026-10-29 (after wave 16 and its review fixes). Four lint gates run as tests |
| **Charity-Indoor 70960 on the fake POS, from the CLI** | run `fb8382bade89`, 2026-10-28: 39 cases, every one to a verdict (0 passed; the 12 POS+pinpad-only cases stop exactly where the Python's `pytest tests/suites/charity --fake-pos` stops them; the 27 others need devices a fake run has not). Evidence about the repository only |
| **Cadence 118517 on the fake POS, from the CLI** | run `81eeb3d11883`, 2026-10-28: 264 cases, every one to a verdict in 1 h 51 m, no internal error (0 passed: RMI, pumps, DCR, Commander and most Cadence elements are absent from the fake). Evidence about the repository only |
| **First rig run from Java: steps 1 and 2 complete live, step 3 started four of five simulators** | run `e272450ee561`, 2026-10-28 15:01 (lab rig, `launch --plan PETROCORE-118517 --upgrade new_install ...`): System Checks complete (rig lines, printer COM1 held, Commander Base_Level 057.02.00 read over SSH, the five staged simulators matched master 129 by their markers, lease taken), Connections complete; **PCATS01/02/03 Loyalty started and their "Start Host" buttons pressed through the Access Bridge, confirmed in each `LoySim_Debug.log` ("Loyalty FEP Simulator is ready. Port: 9010/9020/9030 ... Started VCMG Host")**; Petro Simulator Suite started and listening. BUYPASS Host failed: CAPK re-dating not ported (ported right after: `sims.Capk`, byte-identical entries to the Python's on the real jar; first launch of the built jar pending). Cleanup stopped the four and freed the lease; nothing reached the Commander |
| **Second rig run: the PSI install of Solutions 134 from Java, and the installer-password reset** | run `ac22943e6604`, 2026-10-28 15:19 (same command): steps 1-3 complete in 23 s with all five simulators ready (BUYPASS Host from the Java-built CAPK-re-dated jar, listening); step 4: build already downloaded (Artifactory not asked), **Install the Commander build PASS in 28 m 40 s, psi-init --no-pairing PASS**, provision PASS in 22 s, then **psi-init with pairing FAILED**: no CDP endpoint at the register's 19220, because the browser override (pushed Chromium, relay, boot hook) is not ported and provision had marked it skipped while still reporting PASS (hard rule 5). Cleanup stopped the simulators and freed the lease. Rig left: Commander on Solutions 134 with the lab password set, register wiped and unpaired |
| **Third rig run: provision with the browser override brought CDP up on the wiped register; pairing reached the OTP** | run `1a158f28d9cd`, 2026-10-28 16:09: install 27 m PASS, psi-init --no-pairing PASS, **provision PASS in 46 s with the pushed Chromium, the CDP relay and the boot hook (first live run of `BrowserOverride`)**, then pairing: `cpairingsvc` true, OTP fetched, and the register's page navigated while the Java checked for the refused page: Playwright "Execution context was destroyed" ended the stage (the Python's `is_present` would throw the same). Fixed in `PairingPage` (bounded retry of the snapshot queries across a navigation, commit 9a72dc1). Rig left: Commander on Solutions 134 with the lab password, register wiped, browser override on, unpaired |
| **Fourth rig run: STEP 4 COMPLETE from a clean install (45 m 16 s); step 5 reached** | run `b34a01ad8176`, 2026-10-28 16:42: install 27 m, psi-init, provision (browser override), **pairing PASS (3 m 10 s, the register rebooted and came back to its login screen)**, provision again, commander-prep, **commander-init 5 m 35 s (snapshot restore, base import, inits)**, secondary-fep, payment-links 5 m 17 s, **emv-keys 27 s (tables + CA keys confirmed from the host packet log)**, register-app, **close-day 17 s (register pass + close confirmed in the Commander log)**, pinpad link, payment-ready, snapshot capture, installed build Solutions134. Step 5: 79588, 124034, 79590 (Fuel DCR Init, 2 m 20 s), 37043 (POS-Login), 36857 and 118535 PASSED; then every key-driven case failed: the Java named the shim client "CAT" (the Python names none) and every `-Receipt.txt`/`-Tlog.xml` reference was looked for in the pulled folder, not petroapps' `TestCases/<key>/`. Both fixed (commit on main, 2026-10-28 17:4x). Run killed by the rig owner's decision at ~60 cases; simulators left running for the next run to adopt |
| **Fifth rig run: the first Cadence cases PASS on the rig from Java** | run `f6b5c0596966`, 2026-10-28 17:55: steps 1-3 adopted the running simulators; step 4 complete again in 46 m 55 s (install, psi-init, provision, pairing 3 m 19 s, provision, prep, init 5 m 22 s, secondary-fep, payment-links 5 m 19 s, emv-keys 33 s, close-day, pinpad link, payment-ready, snapshot); step 5: **79588, 124034, 79590, 37043, 36847, 36848 PASSED** (scan, EMV card sale, receipt and TLog validated: the first key presses and validations from Java on a rig). Then three classes: 36850's receipt validation compared the interim "PLU ITEM NOT FOUND" slip, not the sale receipt; 36853's `REFUND_WITH_TRANS_MAP` had no TLog source for the STAN; and no recovery walk ran after that failure, so its open ticket failed every later case ("reads 'Error Correct'", 60 s each). Killed at ~12 cases (rig owner's standing decision for a cascade). Fixes in progress on `wave15/receipts` and `wave15/step5fix` |
| **Sixth rig run: the first WHOLE Cadence plan from Java, all seven steps complete** | run `dec5794107fa`, 2026-10-28 19:23, `--upgrade skip` (rig owner: iterate with skip, finish with a clean install): step 4 in 2 m 15 s on the warm Commander (close-day's register pass cleared the killed run's ticket); **step 5: 264/264 cases to a verdict in 2 h 12 m, 180 passed, 84 failed**; Cleanup closed the day and freed the lease. Failures by class: 26 `WAIT_DCR_PROMPT` (Petro dropped every DCR soft key, the Python's identical bytes too: the Petro instance's state, see §6), 18 `NETWORK_REPORT` (report family's register unbound; fix in progress), 12 loyalty `WAIT_FOR_PROMPT` + ~22 receipt/TLog diffs (the pulled 55.02 case variants and baselines; fixed: local suites resolve first), 36879/37073 (the Python's own expected failures), 37072 SELECT_TILL, two cascades. First live proof of: the shim key path, receipt and TLog validation, the recovery ladder (one rung cleared an open ticket), the dialog watch |
| **Seventh rig run: 208/264** | run `5285e78aa370`, 2026-10-28 21:49, `--upgrade skip`, a fresh Petro: all seven steps complete, step 5 in 1 h 21 m, **208 passed, 56 failed**. DCR soft keys worked on the fresh Petro (the stale instance was the cause). Failures: 18 `NETWORK_REPORT` and the DCR-receipt layout class and the refund STAN (all fixed on main after this run's jar was built), 11 report-total validations downstream of the refund, 36878 (the register printed only the merchant copy), 36879/37073 (the Python's expected failures), 37072 SELECT_TILL, one cascade. **36880-36893 passed: the dialog watch cleared 36879's dialog; recovery cleared an open ticket once** |
| **Eighth rig run: 258/264, the Python's own pass count** | run `a4eda2bf8602`, 2026-10-28 23:26, `--upgrade skip`: all seven steps complete, step 5 in 1 h 19 m, **258 passed, 6 failed**: 36879 and 37073 (the Python's known product defects) and four `@plan_end` report cases (37031, 37033, 37036, 37063) whose verdicts the Python marks EXPECTED FAILURE once any earlier case failed (rig owner, 2026-09-23); the Java lacked that rule and reported them FAILED (ported in e6e76cb). So this run equals the Python's best Cadence result exactly. The refund case, the DCR cases and their receipts, the report cases and the dialog clears all passed |
| Ninth rig run: the install's root-access push stopped on a starting AAA tier | run `35c918e7595d`, 2026-10-29 01:13, clean install: after the PSI install's reboot, `Auto_Upgrade.jar` got `CGIPortal.InternalError: AAA service unavailable` and `RootAccess.isHardFault` ended the retry at once (the Python's `is_hard_fault` would too). Fixed in 05534e2: that text is the tier starting, not a refusal, so the push keeps retrying within its 25-minute budget. Nothing else ran; Cleanup skipped the close (psi-init not reached). Relaunched as the proof run at 01:36 |
| **Rig state 2026-10-29 02:00: the Commander's AAA never came back after run 35c918e7595d's install** | the PSI install itself completed (breadcrumb "Installation Complete. Remember to onboard...", the installer exited by itself with 0 this time where earlier installs were closed by CAT), the register installed and took its root access; the Commander's root-access push failed on connection errors while it rebooted (exit 549/511, retried) and then on `AAA service unavailable`, which the Java took as a hard fault (fixed, 05534e2). 25 minutes later CGILink still faults AAA on every request, root SSH has no key (the package was never pushed), so nothing over the network reaches it: the 2026-09-18 situation (Python HANDOFF §1.63/§1.73; then the cause was a package build 1028 did not ship; Solutions 134 installed cleanly three times today, so this boot is more likely the keystore race at first boot). The relaunch `895f7412dda0` stopped in step 1 on that fault. **Needs the rig owner: a power cycle (or console) of the Commander.** After AAA is up, the chain must resume at the root-access push (no root SSH yet), then psi-init: neither CAT has a resume command, so that is the next thing to build (§6 item 1a) |
| Java Access Bridge clicks against a real Swing window | 2026-10-28, a throwaway JFrame on this controller through the real `AccessBridgeClicker`: "clicked Load Data, Load Servers" in 5.3 s; a miss lists the available buttons. Says nothing about the LoySim/MPPA windows |
| pos-shim health/screen/keys **read** from the lab register (Reg101) | live, 2026-10-24 |
| `--rig lab` loads under the strict Java rig schema | live `lab.yaml`, 2026-10-24 |
| `cat-java preflight --rig lab --no-repair` | live 2026-10-24 |
| Results page over the real `C:\CICD\Results\CAT` | live 2026-10-24 |
| Config tab over the real plan cache | live 2026-10-24 (GET only) |
| summary.html, canonical TLog, locator catalogue (852), `.feature` reading (337 actions), PATS field table (71), snapshot logic, receipt key, CGILink `?` | parity against the Python's own data, 2026-10-24 (unchanged) |
| **Not yet exercised from Java** | a green Cadence; a DCR case passing (Petro dropped the keys); any Xray send; Commander Connect |

## 5. Port map

`ported` = merged, tests pass off-rig. `rig-verified` only where §4 says so. After wave 16 every Python module has a
Java counterpart or a recorded reason it has none (the TUI widgets, pytest-only plumbing, `startup.py`, the telemetry
socket, `rig/ppk.py`: JSch reads `.ppk` v2 itself).

| Python (CAT) | Java package | Status |
|---|---|---|
| `petrotest/rig/` | `rig` | ported (strict); ppk not needed |
| `devices/pos_shim.py` | `posshim` | ported; read path live-checked |
| `devices/config_shim.py`, `devices/commander.py` | `devices.commander`, `commander` | ported; rig-verified in step 4 (§4) |
| `commander_init.py`, `commander_prep.py`, `commander_trust.py`, `payment_ready.py` | `commander` | ported; rig-verified in step 4; **CLI commands added in wave 16** (`commander-prep`, `commander-init`, `commander-trust`, `payment-ready`) |
| `commander_snapshot.py`, `devices/suiteinfo.py` | `commander.snapshot` | ported |
| `commander_links.py` | `commander.links` (rules in `devices.eps.CommanderLinks`) | ported; `payment-links` command (wave 16) |
| `commander_connect.py`, `cloud_snapshot.py`, `devices/csite.py` | `commander.connect`, `devices.csite` | ported; **wave 16: `commander_cloud_config`, the csite-reset and cloud-snapshot stages and commands** |
| `cloud_tunnel.py`, `cat/launch/tunnel.py` | `cloudtunnel`, `launch.TunnelStart` | **ported in wave 16 incl. the serve loop, gateway and SSH reverse forwards**; unverified on hardware |
| `secondary_fep.py`, `register_app.py` | `fep`, `commander`, `commander.registerapp` | ported; `secondary-fep` (with the C-Site push) and `register-app` commands |
| `pos/emv_keys.py` | `commander.emv` | ported; rig-verified in step 4; host packets from FEPSIM v2 (wave 16) |
| `close_day.py` | `closeday` | ported incl. the register pass; rig-verified |
| `upgrade/` (new_install, psi_init, classic, vhq, cli) | `upgrade` | new_install and psi-init rig-verified; **classic (AU), VHQ (a stub, as in the Python) and the `upgrade` command ported in wave 16**; `NewInstall.resumeRootAccess` + `cat-java install-resume` (no Python equivalent) |
| `build/` incl. `nightly.py`, `overlay_merge.py`, `credentials.py` | `build` | ported; **wave 16: foundation/viper merge, `build`, `builds`, `artifactory-login`** (saved under `~/.cat/java`) |
| `collect.py`, `gitutil.py`, `gitauth.py`, `publish.py` | `collect`, `gitauth`, `publish` | ported; gitauth and `publish` (local branch; push only with `--push`) in wave 16 |
| `cat/plans.py`, `selection.py`, `requirements.py`, seed | `plans`, `launch.RunRequirements` | ported; requirements applied at run start (wave 16) |
| `cat/atlassian.py`, `cat/xray/` incl. `attach.py` | `atlassian`, `xray` | ported; never sent live; `xray-attach` (wave 16) |
| `report/`, `results/`, `connections.py`, `readiness.py` | `report`, `results`, `connections` | ported |
| `preflight/`, `recovery/`, `provision/` incl. `shim_stamp.source_stamp`, `tools/build_pos_shim.py` | same names | ported; **wave 16: recovery rung 5 reprovision, the pos-shim rebuild in step 4 (`build-pos-shim`), preflight repair only with `--repair` (the Python's default)** |
| `coverage/` and `cat/launch/coverage.py` | `coverage`, `launch.CoverageLaunch` | **ported in wave 16** (classes, sources, summary, report, `coverage` CLI, launch wiring) |
| `pos/` | `pos.*` | ported; failed-validation evidence (`CaseArtifacts`) in wave 16 |
| `pats/`, authored `.feature` cases | `pats.*` | ported; Scenario Outline Examples expand per row (wave 16) |
| `devices/petro_forecourt.py` and the other `devices/*` | `devices.*` | ported |
| `devices/vats/*`, `btt_agent/MenuAgent.java` | `devices.vats`, `launch.Vats*` | **ported in wave 16**; `agent.py` and `MenuAgent.java` ship verbatim as resources; `pinpad-clear` command |
| `cat/launch/` (run, plan, config, requirements, steps, printer, xray_sidecar, install_upgrade, sims_launch) | `launch`, `web` | ported; **wave 16: `plan.resolved`, the full-plan gate for `@plan_end`, partial runs, `provisionRegister`, receipt relay, Xray status lines, `keep_simulators`** |
| `cat/sims/` incl. `fepsim.py`, `petro_tls.py`, `staging.py` | `sims` | ported; **wave 16: FEPSIM v2, Petro TLS + RxtxAgent, SSLUtil on every launch, wrong-variant stop, staging, `fetch-sims`** |
| `cat/state`, `cat/web/`, `cat/setup/`, `cat/schedule.py`, `cat/web/autoclose.py` | `runservice`, `web`, `setup`, `shell` | **ported in wave 16**: the run board, Validations, receipt roll, Log filters, Config controls, Setup repair and sign-in, `cat-java setup`, auto-close |
| `recorder/`, `authoring/recorded.py`, `cat/authoring/` (draft, replay, CLI) | `recorder`, `authoring` | **ported in wave 16**: `record`, `generate` (writes a `.feature`, not a pytest module), `authoring`, `steps` |
| TUI widgets, `startup.py`, `cat/telemetry` socket, `cat/state/listener.py`, `orchestrator.py`, `clipboard.py`, `launch/relay.py` | — | not applicable (web-only; in-process runner) |

## 6. Open items (priority order)

1. **Record the clean-install proof run.** The Commander must first come back (power cycle or console; §4's
   last row). Then either resume, or rerun the whole clean install once the Commander is healthy. To resume:
   ```
   run.cmd install-resume --rig lab --build-dir C:\CICD\Builds\Solutions134\buypass
   run.cmd launch --rig lab --plan PETROCORE-118517 --upgrade skip --capture-snapshot --no-xray --no-coverage
   ```
   `install-resume` was added in wave 16 and is fake-tested only, including the lab's current state. It takes the
   run lease, pushes root access, waits for SSH, then reboots and waits for CGILink. It skips the reboot when nothing
   needed a push. Then it checks the pos-shim, runs psi-init `--no-pairing`, provision, psi-init with pairing and
   provision, and **stops after pairing by default**. `--continue` runs the rest of step 4 only when Petro (9001) and
   BUYPASS Host (41107) are listening. `launch` refuses a `new_install` that comes only from the Python CAT's saved
   file, so pass `--upgrade` explicitly. Run the command in
   §1 from `C:\gitrepos\CAT-Java` as the working directory (pulled tests land under `<cwd>\tests\suites\pulled`).
   Preconditions checked 2026-10-28: Access Bridge enabled, `C:\CICD\Builds\Solutions134\buypass\` complete, the
   staged simulators carry `.cat-build.json` master 129, the Jira token file present, no PATS or Python CAT run live.
   Watch first: step 3's LoySim/MPPA clicks, the pairing, emv-keys' host-packet confirmation.
2. **First rig run of what wave 16 changed** (all fake-backed so far). In that same run, check that:
   - step 4 runs its real-rig stages (`provisionRegister` is now set on every CLI and web run; without it step 4
     provisions nothing);
   - step 3 still starts BUYPASS Host on master 129's simulators (FEPSIM v2 takes over only when the staged
     `Petro_Simulator_Suite.jar` is v2);
   - `plan.resolved` gives the Not Run count and the full-plan gate leaves Cadence's `@plan_end` cases judged;
   - the receipt relay fills the run log and the web roll;
   - the pos-shim stamp check does not rebuild a current shim.
   Then a web Start against the rig: read the Pre-Run card's Upgrade line before pressing Start.
3. **Commander Connect onboarded.** The tunnel serve loop, `TunnelStart`, csite-reset and cloud-snapshot restore are
   ported (wave 16) but have never run. Every migration plan must pass onboarded.
4. **Petro dropped every DCR soft key in run dec5794107fa** (a stale Petro instance; a fresh one worked in run
   5285e78aa370). If it recurs, look at Petro's `DcrClientAdapter$ClientRequestHandler`.
5. **Small gaps left by wave 16:**
   - `AuthoringService` exposes no recording flag, so `run.gates(...)` and the shell's hold get `false` for it.
   - `PetroPorts` holds need a `RunContext`, so a Launch Simulators start cannot hand Petro's port holds to a later run.
   - The receipt relay polls the printer every 500 ms (slf4j-simple has no handlers); a listener on `TopazPrinter`
     would be exact.
   - The web Downloads' foundation pairing lookup for a viper-only build is not done.
   - `jab.main` (an at-rig diagnostic) is not ported.
   - Vestiges to tidy: `CommanderConfigurationStep.unported(config, tunnelPorted)`; `XrayClient.attachFile`'s third
     parameter (no multipart call in `AtlassianClient`); coverage-ready and sims' FEP hosts kept per run in weak maps
     instead of `RunContext` fields; `Main.serveWeb` (the pre-wave-16 web start) kept unused.
6. **Decisions for the rig owner:**
   - The Setup page's cashier repair defaults `PETROTEST_CASHIER_PASSWORD` to `"123"`, ported from the Python's
     `cat/setup/items/credentials.py` (the lab's standing till password). The Python CAT's CLAUDE.md lists the cashier
     PIN as env-var-only, while its own setup exports that default. Confirm, or drop the default.
   - Logins saved from Java (`artifactory-login`, the Setup page's Jira and Artifactory sign-ins) go under
     `~/.cat/java`, not the Python's `.secrets`/home files (HANDOFF §2's own-files rule).
   - The commander-dots logo was not copied (origin unconfirmed); the CAT monogram was.
7. **Test hygiene:** sims, VATS and PetroTls tests start real child JVMs from `C:\CICD\Tools\Java17` (the pattern
   predates wave 16; one flaked under the full build and was fixed in d8c450f); the VATS agent tests need a Python
   3.9+ and skip without one.
8. **`cat-java preflight --repair` drives the live POS**; only with the rig owner's go-ahead. Without `--repair` it
   is read-only (the Python's default since wave 16).
9. **Step 3 is stricter than the Python**: a NEEDS_ATTENTION row fails the step (decision in §2).
10. **Windows PowerShell 5.1 quoting** (`-EncodedCommand` in the sims port) and Playwright's browser download
    (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` in `run.cmd`/tests) as before.
11. Incidents, all harmless, kept for honesty: 2026-10-24's three (a `client=nosuch` read of the lab shim; one TCP
    connect to the example rig's documentation address; a refused kill-by-image command). 2026-10-28: the fakepos
    agent's first run moved `C:\gitrepos\petroapps` to `master` and back (fixed). A leftover CAT-Java web server
    (PID 37424 on 2026-10-29) holds `target\cat-java.jar`, so `build.cmd` on main cannot repackage while it runs;
    `build.cmd test` works, and so do worktrees.

## 6a. Wave 16: the rest of the port (merged 2026-10-29)

The user asked for all of the Python CAT to be ported while the Commander was down. Four read-only audits found the
gaps (§5 had overstated web, coverage and provision). Ten Opus slices ran in worktrees from the shared brief
`docs/wave16-brief.md`, with strict file ownership; each merged main before its final build, and the orchestrator
merged them one at a time with the full suite after each, wired the cross-slice hooks each report listed, and had a
read-only reviewer compare the merged launch with the Python.

| Slice | What landed |
|---|---|
| tunnel | `Transport` reverse forwards (JSch), `CloudTunnelServer` (the serve loop, keepalive, re-plan, gateway health), `cloud-tunnel` serves; `CatNetRelay.java` byte-identical to the Python's |
| upgrade | classic AU, VHQ, local build, foundation/viper overlay merge, `upgrade`, `build`, `builds`, `artifactory-login`, `render-payload`; later `NewInstall.resumeRootAccess` for `install-resume` |
| sims | FEPSIM v2, Secondary FEP host simulators (`FepHosts`), Petro TLS + RxtxAgent, SSLUtil on every launch, wrong-variant stop, `probeRunning`, staging and `fetch-sims`, `SimulatorState` events |
| coverage | class extraction, sources, summary with baseline, the report's running total, `coverage` CLI, `CoverageLaunch`, `xray-attach`; fixed `linePercent` refusing every real JaCoCo report (DOCTYPE) |
| webrun | the web Start as a real run (step 5 wired, network plan source, gates), `cat/state` fold, Overview board, step/case dialogs, Validations with a `difflib` port, receipt roll, Log filters, Results detail, preflight as a job |
| authoring | recorder, `record`, `generate` (a `.feature`), Draft with Claude, the replay gate, `authoring` and `steps` CLIs, `publish` to a local petroapps branch |
| step4 | `TunnelStart`, `CommanderCloudConfig`, csite-reset and cloud-snapshot stages and commands, pos-shim stamp + build, recovery rung 5, BDD skip, `install-resume`, eleven step-4 commands |
| vats | `devices.vats` (agent shipped verbatim, BTT, card server, relay, readiness, pinpad, session), the step helpers, `pinpad-clear` |
| launchcore | `PlanStart` (`plan.resolved`, partial selection, the full-plan gate), `RunRequirements`, Outline Examples, case evidence, setup errors for missing fixtures, receipt relay, Xray status lines and sidecar args, `keep_simulators`, `preflight --repair`, `provisionRegister` on every run, and most hooks |
| webshell | `shell` (one busy flag, auto-close, page heartbeat), `WebServe` (flags, browser, web log, setup on start), Config controls (branch, Downloads, Commander Connect, Execution, Refresh from Confluence, Jira case fetch), Setup repair and sign-in, `gitauth`, `cat-java setup`, Install Upgrade and Launch Simulators parity |

Commands after wave 16 (`Main`'s table): preflight, launch, provision, xray, close-day, commander-snapshot,
commander-connect, cloud-tunnel, psi-init, emv-keys, coverage, xray-attach, upgrade, build, builds,
artifactory-login, render-payload, fetch-sims, record, generate, steps, authoring, publish, pinpad-clear,
commander-prep, commander-init, payment-links, register-app, payment-ready, commander-trust, secondary-fep,
csite-reset, cloud-snapshot, build-pos-shim, install-resume, setup; no command = the web UI.

**The review of the merged launch (2026-10-29)** found nothing that would break the §1 clean-install command. It
found four problems and some smaller ones; the `reviewfix` branch fixed all of the following:
- **Saved upgrade mode.** A `launch` without `--upgrade` inherited the Python CAT's saved `new_install`, which would
  have meant a 45-minute PSI wipe. The CLI now prints the upgrade, coverage and Commander Connect choices with their
  source. It refuses `new_install` or `classic` when that comes only from the Python CAT's file. This is a deliberate
  deviation from run_plan.
- **`install-resume`** ran the post-pairing chain with no simulators up, took no run lease and always rebooted the
  Commander. It now stops after pairing, takes the lease, skips a needless reboot and refuses an ambiguous build.
- **Web Start after a Jira case fetch** dropped the Config page's choices, which would have moved petroapps to
  `master`.
- **The authoring replay** ran with a bare config. It now uses `RunStart.replayBase`, the Python's `replay_config`.
- **Exit code:** a run with failed cases now exits 1, as run_plan does. Before, it exited 0.
- **Ctrl+C:** a shutdown hook releases the run lease, stops only this run's simulators and writes `run.ended`.
- **PATS port 1072:** step 3's check now reads the resolved cases.

Left as low-severity notes:
- Step 2 ends the run on a fixed row that is down. The Python fails the step and goes on.
- The web Downloads drops a foundation pick.
- Web continuous runs restart the simulators each pass. The Python keeps them, using `keep_simulators` and Petro
  sinks.
- Three config builders coexist and may drift: `LaunchCommand.Options.toConfig`, `LaunchDefaults.fromSaved` and
  `ConfigForm.launchFields`.
- Some web tests build `ConfigForm.standard()` over the real `~/.cat/java/last-config.json`. They only read it.

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
  then capk (CAPK re-dating, after the first rig run's step 3 failed on it), bringup (the browser override, after run 2), and after runs 4-5: the shim client name, petroapps reference folders, the pairing navigation race, the receipt read, the sale STAN source, the recovery ladder. 3,111 → 3,568 tests. Goal set by the rig owner: Cadence from a clean install (§1).
- **2026-10-29, wave 16** (orchestrated, 10 slices on Opus, the Commander down): the rest of the port (§6a).
  3,568 → 4,139 tests, then a read-only review and its fixes. Two usage-limit interruptions; every slice resumed from its own commits.
