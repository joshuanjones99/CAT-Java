# CAT-Java handoff

**Read this first.** It is the single record of what CAT-Java is, what is built, what is
proven, and what comes next. Update it at the end of every wave of work.

If this document disagrees with the tree, the tree is right.

## 1. What this is

A port of the Python CAT (`C:\gitrepos\CAT`, github.com/joshuanjones99/CAT) to Java 17,
with a web UI only (no TUI). The Python CAT stays the working harness until CAT-Java
reaches parity on a rig; nothing here replaces it yet.

## 2. Decisions (rig owner, 2026-10-24)

| Decision | Detail |
|---|---|
| Java 17 | `C:\CICD\Tools\Java17\jdk-17.0.5+8` (Temurin 17.0.5, Java v1.2 in Artifactory). `build.cmd`/`run.cmd` use it; the Maven enforcer refuses any other major version. |
| POS over pos-shim HTTP | No RMI on the controller. pos-shim (built and provisioned by the Python CAT) stays on the register. |
| Web UI | Javalin 6 + htmx 2, server-rendered fragments, localhost only. Layout follows the Python CAT's `docs/web-ui.md`. |
| Build | Maven via the wrapper. Dependencies: Javalin, Jackson (JSON + YAML), slf4j-simple, Playwright for Java 1.49.0 (CDP to the POS), JSch (mwiede fork 0.2.20, SSH/SFTP), JUnit 5. A thin `target/cat-java.jar` plus `target/lib/` (Playwright's driver bundle is ~200 MB, so no fat jar). New dependencies are the orchestrator's call, not an agent's. |
| Rig files | Not committed here. Read from `$PETROTEST_RIGS_DIR`, else `C:\gitrepos\CAT\rigs`. |
| Nothing proprietary | No Verifone jars, petroapps sources or real credentials in this repo. |

## 3. How work is done

- An orchestrator session plans waves; Haiku agents do the coding, each in its own git
  worktree (`C:\gitrepos\CAT-Java-wt\<slice>`, branch `wave<N>/<slice>`), owning disjoint
  packages. The orchestrator reviews, merges to `main`, runs the full build, and updates
  this file.
- Each slice ports named Python modules. The Python source is the specification; where
  it is rig-verified, the port must keep its behaviour and say in its Javadoc that the
  Java port itself is UNVERIFIED AGAINST HARDWARE until run on a rig.
- Evidence discipline is the Python CAT's: a fake-backed test is evidence about this
  repository, not about a rig.

## 4. Verified state

| What | Evidence |
|---|---|
| Builds and tests on the CICD Java 17 | `build.cmd`, 2,424 tests, 0 failures, end of wave 10, 2026-10-24; four lint gates (no sleep, no raw locators, no IP literals, UI only in web/) run as tests |
| Shim health/screen/keys read from the lab register (Reg101) | live, 2026-10-24, PoC and again on merged wave 1 |
| `--rig lab` loads under the strict Java rig schema | live `lab.yaml`, 2026-10-24 (also a test) |
| Read-only preflight (`--no-repair`) against the lab | live 2026-10-24: pos_idle FAIL (a ticket really was open), unsupplied surfaces ERROR, drawer PASS |
| Results page over the real `C:\CICD\Results\CAT` (320 folders, 205 runs) | live 2026-10-24: cold index 34 s, warm 0.19 s; run 202ca297ae18 reads TAX 71132 132/2, as the Python recorded |
| summary.html from a real run's events (202ca297ae18) | 2026-10-24: all 685 text lines equal to the Python's own summary.html; only `&#39;` vs `&#x27;` differs |
| Canonical TLog for both fixture TLogs | 2026-10-24: identical to the Python's `tlog.canonicalise` output (LF) |
| `cat-java preflight --rig lab --no-repair` | live 2026-10-24, same verdicts as the class-level run |
| POS locator catalogue equals the Python's | 2026-10-24: 852 constants on both sides; LocatorsParityTest compares every name, field, rendered selector and registry against the live Python import (runs whenever C:\gitrepos\CAT exists) |
| CGILink routing without the trailing `?` | 2026-10-24: the lab Commander answers `POST /cgi-bin/CGILink` and `.../CGILink?` identically (unauthenticated empty request, 400-byte InternalError fault both ways). Java's HttpClient cannot send the `?` the Python sends |
| Config tab over the real plan cache | live 2026-10-24 (GET only; `~/.cat` redirected to scratch) |
| Key press from CAT-Java reaching the POS | **not yet exercised** |

## 5. Port map

Status: `todo`, `wave N` (in progress), `ported` (merged, tests pass off-rig),
`rig-verified`.

| Python (CAT) | Java package | Status |
|---|---|---|
| `petrotest/devices/pos_shim.py` (read + keys) | `posshim` | ported (PoC), read path live-checked |
| `petrotest/rig/` (models, loader, duration, lease) | `rig` | ported (ppk.py skipped). Strict like pydantic `extra=forbid`; Jackson reports only the first error; lease tested in one JVM only |
| `petrotest/devices/config_shim.py` (Commander CGILink) | `devices.commander` | ported on Opus 5.5 (Haiku's safeguard stopped two attempts): transport, OTP/re-login/15-attempt retry, reads, writes, inits, Connect, close-day, upgrade commands. `devices/commander.py` (SSH OTP reader factory): todo |
| `cat/connections.py`, `cat/readiness.py` | `connections` | ported; not wired to the rig model yet (Endpoints takes plain hosts). Xray/printer/VATS rows left as TODOs |
| `cat/plans.py`, `petrotest/selection.py`, `cat/requirements.py`, `cat/seed/` | `plans` | ported offline (seed: 68 plans; 135127 = 358 cases, checked against the Python seed). Confluence/Jira fetch and page parsers left for `atlassian`; selection's PATS-XML reader behind a `CaseActions` interface |
| `petrotest/report/` + `cat/report/events.py` writer | `report` | ported, including `html.py` (summary.html); case_record's pytest glue is the runner's |
| `cat/results/`, `cat/web/pages/results.py` | `results`, `web` | ported: store, index, stats, run list, run detail, last 500 log lines. Validations tab, async index: todo |
| `cat/web/pages/config.py` | `runconfig`, `web` | ported: plans, run scope, Needs, saved choices. Downloads, Commander Connect, Execution, branch, Refresh: placeholders |
| `cat/report/events.py`, `run_log.py`, `run.ended` | `report.events` | ported (file transport only) |
| `cat/web/pages/run.py`, `simulators.py` | `runservice`, `web` | ported: Run Overview/Log (start, pause, stop, new run, counters, log tail) and Launch Simulators (ticks, Start, red Stop, logs). Main passes `--rig` since wave 5 |
| `cat/setup/`, `pages/setup.py` | `setup`, `web` | ported: 13 detect-only checks with fix hints (Python-only checks replaced by JDK/target/Chromium ones); no repairs |
| `pages/settings.py`, theme prefs | `web` | ported: Appearance (`~/.cat/java/web-settings.json`, cookie), places list. Tools stays empty: the Python's Manual page has no tools |
| `petrotest/authoring/`, `cat/authoring/` | `authoring` | ported: step library (parity-tested), checks, sessions, read-only Jira ingest. draft.py (Claude), replay, recorded, publish writer, CLI: todo |
| `cat/web/` layout, pages shell | `web` | shell ported: sections, Run tabs, placeholders, POS page. Not built: every page body, Settings appearance, the CAT monogram |
| `petrotest/preflight/` | `preflight` | ported: framework, runner, POS checks via pos-shim screen(). pumps_idle, eps_reachable, config_loaded, build_matches and every repair await forecourt/SSH/Commander/POS-control clients |
| `petrotest/recovery/` | `recovery` | ported: CAPTURE (no files yet), PREFLIGHT, SOFT and VOID (pos-shim halves; CDP halves todo). RESET, REPROVISION raise DeviceUnavailable |
| `petrotest/provision/transport.py` | `provision.transport` | ported over JSch: AutoAddPolicy-equivalent host keys (as the Python), publickey only, SELinux step. Never run against a box |
| `petrotest/provision/` (rest) | `provision` | ported: artifacts, manifest (digests equal the Python's), runner, register bring-up, checklist, render, offline stage; `ProvisionCommand`. browser_override, libcups stub, Chrome tarball, shim jar build stay with the Python |
| `petrotest/pos/locators.py`, `locator_types.py` | `pos` | ported: generated `Locators.java` (`pos.gen.GenLocators`, staleness test), parity-tested against the Python |
| `petrotest/pos/receipt.py`, `pinpad_double.py` | `pos.receipt`, `pos.pinpad` | ported (DOM driver behind an interface; `LOOPBACK/` prefix kept) |
| `petrotest/pos/driver.py`, `pages/base.py` | `pos.driver`, `pos.pages` | ported (CDP, frames, bounded waits, capture); browser tests run on Playwright's Chromium 1148 |
| `petrotest/pos/fake.py` | `pos.fake` | ported (loopback, IDs from Locators, pinpad double wired) |
| `petrotest/pos/pages/*` (page objects) | `pos.pages` | ported: Pos, overlays, keypad, menus, fuel, item/express panels, device monitoring, pairing, sign-on. Tested on setContent markup and the fake POS, not a register. ProductMenu, SCOMenu untested |
| `petrotest/pos/plugin.py` | — | pytest glue; becomes the runner's |
| `petrotest/pats/case.py`, `elements.py`, `scenario.py` | `pats` | ported (filesystem; git-blob CaseStore todo) |
| `petrotest/pats/feature.py` | `pats.feature` | ported; all 38 Charity .feature files read to the same 337 actions as the Python (golden from the Python) |
| `petrotest/pats/runner/` core, registry, fields | `pats.runner` | framework + hooks + one unified Action/Fields/Elements (Fields equal the Python's 71 entries); `PatsCaseLoader` (.xml/.scenario/.feature). Handler families ported, each behind its own `*Family.register(registry, ctx)`: pos_ui (13 actions), sale (20), devices (18), http (SEND_HTTP_REQUEST, VALIDATE_PAYLOAD), cloud_settle, configuration (8), inits (CONTROL_FUNCTION + reply pause), validation (5). **Not yet wired together**: shared state, seams (CaseFiles, CommanderReads, CloudSite, DCR, fuel pumps, SaleRmi) and step 5 are wave 11. bdd.py actions (PREPAY_FUEL_*), widget_keys.json table, C-Site client: todo |
| `petrotest/validate/` (receipt/TLog snapshots) | `validate` | ported: receipt (normalisers, snapshots, rule) and TLog/packets. Report hooks (record_receipt etc.) belong to the test runner, not ported |
| `petrotest/build/` | `build` | ported: local discovery, Artifactory listing, verified download. Credentials from ARTIFACTORY_USER/PASSWORD only (DPAPI store todo). build_contents, viper merge, installed.py: todo |
| `petrotest/upgrade/` (PSI install) | `upgrade` | new_install ported; classic.py AU path and psi_init (needs Commander + POS driver): todo |
| `cat/launch/` (the seven steps) | `launch` | step 4 runs provision, commander-prep, commander-init on a rig then stops unavailable at Secondary FEPs/EMV/register app; step 7 does the day close; steps 2, 3, 6 wired (Connections, Start Simulators, Generate Report); 1 partial; 4, 5, 7 unavailable. Framework ported (sequence, events, run.log, pause/stop, run lock, run.ended, `LaunchCommand`). Every step needing an unported client reports unavailable, so no run can complete yet. Step 6 can now use `report.html` |
| `cat/sims/` (simulator supervisor, Access Bridge) | `sims` | registry (7 rows) + supervisor ported; `stop()` kills only its own handles; `stopRunning()` (the red Stop) matches by port/exact token, taskkill by PID. Click rows report NEEDS_ATTENTION (Access Bridge needs JNA). Never run against the real simulators yet |
| `cat/atlassian.py` + plans page parsers | `atlassian` | ported (read-only; credentials from `%USERPROFILE%\jira_api_token.properties` as the Python; redirects refused so Basic auth never follows one) |
| `cat/xray/` | `xray` | ported: reporter, client, summary, ADF, follower, sidecar CLI (`XraySidecar.main`). Not spawned by the launcher yet; attach.py waits on coverage. Never sent live |
| `petrotest/authoring/`, `.feature` step library | `authoring` | todo — Cucumber-JVM |
| `petrotest/devices/commander.py`, `commander_init.py` (+ recover_dcr_link), `commander_prep.py`, `commander_trust.py`, `payment_ready.py` | `commander` | ported on Opus 5.5; base config read live from petroapps. PetroPumps release, commander_links (PMC restart): todo |
| `petrotest/close_day.py` | `closeday` | ported (close, forced-AEOD undo, deadlock recovery, `CloseDayCommand`). **Register pass (nag clear, void, settle, prepays) not ported: a close with a nag up can still be cancelled** |
| `petrotest/collect.py`, `gitutil.py` | `collect` | ported (git as a child process, ff-only, never forced; tested on throwaway repos). gitauth, publish: todo |
| `petrotest/coverage/` (JaCoCo) | `coverage` | ported: components, tool finding, prepare/collect over Transport, report/zip; `LaunchConfig.coverage`. Launch-step wiring, overlay class extraction, summary.py: todo |

## 6. Open items

1. **GitHub repo not created.** `gh` is not logged in on TPA-AUTOLAB1. After
   `gh auth login`: `gh repo create joshuanjones99/CAT-Java --private --source . --push`.
2. Press a harmless key from the PoC page to prove the key path from Java.
3. During a fact-check on 2026-10-24 the orchestrator sent the live lab shim one
   `GET /pos/screen?client=nosuch`, which seeds an empty holder named `nosuch` in the
   shim. Harmless (the register's client list is unchanged); it clears on shim restart.

4. Fixed: CAT-Java writes only under `~/.cat/java/` (results index, saved config, web
   settings, and since wave 10 the plan cache, which reads the Python's `~/.cat` cache
   read-only as a fallback).
5. Preflight's `—` still shows as `�` when run from Git Bash (Java picks cp1252, which can
   encode it). Fold to ASCII in CLI output.
6. **`cat-java preflight` without `--no-repair` drives the live POS**: the pos_idle
   repair sends cancel then void through pos-shim, as the Python does. Not yet run
   against the lab with repair on; do it only with the rig owner's go-ahead.
7. Fixed: SupervisorTest teardown (a fake simulator outlived the test, so
   @TempDir could not be deleted). 4/4 isolated runs and the full build pass.
8. The sims agent found Windows PowerShell 5.1 drops the quotes of a WQL filter passed
   with `-Command` from Java's ProcessBuilder, so the port uses `-EncodedCommand`.
   The Python CAT's `_powershell` uses `-Command`; it is probably fine there (Python
   escapes the quotes) but is unchecked.

9. **Decided (rig owner, 2026-10-24):** a simulator that needs attention FAILS Start
   Simulators (stricter than the Python, which warns), and the operator gets a concise,
   direct warning naming the simulator and what to do. Done: one line per simulator,
   worded in one place (SimStatus), shown in run.log/events, Run → Overview and Launch
   Simulators.
10. `GET /simulators` probes localhost ports 41107 and 9001 (connect only) even in tests.

11. Subcommands: `cat-java [web] --rig R`, `preflight`, `launch`, `xray`.

12. Playwright for Java downloads every browser on first use. A wave 6 test run did so
    once (Chromium 1148, Firefox, WebKit into %LOCALAPPDATA%\ms-playwright, beside the
    Python CAT's Chromium 1228). Since then PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 is set for
    tests and run.cmd; browser tests skip when Chromium is absent.

## 7. Log

- **2026-10-24** PoC: pos-shim client + web page on Java 17, read path checked live.
  Wave 1 started: rig, commander, connections, plans, report, web shell.
- **2026-10-24** Wave 1 merged: rig, connections, plans, report, web shell (220 tests).
  Commander blocked by the model safeguard. Lesson: run `build.cmd` from PowerShell
  with full paths; through Git Bash it can exit 0 without building. The orchestrator
  must never stop processes by image name on the controller — PATS simulators are java too.
- **2026-10-24** Wave 2 merged: validate (receipt), preflight, atlassian, results +
  Results page, report.events, Config tab (522 tests). Orchestrator lesson: PowerShell 5.1
  `Get-Content -Raw` reads UTF-8 as ANSI; edit files with a UTF-8-safe tool.
- **2026-10-24** Wave 3 merged: fixes, TLog/packets, summary.html, recovery + POS
  control, launch framework, sims (761 tests). Parity checks against the Python on real
  data: summary.html and canonical TLog match.
- **2026-10-24** Wave 4 merged: build, Run Overview/Log, Launch Simulators, launch wiring,
  Xray (914 tests). Playwright and JSch added; packaging switched to thin jar + lib.
- **2026-10-24** Wave 5 merged: locator catalogue (852 = 852), SSH transport, POS receipt +
  pinpad double, Main wiring (1,022 tests). One agent committed under an invented identity;
  the orchestrator reset it to the repo's author before merging.
- **2026-10-24** Wave 6 merged (POS driver, fake POS, upgrade, provision, SSH check + Xray
  on runs) and wave 7 in part (Commander client on Opus, simulator warning): 1,327 tests.
- **2026-10-24** Wave 7 merged: Commander (Opus), simulator warning, page objects A+B
  (reconciled), coverage, small fixes + Java lint gates (1,468 tests).
- **2026-10-24** Wave 8 merged: PATS parsing, feature reader (parity 337/337), runner core,
  collect, close-day, Commander bring-up + step 4 (Opus) (1,799 tests).
- **2026-10-24** Waves 9-10 merged: PATS unification + hooks, Setup, Settings, authoring,
  plan-cache fix, and all PATS handler families (2,424 tests). Next: wire the families
  into one handler set, step 5 (Tests Run), then a whole plan against the fake POS.
