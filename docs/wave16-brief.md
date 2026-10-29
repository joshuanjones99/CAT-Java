# Wave 16 shared brief (CAT-Java): port the rest of the Python CAT

Goal (user, 2026-10-29): get ALL of the Python CAT's code ported to CAT-Java. The lab Commander is
DOWN: nothing in this wave may touch the rig, the lab, any device or the network beyond what the
existing tests already fake. This wave is off-rig porting only.

## Read first
- `<your worktree>\CLAUDE.md` (commands, hard rules, evidence discipline) and `HANDOFF.md` §2, §3, §5, §6, §7.
- The Python source at `C:\gitrepos\CAT\src` is the SPECIFICATION. Read the Python module (and its tests in
  `C:\gitrepos\CAT\tests\unit\`) before porting it. Port behaviour faithfully, including log wording an
  operator reads. Record any deviation in the class Javadoc.
- Do not read the Python's `pos/locators.py`, `analysis/*.csv`, rig runbooks, or DESIGN.md whole.

## Rules
1. Work ONLY in your worktree `C:\gitrepos\CAT-Java-wt\<slice>` on branch `wave16/<slice>`. Never touch
   `C:\gitrepos\CAT-Java` (main) or another slice's worktree. Never push.
2. Build from PowerShell with full paths, e.g.
   `cmd /c "cd /d C:\gitrepos\CAT-Java-wt\<slice> && C:\gitrepos\CAT-Java-wt\<slice>\build.cmd test -Dtest=FooTest"`.
   Iterate with targeted `-Dtest=`; run the FULL `build.cmd` (compile, all tests, package) once at the end and
   make it green (0 failures, 0 errors). Ten slices share this machine: never run two Maven builds at once in
   your worktree, and prefer targeted tests while iterating.
3. CLAUDE.md hard rules apply in full: tests never contact a real device/lab/network (loopback fakes,
   in-memory transports, @TempDir); no Thread.sleep in main code; no IP literals in src/main; no raw
   locators outside pos/Locators.java; Javalin/UI only in web/; unimplemented device surfaces throw
   DeviceUnavailable; CAT-Java writes only under ~/.cat/java/ (and never in tests); no proprietary material,
   no credentials beyond the two documented carve-outs; NO NEW DEPENDENCIES (Maven) - if you think one is
   unavoidable, stop that piece and say so in your report. Never stop processes by image name.
4. Rig-touching classes carry `UNVERIFIED AGAINST HARDWARE (Java port)` in their Javadoc.
5. Edit files with Edit/Write tools (not PowerShell Get-Content/Set-Content). Java 17: no pattern-matching
   switch, no virtual threads.
6. FILE OWNERSHIP (below) is strict. You may READ anything. You may EDIT only the files/packages your slice
   owns, plus: new files in packages nobody owns, and ONE-LINE additions to `Main.java`'s command table for
   your own new commands (and its Javadoc table row). If you need a change in a file another slice owns,
   do not make it: write the exact hook (file, method, code) in your report under "Hooks for the
   orchestrator" and code against an interface/static method you own so your branch compiles.
7. Commit in small logical commits on your branch with messages ending:
   ```
   Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01GpHRJo3Q7SWy4kDMvaKXkk
   ```
   Do NOT edit HANDOFF.md (the orchestrator updates it from your report); you may update CLAUDE.md's layout
   list only if you add a package.
8. Finish everything in your scope. If a piece is genuinely blocked, finish the rest and say exactly what
   you left and why. Your final report: what you ported (Python module -> Java class), commands added,
   deviations, tests added (count), final full-build result line, hooks for the orchestrator, anything
   left. Be honest: a fake-backed pass is evidence about this repo only.

## Ownership (slice -> owns)
- launchcore: launch/RunLauncher, PlanCases, LaunchCommand, LastConfig, LaunchConfig (fields beyond those
  already added on main), RunContext, RunEvents, TestsRunStep, GenerateReportStep, CleanupStep, SystemChecksStep,
  SystemCheckStages, XraySidecarStart, PrinterHold, BrowserConnector, RegistryFactory, StandardRegistry,
  launch/CaseSource/CaseEntry; plans/ (PartialRun etc.); pats/ (incl. feature/Gherkin); report/ EXCEPT
  report/RunFacts; xray/ EXCEPT any attach code; posshim/; pos/ (not Locators.java).
- sims: sims/** (incl. sims/base), launch/StartSimulatorsStep, SimulatorBaseChecks, SimulatorStaging,
  PinpadLink, PetroPorts, PetroSession; devices/fep/; fep/.
- tunnel: cloudtunnel/**, provision/transport/** (Transport, JschTransport, InMemoryTransport, ...).
- step4: launch/CommanderConfigurationStep, CommanderStages, DayClose, StageUnavailable; commander/** ;
  devices/commander, devices/csite, devices/eps; closeday/; recovery/; provision/ EXCEPT provision/transport;
  new launch/InstallResume; new launch/TunnelStart (the port of cat/launch/tunnel.py).
- upgrade: upgrade/**, build/**, launch/InstallCommanderBuild, launch/UpgradeMode.
- coverage: coverage/**, new launch/CoverageLaunch, new xray attach classes (xray/Attach*.java, and
  XrayClient.attachFile ONLY as an added method).
- webrun: runservice/**, results/**, report/RunFacts, web/Run*.java, web/Results*.java, new
  launch/LaunchDefaults (the run-start defaults extracted for the web; do not edit LaunchCommand).
- webshell: web/Layout, WebApp, Html, Section, Placeholder, JobLog, Settings*, Setup*, Simulators*, Upgrade*,
  Downloads*, Tools*, PosPage, web static resources; setup/**; runconfig/**; prefs/; new gitauth package;
  rig/ (ppk only); connections/.
- vats: new devices/vats/** (and devices/btt or similar), new launch/Vats* helper classes, the pinpad-clear
  command, resources it ships.
- authoring: authoring/**, new recorder/**, web/Authoring*.java, new publish package (petroapps publish),
  collect/ (only if publish needs it).
- Unowned shared files (Main.java table lines only; pom.xml: nobody): the orchestrator merges.

## Already done on main (63437bc)
`LaunchConfig` gained `ifsfMode, localBuild, foundationBranch, foundationBuild, provisionRegister,
keepSimulators` (canonical ctor + Builder setters). Use them; launchcore wires their CLI flags/last-config.
