# petrotest POS HTTP shim

The RMI bridge of DESIGN.md §5.2: a small Java service that runs on the register
beside the Topaz POS, hosts the RMI service the retained automation agent connects
into, and serves the state that agent pushes as JSON over HTTP on port 8801.

Verified end-to-end against the lab register (192.168.31.101, Corretto 17.0.5)
on 2026-08-03.

```
  register 192.168.31.101 (C18)
  ┌──────────────────────────────────────────────┐
  │ Topaz POS (Tomcat JVM)                       │
  │   private_system.prop:                       │
  │     automation.observer.class=…ObserverImpl  │
  │   ObserverImpl ── pushes panel state ──┐     │
  │   LexStrategy  ◄── injects keys ─────┐ │     │      controller
  ├──────────────────────────────────────┼─┼─────┤   ┌──────────────┐
  │ petrotest-pos-shim  (our JVM)        │ │     │   │  petrotest   │
  │   RMI registry :1094  tokenRMI.app ◄─┘◄┘     │   │ PosShimClient│
  │   HTTP :8801  ◄───────────────────────────────────┤              │
  └──────────────────────────────────────────────┘   └──────────────┘
```

## The thing to understand first

**The agent is a client, not a server.** It does not expose
`isPrinterConnected()` for anything to call. `POSStatusRMIService` inside the POS
JVM reads `tokenRMI.prop`, dials out to `token.host:token.port`, asks for a remote
`IPOSStatusMonitor`, and **pushes GUI panel state into it**. The registry and the
bound `tokenRMI.app` object live on the other end — in PATS, inside the PATS
application on the controller PC.

So a bridge cannot be something that dials into the POS to ask questions. It has to
be the thing the POS dials. That is what this shim is, and it is why three earlier
attempts at "the RMI HTTP bridge" — all of them looking for a request/response
service on `commander:8080` — found nothing but 404s. There was never a method on
the other end to call.

## What it can and cannot tell you

Because the agent pushes *screen* state, the surface is screen-shaped.

**Available** (`GET /pos/screen` for all of it at once):

| Route | What it reports |
|---|---|
| `GET /pos/idle` | §6.3's testability predicate, plus the `basis` that decided it |
| `GET /pos/transaction` | open ticket, from the ODSP totals panel |
| `GET /pos/overlay` | current dialog: title, three lines, which buttons it offers |
| `GET /pos/express-panel` | the soft-key buttons on screen, with labels |
| `GET /pos/totals` | subtotal, tax, balance due, paid, change… |
| `GET /pos/fuel` | fuel position, pump state, help requested, paper low |
| `GET /pos/keys` | which keys can be pressed, verified against the token maker |
| `POST /pos/keys` | press keys: `{"keys": ["VOID_TICKET", "YES"]}` |
| `POST /pos/control/cancel` | §6.3 rung 1 SOFT — dismiss overlays, verified |
| `POST /pos/control/ticket/void` | §6.3 rung 2 VOID — void open ticket, verified |
| `POST /pos/scan` | present a barcode scan (`SCAN_ITEM`, HANDOFF.md §3.2) — **UNVERIFIED AGAINST HARDWARE**; compiles against the retained `ScannerLexDataEvent`/`createScannerToken` path but has not been driven against a POS |
| `POST /pos/keys/enter` | press ENTER carrying a data payload (`PRESS_ENTER`, HANDOFF.md §4.10) — **UNVERIFIED AGAINST HARDWARE**; built from the retained `LexTokenMaker.createPendingTokens("ENTER", data)`, the same path PATS' `pendingToken` uses, but has not been driven against a POS |
| `GET /health`, `GET /status` | liveness, and §6.1's post-provision agent probe; `/health` (and `GET /`) also report `build`, the stamp compiled into this jar (`Build.java`), or `null` for a shim built before 2026-10-16 |

**Not available, and answering 501 rather than guessing** — printer port state,
cash drawer position, the suspended-ticket list, the signed-on cashier id, the
build version, and cashier sign-on. None of them appear on any panel object the
observer sends. `GET /` lists each with the reason and what a real source would be.

This matters more than it looks. The client this replaces read every reply as
`data.get("connected", False)`, so a 404 became "the printer is disconnected" —
turning an unrunnable probe into a hardware FAIL. §6.2 requires the opposite: an
ERROR is the probe, a FAIL is the rig. `PosShimClient` maps 501 to
`DeviceUnavailable`, which the preflight runner records as ERROR with the shim's
own explanation attached.

## Three properties of a push-only surface

These were all found on the rig, and none of them are fixable from this side. They
are the operational cost of §5.2's "retained, not rewritten".

**1. Absent state is indistinguishable from an idle screen.** Every field starts
null, so a POS that has never pushed looks exactly like a POS on a clean idle
screen: no overlay, no totals, `idle=true`. So the predicate routes answer **503
`no-state`** until something has been pushed, and only `GET /pos/screen` answers
regardless (reporting `state_present: false`). Without this, a register whose
observer never loaded would report itself ready to test.

**2. State can go stale.** Injecting `EXIT` on an idle POS raises the transient
`PROCESSING / PLEASE WAIT` overlay with `auto_hide=true` — and on the rig that
overlay was still the shim's latest known state 48 seconds later. The POS
auto-hid it on screen without pushing a dismissal. There is no poll to fall back
on, so every snapshot carries `last_change_age_s`, and a caller applies §6.3's
bounded-wait discipline rather than trusting a boolean that may be minutes old.

**3. Restarting the shim breaks the read surface until the POS restarts.** This
one is worth reading twice before deploying an update.
`POSStatusRMIService.remoteToken` is a **static** field holding a stub to the
`POSStatusMonitorImpl` that the shim's *previous* JVM exported, and
`ObserverImpl.update` re-initialises only when it is null:

```java
if (remoteToken == null) { initRMIService(); }
if (remoteToken != null) { remoteToken.updateOverlayMsgEvent(...); }
```

A stub to a dead JVM is not null, so every push throws `RemoteException` into the
observer's own `catch`, which logs and returns — permanently. The **drive** path
recovers on its own (it goes through `RMIConnector`'s reset listener); the read
path has no such listener. The signature is a POS that accepts keys and pushes
nothing, which `GET /health` reports as `drivable_but_silent` with a warning.

**So: after replacing the shim jar, restart the POS.** Restarting the shim again
will not help.

`provision` does this itself since 2026-10-16 (`EnsurePosShimCurrent`,
`src/petrotest/provision/register_bringup.py`), after its push and before `start`:
when the running shim's `GET /health` `build` is not the payload's stamp (no `build`
at all counts as different), it runs `petrotest-pos-shim.sh restart` as `topaz`, waits
up to 60 s for the new shim to answer on the payload's build, restarts the POS
(`/etc/init.d/apps stop`, stale serial locks dropped, `start`), and waits up to 5
minutes for `/health` to show the POS under `drivable_clients` with `state_present:
true` and nothing `drivable_but_silent`. Equal builds: nothing is run. UNVERIFIED
AGAINST HARDWARE. Before it, `start` kept any shim that was already running, so a
pushed jar never ran: on 2026-10-16 the lab register's shim, 50 hours old, served
neither `POST /pos/msr` nor `GET /pos/scanner/last`.

## Building

```bash
# first time, or when the Topaz build changes
python tools/build_pos_shim.py --pull-deps-from 192.168.31.101 --ssh-key <key>

# thereafter
python tools/build_pos_shim.py
python tools/build_pos_shim.py --check      # compile only, no jars
```

Output lands in `payload/pos/`, which is what `provision` pushes:

| File | Goes to | Why there |
|---|---|---|
| `automation-target-pos-topaz.jar` | `/home/topaz/lib/` | **must** be here — `register.sh` globs `$APP_HOME/lib/*.jar` for the POS classpath |
| `topazerp-connection-enabler.jar` | `/home/topaz/lib/` | `LexStrategy` gates the keyboard connection on `Class.forName` of `topazERP.VsmsCommonImpl` |
| `tokenRMI.prop` | `/home/topaz/config/` | points the agent at `127.0.0.1:1094` |
| `petrotest-pos-shim.jar` | `/home/topaz/automation/` | separate JVM, own classpath |
| `petrotest-pos-shim.sh` | `/home/topaz/automation/` | launcher |
| `petrotest-pos-shim.build` | nowhere: it stays on the controller | the build stamp, written last; also inside `petrotest-pos-shim.jar` |

The build needs `isdApps.topaz.pos.app-V1.jar` from a register (or a Topaz build
drop) in `C:/CICD/Tools/pos-shim-deps` (`tools/build_pos_shim.py`'s
`DEFAULT_DEPS`; it was `<repo>/.deps` until 2026-09-22, and moved out beside the
staged simulators because a 16MB vendor jar is a tool this controller holds, not
repository content). It is a Verifone component-build output, so it is not
committed; `--pull-deps-from` refetches it in seconds. This does mean the shim
cannot be built on a machine that has never seen a rig — a real constraint, and
preferable to compiling against hand-written stub interfaces that would pass
`javac` and fail at runtime on the first signature that differed.

CAT builds the payload itself when a run needs it: step 4 compiles
`bridge/pos-shim/` before `petrotest provision` (`cat.launch._build_pos_shim`) when a
file of `payload/pos/` is absent, or when `petrotest-pos-shim.build` is absent or is
not the stamp of the sources as they are now, rather than failing provision on a file
it could have made or pushing a jar from older sources. It still needs a JDK and the
vendor jars above.

**The build stamp** (`src/petrotest/provision/shim_stamp.py`) is one sha256 over the
shim's `.java` sources, its launcher, the retained agent's and the TopazERP class's
sources in petroapps, and `tools/build_pos_shim.py` itself (the javac flags, the target
release, `tokenRMI.prop`'s content), each under a name independent of where it is
checked out, with CRLF read as LF. Not the Topaz vendor jars: they are compiled
against, not compiled in. The build writes it into the shim jar's root and, after
every other output, as `payload/pos/petrotest-pos-shim.build`; a build that dies
halfway leaves none.

## Running it

```bash
/home/topaz/automation/petrotest-pos-shim.sh start|stop|restart|status
/home/topaz/automation/petrotest-pos-shim.sh foreground   # debugging; logs to terminal
```

Logs to `/home/topaz/automation/petrotest-pos-shim.log`. Run as `topaz`, the user
the POS runs as.

Two JVM flags in the launcher are load-bearing, both mirroring the POS's own
command line: `--add-opens java.rmi/...` because the Topaz RMI stack reflects into
`java.rmi` internals, and
`-Dsun.rmi.registry.registryFilter=java.**;com.verifone.**;javax.**;org.**`
because Java 17 otherwise refuses to deserialise the `com.verifone.*` panel
objects the POS pushes through the registry.

## First-time setup on a register

`provision` does all of this; it is written out because the failure modes are
quiet and each step has one.

0. **`setenforce 0` on the register (and on the Commander, if the same session
   pushes there too).** SELinux `Enforcing` denies SFTP `put`/`get` with
   `Permission denied` even as root and even as the target file's own owning
   uid — `ls`/read are unaffected, so this looks exactly like an ownership
   problem and is not one. `ParamikoTransport._ensure_selinux_permissive`
   (`src/petrotest/provision/transport.py`) now runs this automatically before
   the first SFTP operation on a connection, so a manual `provision` run no
   longer needs this step — but anyone pushing files by hand (WinSCP, scp,
   sftp CLI) still does.
1. `automation.observer.class` in `/home/topaz/config/private_system.prop` — the
   lab register **already had this**, and `automation.statusmonitor.class` too. If
   it is missing, the observer is never instantiated.
2. Both jars in `/home/topaz/lib/` (not `automation/` — see the table above).
3. `tokenRMI.prop` in `/home/topaz/config/`.
4. Start the shim.
5. **Restart the POS** (`/etc/init.d/apps stop && /etc/init.d/apps start`). The
   observer class is loaded at POS start; nothing happens until this.
6. Confirm: `GET /health` shows the client under `drivable_clients` with
   `state_present: true`. `GET /status` returns 200.

Expect step 6 to take a couple of minutes — the POS boots through its own
`PROCESSING / PLEASE WAIT` before it settles.

## Reading the code

| File | Responsibility |
|---|---|
| `PosShim.java` | entry point; RMI up before HTTP, deliberately |
| `Build.java` | the build stamp compiled into the jar, as `/health` reports it |
| `ShimRmiHost.java` | starts the retained `TopazRMIService`; client resolution; the state-holder seeding trap |
| `Snapshot.java` | one `TokenInfoHolder` → JSON; what the surface can and cannot say |
| `Keys.java` | key injection and the cancel ladder; which key names actually build |
| `Routes.java` | HTTP dispatch; the 501 table with reasons |
| `StateWatcher.java` | staleness tracking, and why it is not an expiry |
| `Json.java` | JDK-only JSON, because the register has no dependency tree |

Two upstream bugs in petroapps are worked around rather than relied upon, both
documented at their use site: `ReceiptPanelInfo`'s constructor assigns
`setItemLine(itemLine)` — its own null field — instead of its `item` parameter, so
receipt item lines never arrive (`Snapshot.transactionActive` reads the totals
panel instead); and `LexTokenMaker.createKBDToken` has no `case` for most of its
own `KbdTokens` enum, including `CLEAR` and every digit, returning null instead
(`Keys` validates against the maker at runtime rather than trusting a static list).
