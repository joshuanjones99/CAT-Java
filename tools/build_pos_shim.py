#!/usr/bin/env python
"""Build the POS shim and the retained RMI agent into `payload/pos/`.

DESIGN.md §6.1's table names three files this produces:

    pos/automation-target-pos-topaz.jar   the retained agent (§5.2), from petroapps
    pos/petrotest-pos-shim.jar            the HTTP shim, from bridge/pos-shim/
    pos/petrotest-pos-shim.sh             its launcher

`provision` pushes whatever is in `payload/`; producing it is this script's job.

It also writes `pos/petrotest-pos-shim.build`, the build stamp: one hash of every
source the build compiles and ships (`petrotest.provision.shim_stamp`), written last,
and compiled into the shim jar so the running shim reports it as `GET /health`'s
`build`. Step 4 rebuilds when the stamp is not the sources' (`_build_pos_shim`), and
`provision` replaces a running shim whose `build` is not the payload's
(`EnsurePosShimCurrent`).

WHY THIS NEEDS A JAR OFF A RIG
------------------------------
Both jars compile against `isdApps.topaz.pos.app-V1.jar`, which carries the Topaz
RMI infrastructure (`RMIServer`, `TLSRMISocketFactory`, `IPOSStatusMonitor`, the
panel value objects, `LexToken`). That jar is a build output of Verifone's
component build system and is not committed here — so it is fetched from a live
register into the deps cache with `--pull-deps-from`, or supplied by hand with
`--deps-dir`. That cache is `C:/CICD/Tools/pos-shim-deps` — outside the
repository, beside the simulators `cat.sims.config.tools_root` stages, because
a 16MB vendor jar fetched off a register is a tool this controller holds, not
repository content. It was `<repo>/.deps` until 2026-09-22.

That is a real constraint and worth naming rather than hiding: this script cannot
build on a machine that has never seen a rig or a Topaz build drop. The
alternative was checking a 16MB proprietary jar into git, or hand-writing stub
interfaces to compile against — the latter compiles and then fails at runtime the
first time a signature differs, which is the worse trade.

Usage:
    python tools/build_pos_shim.py --pull-deps-from <register-host>
    python tools/build_pos_shim.py                     # reuse the deps cache
    python tools/build_pos_shim.py --check             # compile only, no jars
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath

from cat.sims.config import tools_root as _tools_root
from petrotest.collect import default_petroapps_root
from petrotest.provision.shim_stamp import (
    AGENT_SUBPATH,
    DEP_JARS,
    STAMP_NAME,
    StampInputMissing,
    agent_java_sources,
    shim_java_sources,
    source_stamp,
    topazerp_java_sources,
)

#: The register's JRE is Corretto 17.0.5 (verified on the lab rig, 2026-08-03).
#: Compiling with a newer local JDK and no `--release` produces class files the
#: register's JVM refuses with UnsupportedClassVersionError — a failure that
#: appears at shim start, on the rig, in a log nobody is watching yet.
TARGET_RELEASE = "17"

#: The one jar both compilations need. Everything else on the register's
#: classpath is only needed at runtime (see petrotest-pos-shim.sh). `DEP_JARS`
#: (`petrotest.provision.shim_stamp`) is it plus the logging jars `javac` needs to
#: resolve the agent's imports.
TOPAZ_JAR = DEP_JARS[0]

#: Where those live on a register.
REMOTE_LIB = PurePosixPath("/home/topaz/lib")
#: Seconds for each SSH step of the dependency fetch (connect, `setenforce`, one jar).
SSH_TIMEOUT = 60.0

REPO_ROOT = Path(__file__).resolve().parents[1]
SHIM_SRC = REPO_ROOT / "bridge" / "pos-shim" / "src" / "main" / "java"
SHIM_LAUNCHER = REPO_ROOT / "bridge" / "pos-shim" / "petrotest-pos-shim.sh"
#: Where the vendor compile jars are cached, under the same tools root the
#: simulators use (`cat.sims.config.DEFAULT_TOOLS_ROOT`) and overridable the
#: same way, so one `CAT_TOOLS_ROOT` moves everything this controller stages.
DEFAULT_DEPS = _tools_root() / "pos-shim-deps"
DEFAULT_PAYLOAD = REPO_ROOT / "payload" / "pos"

# `AGENT_SUBPATH` (`petrotest.provision.shim_stamp`): the retained agent's source, in
# the petroapps checkout. §5.2 keeps this component as-is; we compile it, we do not
# fork it. The stamp module holds it, and the two below, because step 4 hashes the
# same sources this script compiles, and it cannot import this script.
#
# `TOPAZERP_SUBPATH`/`TOPAZERP_CLASSES` (same module): one class out of petroapps' TopazERP
# component, shipped as its own jar.
#
# WHY: `LexStrategy` — the POS's own keyboard/MSR input layer — opens its
# connection to the token RMI service only if this class is loadable:
#
#     implstub = Class.forName("com.verifone.isd.pcapps.topazERP.VsmsCommonImpl");
#     ...
#     if ((implstub == null) && (!junitEnableValue.equals("true"))) return;
#
# It is used as a presence gate and nothing more — "is the automation package
# installed on this register?". Without it on the POS classpath the observer
# still connects and pushes screen state, but no keyboard listener is ever
# registered, so the shim can read the POS and cannot press a key on it. That
# was observed on the lab rig: `/health` reported the client under
# `read_only_clients` with `drivable_clients` empty.
#
# Shipped as a separate jar rather than folded into the agent jar because it
# comes from a different petroapps component, and a jar named for one component
# that quietly contains another is how a provenance question becomes
# unanswerable later. Only this one class is compiled: the rest of TopazERP is
# PATS's Swing operator UI (TokenTableDialog, TokenERPServicePanel) which the POS
# has no use for.

#: The petroapps checkout, resolved as collection resolves it (`$PETROAPPS_ROOT`).
DEFAULT_PETROAPPS = Path(default_petroapps_root())

MAIN_CLASS = "com.verifone.petrotest.posshim.PosShim"


class BuildError(RuntimeError):
    """Something the operator has to fix — reported without a traceback."""


def run(command: list[str], *, what: str) -> str:
    """Run a build command, surfacing its own diagnostics on failure.

    javac's error output is the useful part of a failed build, so it is passed
    through rather than replaced with a return code.
    """
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise BuildError(
            f"{what} failed (exit {result.returncode}):\n{result.stdout}{result.stderr}".rstrip()
        )
    return result.stdout + result.stderr


def tool(name: str) -> str:
    """Locate a JDK tool, preferring JAVA_HOME over whatever is on PATH.

    A machine with a JRE on PATH and a JDK in JAVA_HOME is the common shape, and
    "javac is not recognised" is a less useful message than naming both places
    that were tried.
    """
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        # `JAVA_HOME` should be the JDK root, but this controller's points at its
        # `bin` (`C:\CICD\Tools\Java17\jdk-17.0.5+8\bin`); accept either.
        exe = name + (".exe" if os.name == "nt" else "")
        for candidate in (Path(java_home) / "bin" / exe, Path(java_home) / exe):
            if candidate.exists():
                return str(candidate)
    found = shutil.which(name)
    if not found:
        # The JDK PATS stages, which CAT's simulators already run on: a fresh
        # controller (2026-10-08) had it but neither JAVA_HOME nor PATH named it.
        from cat.sims.config import javaw

        staged = javaw(_tools_root())
        if staged is not None:
            candidate = staged.parent / (name + (".exe" if os.name == "nt" else ""))
            if candidate.exists():
                return str(candidate)
        raise BuildError(
            f"{name} not found on PATH"
            + (f" nor in JAVA_HOME ({java_home})" if java_home else "")
            + f" nor under {_tools_root() / 'Java17'}"
            + f". A JDK {TARGET_RELEASE} or newer is required to build the shim."
        )
    return found


def pull_deps(
    host: str,
    deps_dir: Path,
    ssh_key: str | None,
    *,
    user: str = "root",
    only_missing: bool = False,
) -> None:
    """Copy the compile dependencies off a register into `deps_dir`, over SFTP.

    Through `ParamikoTransport`, the connection `provision` makes to the same box,
    not `scp.exe`: on a second controller (2026-10-08) Windows OpenSSH's scp
    failed with only "Connection closed" while paramiko, with the same key, went
    straight in a second later. OpenSSH refuses a key file that other users can
    read (the setup-converted `.pem` inherits `C:\\CICD\\Tools`' ACL), and it
    needs SELinux permissive for SFTP; paramiko ignores the first, and the
    transport runs `setenforce 0` before its first read.
    """
    from petrotest.provision.transport import ParamikoTransport

    deps_dir.mkdir(parents=True, exist_ok=True)
    wanted = [jar for jar in DEP_JARS if not (only_missing and (deps_dir / jar).is_file())]
    if not wanted:
        return
    transport = ParamikoTransport(host, username=user, key_filename=ssh_key)
    try:
        for jar in wanted:
            print(f"  fetching {jar} from {host}")
            try:
                content = transport.get(REMOTE_LIB / jar, timeout=SSH_TIMEOUT)
            except Exception as exc:
                raise BuildError(f"fetching {jar} from {host} failed: {exc}") from exc
            if content is None:
                raise BuildError(f"{host} has no {REMOTE_LIB / jar}")
            (deps_dir / jar).write_bytes(content)
    finally:
        transport.close()


def pull_deps_from_rig(rig_name: str, deps_dir: Path) -> None:
    """Fetch whichever compile jars are missing from the first register of `rig_name`.

    The vendor jars exist only on a Topaz build, so a controller that has never
    built the shim has no other source. Host, user and key come from the rig file
    (the same `targets_from_rig` that `provision` uses), never from a literal here.
    """
    from petrotest.provision.artifacts import TargetRole
    from petrotest.provision.targets import targets_from_rig
    from petrotest.rig import load_rig

    registers = [t for t in targets_from_rig(load_rig(rig_name)) if t.role is TargetRole.REGISTER]
    if not registers:
        raise BuildError(f"rig {rig_name!r} declares no register to fetch the Topaz jars from")
    register = registers[0]
    print(f"Fetching missing build dependencies from {register.name} ({register.host})")
    pull_deps(
        register.host,
        deps_dir,
        register.ssh_key_filename,
        user=register.ssh_user,
        only_missing=True,
    )


def check_deps(deps_dir: Path) -> list[Path]:
    """Confirm the dependency jars are present, and say how to get them if not."""
    missing = [jar for jar in DEP_JARS if not (deps_dir / jar).is_file()]
    if missing:
        raise BuildError(
            "missing build dependencies in "
            + str(deps_dir)
            + ":\n  "
            + "\n  ".join(missing)
            + "\n\nFetch them from a register with:\n"
            + "  python tools/build_pos_shim.py --pull-deps-from <register-host> "
            + "--ssh-key <key>\n"
            + f"or copy them by hand out of {REMOTE_LIB} on any Topaz build."
        )
    return [deps_dir / jar for jar in DEP_JARS]


def agent_sources(petroapps: Path) -> list[Path]:
    """The retained agent's .java files."""
    src = petroapps / AGENT_SUBPATH
    if not src.is_dir():
        raise BuildError(
            f"retained agent source not found at {src}.\n"
            "Point --petroapps at a petroapps checkout (or set PETROAPPS_ROOT)."
        )
    sources = agent_java_sources(petroapps)
    if not sources:
        raise BuildError(f"no .java files under {src}")
    return sources


def topazerp_sources(petroapps: Path) -> list[Path]:
    """The single TopazERP class the POS's LexStrategy gates on."""
    sources = topazerp_java_sources(petroapps)
    missing = [source for source in sources if not source.is_file()]
    if missing:
        raise BuildError(
            "TopazERP source not found:\n  "
            + "\n  ".join(str(path) for path in missing)
            + "\n\nWithout it the POS registers no keyboard listener and the shim can "
            + "read the POS but not drive it. Point --petroapps at a checkout that has "
            + "isdApps/pcapps/utility/TopazERP."
        )
    return sources


def shim_sources() -> list[Path]:
    sources = shim_java_sources(REPO_ROOT)
    if not sources:
        raise BuildError(f"no shim sources under {SHIM_SRC}")
    return sources


def build_stamp(petroapps: Path) -> str:
    """The build stamp of the sources this run compiles (`shim_stamp.source_stamp`)."""
    try:
        return source_stamp(REPO_ROOT, petroapps)
    except StampInputMissing as exc:
        raise BuildError(f"cannot stamp the build: {exc}") from exc


def stamp_classes(classes: Path, stamp: str) -> None:
    """Put the stamp at the root of the shim's classes, so the jar carries it.

    `Build.java` reads it back as `/petrotest-pos-shim.build` and `GET /health`
    reports it as `build`: how `provision` tells a running shim from the payload's.
    """
    (classes / STAMP_NAME).write_bytes(f"{stamp}\n".encode("ascii"))


def write_payload_stamp(dest_dir: Path, stamp: str) -> None:
    """`payload/pos/petrotest-pos-shim.build`, written after every other output.

    Last, and removed before the jars are written (`main`), so a build that dies
    halfway leaves no stamp: step 4 then rebuilds rather than taking a half-written
    payload for the sources' build.
    """
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / STAMP_NAME
    dest.write_bytes(f"{stamp}\n".encode("ascii"))
    print(f"  wrote {_shown(dest)} (build {stamp[:12]})")


def classpath(entries: list[Path]) -> str:
    """Join classpath entries with the platform separator."""
    return os.pathsep.join(str(entry) for entry in entries)


def compile_tree(sources: list[Path], *, into: Path, cp: list[Path], what: str, lint: bool) -> None:
    into.mkdir(parents=True, exist_ok=True)
    command = [tool("javac"), "--release", TARGET_RELEASE]
    # The retained agent is compiled with warnings off: it is petroapps' code
    # under §5.2's "retained, not rewritten", it has a deprecated `new Short(...)`
    # in StatusUtil, and warnings we are not going to act on are noise that hides
    # the ones we would. The shim's own sources get -Xlint:all.
    command += ["-Xlint:all"] if lint else ["-nowarn"]
    command += ["-cp", classpath(cp), "-d", str(into)]
    command += [str(source) for source in sources]
    output = run(command, what=what).strip()
    if output:
        print(output)


def _shown(path: Path) -> str:
    """`path` relative to the repo when it is inside it; a `--payload` elsewhere as is."""
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def make_jar(classes: Path, jar_path: Path, *, main_class: str | None) -> None:
    jar_path.parent.mkdir(parents=True, exist_ok=True)
    command = [tool("jar"), "--create", "--file", str(jar_path)]
    if main_class:
        command += ["--main-class", main_class]
    command += ["-C", str(classes), "."]
    run(command, what=f"jar {jar_path.name}")
    print(f"  wrote {_shown(jar_path)} ({jar_path.stat().st_size:,} bytes)")


#: What the register's agent reads to find the shim.
#:
#: `token.host` is 127.0.0.1 because the shim runs on the register: the agent
#: dials it over loopback and no RMI traffic crosses the bench network. PATS
#: shipped this file with one lab PC's own address as `token.host`, baked into a
#: config pushed to every register — which is exactly the coupling §4.2
#: rule 5 removes. Fixed content, so it is emitted here rather than rendered per
#: rig; the ports match `Config.java`'s defaults, which in turn match the retained
#: agent's own DEFAULT_RMI_PORT / DEFAULT_CLIENT_PORT / DEFAULT_APP_NAME.
TOKEN_RMI_PROP = """\
# Written by tools/build_pos_shim.py — see DESIGN.md §5.2.
#
# Points the POS's retained automation agent (automation.observer.class in
# private_system.prop, and the keyboard/MSR LexStrategy) at the petrotest POS
# shim. The shim runs on this same register, so this is loopback.
token.host=127.0.0.1
token.port=1094
token.client=5003
token.rmi=tokenRMI.app
"""


def write_token_rmi_prop(dest_dir: Path) -> None:
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / "tokenRMI.prop"
    # LF endings: read by a JVM on Linux, and a stray CR would end up inside the
    # property *value* — `token.host=127.0.0.1\r` resolves to no host at all.
    dest.write_bytes(TOKEN_RMI_PROP.encode("utf-8"))
    print(f"  wrote {_shown(dest)} ({dest.stat().st_size:,} bytes)")


def copy_launcher(dest_dir: Path) -> None:
    """Copy the launcher with LF endings.

    Written on Windows, run by /bin/sh on the register: a CR that survives the
    trip makes the shebang line fail with a "no such file or directory" that names
    the interpreter and not the reason.
    """
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / SHIM_LAUNCHER.name
    text = SHIM_LAUNCHER.read_text(encoding="utf-8").replace("\r\n", "\n")
    dest.write_bytes(text.encode("utf-8"))
    print(f"  wrote {_shown(dest)} ({dest.stat().st_size:,} bytes)")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Build the POS HTTP shim and retained RMI agent into payload/pos/",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--deps-dir",
        type=Path,
        default=DEFAULT_DEPS,
        help=f"where the Topaz compile jars live (default {DEFAULT_DEPS})",
    )
    parser.add_argument(
        "--pull-deps-from",
        metavar="HOST",
        help="SCP the compile jars off this register before building",
    )
    parser.add_argument(
        "--rig",
        help="when the deps cache is missing jars, fetch them from this rig's first register",
    )
    parser.add_argument(
        "--ssh-key",
        default=os.environ.get("PETROTEST_COMMANDER_SSH_KEY"),
        help="SSH key for --pull-deps-from (default $PETROTEST_COMMANDER_SSH_KEY)",
    )
    parser.add_argument(
        "--petroapps",
        type=Path,
        default=DEFAULT_PETROAPPS,
        help=f"petroapps checkout holding the retained agent (default {DEFAULT_PETROAPPS})",
    )
    parser.add_argument(
        "--payload",
        type=Path,
        default=DEFAULT_PAYLOAD,
        help=f"output directory (default {DEFAULT_PAYLOAD.relative_to(REPO_ROOT)})",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="compile both trees and stop; writes no jars",
    )
    args = parser.parse_args(argv)

    try:
        if args.pull_deps_from:
            print(f"Fetching build dependencies from {args.pull_deps_from}")
            pull_deps(args.pull_deps_from, args.deps_dir, args.ssh_key)
        elif args.rig and any(not (args.deps_dir / jar).is_file() for jar in DEP_JARS):
            pull_deps_from_rig(args.rig, args.deps_dir)
        deps = check_deps(args.deps_dir)
        agent = agent_sources(args.petroapps)
        topazerp = topazerp_sources(args.petroapps)
        shim = shim_sources()
        # Of these sources, the launcher and this script, before anything is compiled:
        # the stamp names what was built, not what the tree held by the end of it.
        stamp = build_stamp(args.petroapps)
        print(f"Build stamp {stamp[:12]} (bridge/pos-shim, the retained agent, TopazERP, recipe)")

        with tempfile.TemporaryDirectory(prefix="pos-shim-build-") as tmp:
            agent_classes = Path(tmp) / "agent"
            erp_classes = Path(tmp) / "topazerp"
            shim_classes = Path(tmp) / "shim"

            print("Compiling retained RMI agent (petroapps §5.2)")
            compile_tree(
                agent,
                into=agent_classes,
                cp=deps,
                what="javac (agent)",
                lint=False,
            )

            print("Compiling TopazERP connection enabler (LexStrategy's gate class)")
            compile_tree(
                topazerp,
                into=erp_classes,
                cp=deps,
                what="javac (topazERP)",
                lint=False,
            )

            print("Compiling POS HTTP shim")
            compile_tree(
                shim,
                into=shim_classes,
                cp=[*deps, agent_classes],
                what="javac (shim)",
                lint=True,
            )

            if args.check:
                print("\nOK — both trees compile. No jars written (--check).")
                return 0

            print("Packaging")
            # The old stamp goes first and the new one is written last: a payload
            # that has a stamp is one whose every file came from that build.
            (args.payload / STAMP_NAME).unlink(missing_ok=True)
            stamp_classes(shim_classes, stamp)
            make_jar(
                agent_classes,
                args.payload / "automation-target-pos-topaz.jar",
                main_class=None,
            )
            make_jar(
                erp_classes,
                args.payload / "topazerp-connection-enabler.jar",
                main_class=None,
            )
            make_jar(
                shim_classes,
                args.payload / "petrotest-pos-shim.jar",
                main_class=MAIN_CLASS,
            )
            copy_launcher(args.payload)
            write_token_rmi_prop(args.payload)
            write_payload_stamp(args.payload, stamp)

        print("\nOK — payload/pos is ready for `petrotest provision`.")
        return 0

    except BuildError as exc:
        print(f"\nERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
