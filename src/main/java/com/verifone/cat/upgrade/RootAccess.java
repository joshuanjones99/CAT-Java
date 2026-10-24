package com.verifone.cat.upgrade;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * The pushes that give a freshly installed device its root access, and the Commander's bypass switch.
 *
 * <p>A port of the parts of {@code petrotest.upgrade.classic} the PSI path calls:
 * {@code push_commander_root_access}, {@code push_register_root_access}, {@code set_bypass_mem_size_check}
 * and {@code is_hard_fault}. Argv is the one {@code CICDScript.bat} gives; the {@code 443} and the trailing
 * {@code 0} are copied as-is. Success is judged as the Python judges it.
 *
 * <p>Not ported here: the AU path's overlay push and its CGI upgrade dance (the Python {@code classic}
 * module's {@code upgrade_classic}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class RootAccess {

    static final String STEP_COMMANDER = "push_commander_root_access";
    static final String STEP_REGISTER = "push_register_root_access";

    /** What a 1052 Commander answers the jacoco package with when it is too large. */
    static final String COMMANDER_PACKAGE_TOO_LARGE = "Uploaded file exceeds size limit";
    static final String SYSTEMS_ROOT_ACCESS_PACKAGE = "update-all-systems-noPW.tgz";

    /** A fault the Commander returns on the request itself: it will answer the same way on every retry. */
    static final String HARD_FAULT = "CGIPortal.InternalError";

    /** What {@code NetLoaderClient.jar} prints after a transfer the register accepted. */
    static final String NETLOADER_SUCCESS = "File Download Completed successfully";

    private static final Pattern FAULT_MESSAGE = Pattern.compile("<e:message>(.*?)</e:message>", Pattern.DOTALL);
    private static final Pattern FAULT_CODE = Pattern.compile("<faultCode>(.*?)</faultCode>");
    private static final int STDERR_TAIL = 500;

    /**
     * {@code bypass.mem.size.check} in {@code private_system.prop}, as {@code CICDScript.bat} edits it: sed the
     * property if it is there, else append it. Idempotent either way. {@code %s} is {@code true} or {@code false}.
     */
    private static final String BYPASS_CMD =
            "grep -q 'bypass.mem.size.check=' /home/coresvcs/config/private_system.prop && "
                    + "sed -i 's/bypass.mem.size.check=.*/bypass.mem.size.check=%s/' "
                    + "/home/coresvcs/config/private_system.prop || "
                    + "printf '\\nbypass.mem.size.check=%s\\n' >> /home/coresvcs/config/private_system.prop";

    private RootAccess() {}

    /** A push failure that retrying cannot fix: the Commander answered, and refused. */
    static boolean isHardFault(String detail) {
        return detail != null && detail.contains(HARD_FAULT);
    }

    /** Sets {@code bypass.mem.size.check} to {@code true} on the Commander, before any upgrade branch. */
    static StepResult setBypassMemSizeCheck(Transport transport, Duration timeout) {
        String step = "bypass_mem_size_check";
        String command = BYPASS_CMD.replace("%s", "true");
        try {
            CommandResult result = transport.run(command, timeout);
            if (!result.ok()) {
                String stderr = result.stderr().strip();
                return StepResult.failed(step, "exit " + result.exitStatus() + ": "
                        + stderr.substring(0, Math.min(STDERR_TAIL, stderr.length())));
            }
            return StepResult.done(step);
        } catch (DeviceError e) {
            return StepResult.failed(step, e.getMessage());
        }
    }

    /**
     * Pushes both root-access packages to the Commander ({@code Auto_Upgrade.jar -q lic}, port 443). When the
     * Commander refuses the jacoco package by size, the sibling systems package is pushed instead.
     */
    static StepResult pushCommanderRootAccess(NewInstallTools tools, Jars jars, String host, Duration timeout) {
        for (String packagePath : List.of(tools.gemregLicensePath(), tools.rootAccessPath())) {
            StepResult failed = pushLic(tools, jars, host, packagePath, timeout);
            if (failed != null && failed.detail() != null && failed.detail().contains(COMMANDER_PACKAGE_TOO_LARGE)) {
                String fallback = Path.of(packagePath).resolveSibling(SYSTEMS_ROOT_ACCESS_PACKAGE).toString();
                if (!fallback.equals(packagePath) && Path.of(fallback).toFile().isFile()) {
                    failed = pushLic(tools, jars, host, fallback, timeout);
                    if (failed == null) {
                        return StepResult.done(STEP_COMMANDER, Path.of(packagePath).getFileName() + " refused ("
                                + COMMANDER_PACKAGE_TOO_LARGE + "); pushed " + SYSTEMS_ROOT_ACCESS_PACKAGE
                                + " instead");
                    }
                }
            }
            if (failed != null) {
                return failed;
            }
        }
        return StepResult.done(STEP_COMMANDER);
    }

    /** One {@code -q lic} push; {@code null} on success, else the failed result. */
    private static StepResult pushLic(NewInstallTools tools, Jars jars, String host, String packagePath,
            Duration timeout) {
        List<String> argv = jarArgv(tools.javaBin(), tools.autoUpgradeJar().toString(),
                "-q", "lic", packagePath, host, "443", "0");
        Jars.Result result;
        try {
            result = jars.run(argv, timeout);
        } catch (IOException e) {
            return StepResult.failed(STEP_COMMANDER, e.getMessage());
        }
        if (result.exitCode() == 0) {
            return null;
        }
        // The jar's fault text is what says why; it is never in the head of its stderr, so keep the tail.
        String stderr = result.stderr().strip();
        Matcher message = FAULT_MESSAGE.matcher(stderr);
        String why = message.find() ? message.group(1).strip()
                : stderr.substring(Math.max(0, stderr.length() - STDERR_TAIL));
        Matcher fault = FAULT_CODE.matcher(stderr);
        String code = fault.find() ? " " + fault.group(1) + ":" : "";
        return StepResult.failed(STEP_COMMANDER,
                "Auto_Upgrade.jar exited " + result.exitCode() + ":" + code + " " + why);
    }

    /**
     * Pushes root access to one register ({@code NetLoaderClient.jar <host> <package>}). The jar exits 0
     * whether or not the push worked, so success is its own {@link #NETLOADER_SUCCESS} line and nothing else.
     */
    static StepResult pushRegisterRootAccess(NewInstallTools tools, Jars jars, String host, Duration timeout) {
        List<String> argv = List.of(tools.javaBin(), "-jar", tools.netloaderJar(), host, tools.rootAccessPath());
        Jars.Result result;
        try {
            result = jars.run(argv, timeout);
        } catch (IOException e) {
            return StepResult.failed(STEP_REGISTER, e.getMessage());
        }
        if (!result.stdout().contains(NETLOADER_SUCCESS)) {
            String[] lines = (result.stdout() + result.stderr()).strip().split("\\R");
            String last = lines.length == 0 || lines[lines.length - 1].isBlank()
                    ? "no output" : lines[lines.length - 1];
            return StepResult.failed(STEP_REGISTER, "NetLoaderClient.jar: " + last);
        }
        return StepResult.done(STEP_REGISTER);
    }

    /**
     * Pushes {@code target}'s root access, retrying until the package is in.
     *
     * <p><b>Success is the package's effect, not the jar's reply.</b> A 1052 Commander installs the package and
     * reboots before {@code Auto_Upgrade.jar} gets its HTTP reply, so the jar times out on a push that worked.
     * So before every attempt {@code sshAnswers} asks whether root already takes the no-pw key, and stops there.
     *
     * <p>A hard fault ({@link #isHardFault}) ends the retry at once: the answer will not change.
     *
     * @param retryFor how long to keep retrying, counted from the first attempt
     * @param retryInterval the pause between attempts
     * @param log receives every attempt's outcome
     */
    static StepResult pushUntilConfirmed(NewInstallTools tools, Jars jars, Target target, Duration timeout,
            Duration retryFor, Duration retryInterval, java.util.function.Predicate<Target> sshAnswers,
            java.util.function.Consumer<String> log) {
        boolean commander = target.role() == Target.Role.COMMANDER;
        String step = commander ? STEP_COMMANDER : STEP_REGISTER;
        long deadline = System.nanoTime() + retryFor.toNanos();
        int attempts = 0;
        while (true) {
            if (sshAnswers.test(target)) {
                log.accept(step + ": root already answers with the no-pw key (attempt " + (attempts + 1)
                        + " not sent)");
                return StepResult.done(step, "root access confirmed over SSH");
            }
            attempts++;
            StepResult result = commander
                    ? pushCommanderRootAccess(tools, jars, target.host(), timeout)
                    : pushRegisterRootAccess(tools, jars, target.host(), timeout);
            log.accept(result.step() + " attempt " + attempts + ": " + result.status().wire()
                    + (result.detail().isEmpty() ? "" : " " + result.detail()));
            if (!result.failed() || isHardFault(result.detail())) {
                return result;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return StepResult.failed(step, "root access on " + target.name() + " not confirmed within "
                        + retryFor.toSeconds() + " s; last: " + result.detail());
            }
            Bounded.pause(Duration.ofNanos(Math.min(retryInterval.toNanos(), remaining)));
        }
    }

    private static List<String> jarArgv(String java, String jar, String... args) {
        List<String> argv = new ArrayList<>(List.of(java, "-jar", jar));
        argv.addAll(List.of(args));
        return argv;
    }
}
