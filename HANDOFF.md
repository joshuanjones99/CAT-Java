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
| Build | Maven via the wrapper. Dependencies today: Javalin, Jackson (JSON + YAML), slf4j-simple, JUnit 5. New dependencies are the orchestrator's call, not an agent's. |
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
| Builds and tests on the CICD Java 17 | `build.cmd`, 220 tests, 0 failures, end of wave 1, 2026-10-24 |
| Shim health/screen/keys read from the lab register (Reg101) | live, 2026-10-24, PoC and again on merged wave 1 |
| `--rig lab` loads under the strict Java rig schema | live `lab.yaml`, 2026-10-24 (also a test) |
| Key press from CAT-Java reaching the POS | **not yet exercised** |

## 5. Port map

Status: `todo`, `wave N` (in progress), `ported` (merged, tests pass off-rig),
`rig-verified`.

| Python (CAT) | Java package | Status |
|---|---|---|
| `petrotest/devices/pos_shim.py` (read + keys) | `posshim` | ported (PoC), read path live-checked |
| `petrotest/rig/` (models, loader, duration, lease) | `rig` | ported (ppk.py skipped). Strict like pydantic `extra=forbid`; Jackson reports only the first error; lease tested in one JVM only |
| `petrotest/devices/config_shim.py` (Commander CGILink) | `devices.commander` | **blocked**: Haiku 5.5's cyber safeguard stopped two agents (login/OTP/lab credential). ~700 uncommitted lines in worktree `CAT-Java-wt/commander`. Needs a different model or hand work — rig owner's call |
| `cat/connections.py`, `cat/readiness.py` | `connections` | ported; not wired to the rig model yet (Endpoints takes plain hosts). Xray/printer/VATS rows left as TODOs |
| `cat/plans.py`, `petrotest/selection.py`, `cat/requirements.py`, `cat/seed/` | `plans` | ported offline (seed: 68 plans; 135127 = 358 cases, checked against the Python seed). Confluence/Jira fetch and page parsers left for `atlassian`; selection's PATS-XML reader behind a `CaseActions` interface |
| `petrotest/report/` + `cat/report/events.py` writer | `report` | ported except `html.py` and case_record's pytest glue |
| `cat/web/` layout, pages shell | `web` | shell ported: sections, Run tabs, placeholders, POS page. Not built: every page body, Settings appearance, the CAT monogram |
| `petrotest/preflight/` | `preflight` | todo |
| `petrotest/recovery/` | `recovery` | todo |
| `petrotest/provision/` | `provision` | todo |
| `petrotest/pos/` (Playwright page objects; locators generated) | `pos` | todo — needs Playwright for Java |
| `petrotest/pats/` (PATS -TestCase.xml runner) | `pats` | todo |
| `petrotest/validate/` (receipt/TLog snapshots) | `validate` | todo |
| `petrotest/build/`, `upgrade/` (Artifactory, PSI install) | `build`, `upgrade` | todo |
| `cat/launch/` (the seven steps) | `launch` | todo |
| `cat/sims/` (simulator supervisor, Access Bridge) | `sims` | todo — Access Bridge needs JNA |
| `cat/xray/`, `cat/atlassian.py` | `xray`, `atlassian` | todo |
| `petrotest/authoring/`, `.feature` step library | `authoring` | todo — Cucumber-JVM |
| `petrotest/coverage/` (JaCoCo) | `coverage` | todo |

## 6. Open items

1. **GitHub repo not created.** `gh` is not logged in on TPA-AUTOLAB1. After
   `gh auth login`: `gh repo create joshuanjones99/CAT-Java --private --source . --push`.
2. Press a harmless key from the PoC page to prove the key path from Java.
3. During a fact-check on 2026-10-24 the orchestrator sent the live lab shim one
   `GET /pos/screen?client=nosuch`, which seeds an empty holder named `nosuch` in the
   shim. Harmless (the register's client list is unchanged); it clears on shim restart.

## 7. Log

- **2026-10-24** PoC: pos-shim client + web page on Java 17, read path checked live.
  Wave 1 started: rig, commander, connections, plans, report, web shell.
- **2026-10-24** Wave 1 merged: rig, connections, plans, report, web shell (220 tests).
  Commander blocked by the model safeguard. Lesson: run `build.cmd` from PowerShell
  with full paths; through Git Bash it can exit 0 without building. The orchestrator
  must never stop processes by image name on the controller — PATS simulators are java too.
