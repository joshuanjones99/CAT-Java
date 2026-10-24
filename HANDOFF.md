# CAT-Java handoff

**Read this first, then `CLAUDE.md`.** This is the single record of what CAT-Java is, what
is built, what is proven, and what comes next. Update it whenever work lands.

If this document disagrees with the tree, the tree is right.

State as of **2026-10-24, end of wave 14** (orchestrated session; work stopped here at the
rig owner's request). `main` builds clean: **3,111 tests, 0 failures, 0 skipped** on the
CICD Java 17. No worktrees or side branches remain. Nothing is pushed: the GitHub repo
does not exist yet (§6 item 1).

## 1. What this is

A port of the Python CAT (`C:\gitrepos\CAT`, github.com/joshuanjones99/CAT) to Java 17,
with a web UI only (no TUI). The Python CAT stays the working harness until CAT-Java
reaches parity **on a rig**; nothing here replaces it yet.

Honest progress (2026-10-24): code ported ≈ 75-80% of the Python by module; working end
to end ≈ 40-50% (no plan has completed a Java run, even on the fake POS); rig-proven ≈ 5%
(read-only live checks only, §4).

## 2. Decisions (rig owner, 2026-10-24)

| Decision | Detail |
|---|---|
| Java 17 | `C:\CICD\Tools\Java17\jdk-17.0.5+8` (Temurin 17.0.5, Java v1.2 in Artifactory). `build.cmd`/`run.cmd` use it; the Maven enforcer refuses any other major version. |
| POS over pos-shim HTTP | No RMI on the controller. pos-shim (built and provisioned by the Python CAT's tools) stays on the register; Java talks HTTP to it. |
| Web UI | Javalin 6 + htmx 2, server-rendered fragments, localhost only. Layout follows the Python CAT's `docs/web-ui.md`. |
| Build | Maven via the wrapper. Dependencies: Javalin, Jackson (JSON + YAML), slf4j-simple, Playwright for Java 1.49.0, JSch (mwiede 0.2.20), jSerialComm 2.11.0, JUnit 5. Thin `target/cat-java.jar` + `target/lib/` (Playwright's driver bundle is ~200 MB). New dependencies are the orchestrator's call. |
| Rig files | Not committed here. Read from `$PETROTEST_RIGS_DIR`, else `C:\gitrepos\CAT\rigs`. |
| Own files | CAT-Java writes only under `~/.cat/java/`. It may read the Python CAT's `~/.cat` plan cache read-only as a fallback, never write it. |
| Nothing proprietary | No Verifone jars, petroapps sources, private keys or real credentials. The only hardcoded credentials are the Python CAT's two documented lab carve-outs (Commander CGILink login, PSI answers). |
| Simulator needs attention | FAILS Start Simulators with one concise line per simulator (stricter than the Python, which warns). |
| Blocked slices | Any slice Haiku 5.5's cyber safeguard blocks is rerun on Opus 5.5 without asking. |

## 3. How to work here

- Read `CLAUDE.md` for the commands and the hard rules.
- The Python source is the specification. Port behaviour faithfully; record any deviation
  in the class Javadoc and in §6 here.
- Evidence discipline is the Python CAT's: a fake-backed test is evidence about this
  repository, not about a rig. Rig-touching classes carry `UNVERIFIED AGAINST HARDWARE
  (Java port)` until run on a rig. Never present a fake pass as hardware verification.
- How this was built (useful if you orchestrate again): one orchestrator session planned
  waves; Haiku agents each worked in a git worktree (`C:\gitrepos\CAT-Java-wt\<slice>`,
  branch `wave<N>/<slice>`) owning disjoint packages; the orchestrator reviewed each
  branch, merged it, ran the full build and checked parity against the Python on real
  data where it could. Lessons are in §7.

## 4. Verified state

| What | Evidence |
|---|---|
| Builds and tests on the CICD Java 17 | `build.cmd`, 3,111 tests, 0 failures, 0 skipped, 2026-10-24. Four lint gates run as tests (no sleep, no raw locators, no IP literals, UI only in web/) |
| pos-shim health/screen/keys **read** from the lab register (Reg101) | live, 2026-10-24 |
| `--rig lab` loads under the strict Java rig schema | live `lab.yaml`, 2026-10-24 (also a test) |
| `cat-java preflight --rig lab --no-repair` | live 2026-10-24: pos_idle FAIL (a ticket really was open), unsupplied surfaces ERROR, drawer PASS |
| Results page over the real `C:\CICD\Results\CAT` (320 folders, 205 runs) | live 2026-10-24: cold index 34 s, warm 0.19 s; run 202ca297ae18 reads TAX 71132 132/2 as the Python recorded |
| Config tab over the real plan cache | live 2026-10-24 (GET only) |
| summary.html from a real run's events | all 685 text lines equal the Python's own summary.html (only `&#39;` vs `&#x27;`) |
| Canonical TLog for both fixture TLogs | identical to the Python's `tlog.canonicalise` |
| POS locator catalogue | 852 constants = the Python's; `LocatorsParityTest` compares every name, field, selector and registry against a live Python import |
| `.feature` reading | all 38 Charity cases read to the same 337 actions as the Python (golden recorded from the Python) |
| PATS field table | `Fields` equals the Python's 71 entries; `StandardHandlers` registers exactly one handler for each of the 71 actions |
| Commander snapshot logic | difflib, normalise, differences, merge_document match goldens recorded from the Python; all 44 Python snapshot tests ported |
| Printer receipt key | `receiptKey` equals the Python's for the 61334 receipt |
| CGILink without the trailing `?` | the lab Commander answers `/cgi-bin/CGILink` and `.../CGILink?` identically (unauthenticated empty POST) |
| `launch --plan … --fake-pos` reaches step 5 | test only (`LaunchCommandReachesStepFiveTest`, throwaway petroapps clone) |
| **Not yet exercised from Java** | any key press, any case run on a rig, any install/provision/close-day/restore, any simulator start, any Xray send |

## 5. Port map

`ported` = merged, tests pass off-rig. Nothing is `rig-verified` yet.

| Python (CAT) | Java package | Status |
|---|---|---|
| `petrotest/rig/` | `rig` | ported (strict like pydantic `extra=forbid`; ppk.py skipped) |
| `devices/pos_shim.py` | `posshim` | ported incl. control (cancel, void, keys, enter-with-data, scan, pending); read path live-checked |
| `devices/config_shim.py`, `devices/commander.py` | `devices.commander`, `commander` | ported (Opus) |
| `commander_init.py` (+recover_dcr_link), `commander_prep.py`, `commander_trust.py`, `payment_ready.py` | `commander` | ported (Opus); base config read live from petroapps; trust restarts PMC after an import |
| `commander_snapshot.py`, `devices/suiteinfo.py` | `commander.snapshot`, `devices.commander` | ported (Opus); restore runs in CommanderInit before the base import |
| `commander_links.py` | `commander.links` (+ a read-only `devices.eps.CommanderLinks`) | ported — **two classes named CommanderLinks; consolidate (§6)** |
| `commander_connect.py`, `cloud_snapshot.py` | `commander.connect` | ported (Opus); `CommanderConnectCommand`; `LaunchConfig.commanderConnect` switch, not yet called by step 4 |
| `cloud_tunnel.py` | `cloudtunnel` | ported except the serve loop (needs SSH reverse forwards in `Transport`); `cloud-tunnel` without `--stop` exits 1 |
| `secondary_fep.py`, `register_app.py` | `fep`, `commander.registerapp` | ported; not yet called by step 4 |
| `close_day.py` | `closeday` | ported; **register pass (nag clear, void, settle, prepays) not ported — a close with a nag up can still be cancelled** |
| `collect.py`, `gitutil.py` | `collect` | ported (git child process, ff-only). gitauth, publish: todo |
| `cat/plans.py`, `selection.py`, `requirements.py`, seed | `plans` | ported |
| `cat/atlassian.py` | `atlassian` | ported (read-only + Xray writes; credentials from `%USERPROFILE%\jira_api_token.properties`) |
| `cat/xray/` | `xray` | ported; started per run as a separate process when enabled. Never sent live. attach.py: todo |
| `report/`, `cat/report/events.py`, `run_log.py`, `html.py` | `report`, `report.events`, `report.html` | ported |
| `cat/results/` | `results` | ported. Validations tab, async index: todo |
| `cat/connections.py`, `readiness.py` | `connections` | ported, derived from the rig; SSH login check wired |
| `preflight/` | `preflight` | ported; real sources for POS, EPS, suspended tickets, pumps (Petro). Repairs drive the POS (§6) |
| `recovery/` | `recovery` | ported: CAPTURE (no files), PREFLIGHT, SOFT, VOID (pos-shim halves). RESET, REPROVISION unavailable |
| `provision/` | `provision`, `provision.transport` | ported over JSch. browser_override, libcups stub, Chrome tarball, shim jar build stay with the Python |
| `upgrade/new_install.py` | `upgrade` | ported. classic.py (AU), vhq.py, psi_init.py: todo |
| `build/` | `build` | ported: discovery, Artifactory listing, verified download (creds from env; DPAPI store todo). build_contents, overlay merge, installed.py: todo |
| `coverage/` | `coverage` | ported; **launch-step wiring todo**; overlay class extraction, summary.py todo |
| `pos/locators.py`, `locator_types.py` | `pos`, `pos.gen` | ported; `Locators.java` generated by `pos.gen.GenLocators` |
| `pos/driver.py`, `pages/*` | `pos.driver`, `pos.pages` | ported (CDP, frames, bounded waits); tested on markup and the fake POS only |
| `pos/fake.py`, `pinpad_double.py`, `receipt.py` | `pos.fake`, `pos.pinpad`, `pos.receipt` | ported |
| `pats/` (case, scenario, elements, feature, runner core) | `pats`, `pats.feature`, `pats.runner` | ported; one Action/Fields/Elements model; `PatsCaseLoader` |
| `pats/runner/*` handler families + bdd | `pats.runner.handlers.*`, `StandardHandlers` | ported: all 71 actions |
| `pats/data/widget_keys.json` | `pats.WidgetKeys` | ported |
| `devices/petro_forecourt.py` | `devices.forecourt` | ported; wired into rig runs |
| `devices/legacy_rmi.py` | `devices.legacyrmi` | ported; wired (SaleRmi) |
| `devices/viperpay.py` | `devices.viperpay` | ported; wired |
| `devices/topaz_printer.py` | `devices.printer` | ported over jSerialComm; wired. Port lend/reclaim around tests: todo |
| `devices/csite.py` | `devices.csite` | ported; refuses `isDisableCsiteSSLVerification` (§6) |
| `devices/eps.py`, `suspended.py`, `buypass_host.py`, `fepsim_legacy.py` | `devices.eps`, `devices.suspended`, `devices.fep` | ported (Opus); FepsimLegacy not yet bound at launch |
| `cat/launch/` (seven steps) | `launch` | 1 System Checks ported (fake-POS skips as the Python); 2 Connections; 3 Start Simulators; 4 provision + prep + init + snapshot restore, then **unavailable** at Secondary FEPs / payment links / EMV keys / register app / pinpad; 5 Tests Run (StandardHandlers, collected cases, fake-POS mode); 6 Generate Report; 7 Cleanup (day close). `LaunchCommand` |
| `cat/sims/` | `sims` | registry + supervisor ported; **Access Bridge clicking not ported (needs JNA)** so click rows NEEDS_ATTENTION |
| `cat/schedule.py` | `runservice` | ported (in memory, as the Python) |
| `cat/setup/` | `setup` | ported, detect-only |
| `petrotest/authoring/`, `cat/authoring/` | `authoring` | ported: step library, checks, sessions, Jira ingest. draft.py (Claude), replay, recorded, publish writer: todo |
| `cat/web/` pages | `web` | Run (Overview/Config/Log, schedule, continuous), Results, Authoring, Install Upgrade + Downloads, Launch Simulators, Setup, Settings, POS. Tools empty (the Python's is too) |
| `recorder/` (P11), `emv_keys.py`, `cat/telemetry` socket, `cat/state`, `startup.py` | — | not ported |

## 6. Open items

1. **GitHub repo not created.** `gh` is not logged in on TPA-AUTOLAB1. After
   `gh auth login`: `gh repo create joshuanjones99/CAT-Java --private --source . --push`.
2. **First fake-POS plan run from the CLI.** `cat-java launch --plan PETROCORE-70960
   --fake-pos` should now get to step 5 (proven only in a test). Step 1 fetches and
   fast-forwards the real petroapps checkout, as the Python does — run it when that is OK.
   Then fix what breaks; compare per case with the Python's `pytest tests/suites/charity
   --fake-pos` (which itself errors at setup for most cases without `--rig`).
3. **TestsRunStep builds its own FixtureSource** and still throws for LEGACY_RMI, DCR and
   FUEL_PUMPS fixtures; switch it to `StandardHandlers.fixtureSource` so the new device
   clients reach the handlers.
4. **Step 4 TODOs**: call Commander Connect (`ConnectService.apply`), secondary FEPs,
   payment links, register app, EMV keys (not ported), pinpad link; bind FepsimLegacy;
   coverage prepare (step 4) and collect/report/attach (step 6).
5. **Main does not dispatch** `close-day`, `commander-snapshot`, `commander-connect`,
   `cloud-tunnel` (their `*Command.run(String[])` exist). It dispatches `web` (default),
   `preflight`, `launch`, `provision`, `xray`.
6. **Consolidate the two `CommanderLinks`** (`commander.links` and `devices.eps`).
7. **`cat-java preflight` without `--no-repair` drives the live POS** (cancel then void),
   as the Python does. Not yet run with repair on; only with the rig owner's go-ahead.
8. Close-day register pass not ported (see the port map).
9. Access Bridge (simulator button clicks) needs JNA — a dependency decision.
10. Java C-Site client refuses `isDisableCsiteSSLVerification=true`, which the Python
    honours. Decide if a lab needs it.
11. Printer port is held from connect to close; the Python lends it around pytest.
12. A device that fails to start in a rig run is logged and left unbound, not raised.
13. Schedule panel re-renders its time input every 2 s while waiting, losing a half-typed
    time.
14. `GET /simulators` probes the simulators' ready ports on localhost (connect only).
15. Windows PowerShell 5.1 drops the quotes of a WQL filter passed with `-Command` from
    Java's ProcessBuilder, so the sims port uses `-EncodedCommand`. The Python CAT's
    `_powershell` uses `-Command`; probably fine there, unchecked.
16. Playwright for Java downloads every browser on first use. One wave-6 test run did so
    (Chromium 1148, Firefox, WebKit into `%LOCALAPPDATA%\ms-playwright`, beside the
    Python CAT's Chromium 1228). `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` is now set for tests
    and `run.cmd`; browser tests skip when Chromium is absent.
17. Incidents, all harmless, recorded for honesty: the orchestrator sent the lab shim one
    `GET /pos/screen?client=nosuch` (seeds an empty holder until the shim restarts); one
    test once tried a single TCP connect to 10.0.0.160 (the example rig's documentation
    address — nothing answered, the test was fixed); one orchestrator command that would
    have killed every Java 17 process was refused by the permission check before it ran.

## 7. Lessons (read before orchestrating again)

- Build from **PowerShell with full paths**: `cmd /c "cd /d <dir> && <dir>\build.cmd"`.
  Through Git Bash it can exit 0 without building.
- PowerShell 5.1 `Get-Content -Raw` reads UTF-8 as ANSI; edit files with the Edit/Write
  tools or a UTF-8-safe script.
- Never stop processes by image name on the controller: PATS's simulators are Java too.
- Parallel agents produce order-dependent tests (concurrent futures, parallel ranges) and
  stale placeholders; expect one fix-up per merge wave.
- Brief agents with the author identity explicitly (one invented an identity once).
- Haiku 5.5's safeguard flags Commander/credential/payment-host code; Opus finishes it.
- Check agents' parity claims yourself against the Python on real data — that is where the
  real evidence in §4 came from.

## 8. Log

- **2026-10-24** PoC (pos-shim read + web page), then waves 1-14 in one orchestrated
  session: 220 → 522 → 761 → 914 → 1,022 → 1,217 → 1,468 → 1,799 → 2,424 → 2,497 → 2,843
  → 3,111 tests. Stopped at the end of wave 14 at the rig owner's request; all branches
  merged and worktrees removed.
