package com.verifone.cat.web;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.shell.RigBusy;
import com.verifone.cat.tools.RigTools;

/**
 * The Tools page's state: which tool is running, each tool's last outcome, and one log. The tools themselves are
 * {@link RigTools}'; this only starts them, one at a time, off the request thread.
 *
 * <p>One action at a time across CAT: each tool takes the {@link RigBusy} flag the run, the install and the simulator
 * pages share, so a tool is refused while a run, a preflight, an install or a simulator action holds the rig, and they
 * are refused while it runs. There is no Stop: a reboot cannot be taken back, and pairing or onboarding stopped
 * half-way leaves the rig in a worse state than either end.
 *
 * <p>Onboarding uses the Config tab's Commander Connect values (the password, when the tab has none, from
 * {@code $COMMANDER_CONNECT_PASSWORD}), so a tool onboards exactly as a run would.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Tests pass a fake {@link RigTools}; nothing here reaches a rig.
 */
final class ToolsService {

    /** The tools, in the page's order: the address each is started at, its name, and the flag it holds. */
    enum Tool {
        PAIR("pair", "POS Pairing", RigBusy.PAIRING),
        ONBOARD("onboard", "Onboard to Commander Connect", RigBusy.ONBOARDING),
        REBOOT_C18("reboot-c18", "Reboot C18", RigBusy.REBOOT),
        REBOOT_COMMANDER("reboot-commander", "Reboot Commander", RigBusy.REBOOT),
        /** The POS page's button, not listed on the Tools page. */
        PUSH_SHIM("push-shim", "Push shim to register", RigBusy.SHIM_PUSH);

        final String slug;
        final String title;
        final String kind;

        Tool(String slug, String title, String kind) {
            this.slug = slug;
            this.title = title;
            this.kind = kind;
        }

        /** {@code /tools/<slug>}, which the tool's button posts to. */
        String path() {
            return "/tools/" + slug;
        }
    }

    /** A tool's last outcome: {@code ""} (not run since CAT started), {@code running}, {@code done} or {@code failed}. */
    record Outcome(String state, String message) {
        static final Outcome NONE = new Outcome("", "");
    }

    private final Supplier<String> rig;
    private final RigBusy busy;
    private final RigTools tools;
    private final Supplier<CommanderConnect> connect;
    private final Map<String, String> env;
    private final Consumer<Runnable> worker;
    private final JobLog log = new JobLog();
    private final Map<String, List<String>> registers = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    // Guarded by lock.
    private final Map<Tool, Outcome> outcomes = new EnumMap<>(Tool.class);
    private Tool running;
    private String notice = "";

    /**
     * @param rig     the rig CAT was started with ({@code --rig}); empty is none
     * @param connect the Config tab's Commander Connect values, as they stand when a tool starts
     * @param worker  runs a tool: a daemon thread in production, the caller's thread in a test
     */
    ToolsService(Supplier<String> rig, RigBusy busy, RigTools tools, Supplier<CommanderConnect> connect,
            Map<String, String> env, Consumer<Runnable> worker) {
        this.rig = rig;
        this.busy = busy;
        this.tools = tools;
        this.connect = connect;
        this.env = env == null ? Map.of() : env;
        this.worker = worker;
    }

    /** The real service: the rig's devices, each tool on its own daemon thread. */
    static ToolsService standard(Supplier<String> rig, RigBusy busy, RigTools tools,
            Supplier<CommanderConnect> connect, Map<String, String> env) {
        return new ToolsService(rig, busy, tools, connect, env, work -> Daemons.run("cat-web-tool", work));
    }

    // ------------------------------------------------------------ what the page shows

    String rigName() {
        String name = rig.get();
        return name == null ? "" : name.strip();
    }

    /** Why no tool can start now, or "". */
    String refusal() {
        String held = busy.refusal();
        if (!held.isEmpty()) {
            return held;
        }
        return rigName().isEmpty() ? "No rig: start CAT with --rig to use a tool." : "";
    }

    /** The rig's register ids, read once per rig; empty when there is no rig or it does not load. */
    List<String> registers() {
        String name = rigName();
        if (name.isEmpty()) {
            return List.of();
        }
        return registers.computeIfAbsent(name, n -> {
            try {
                return List.copyOf(tools.registers(n));
            } catch (RuntimeException e) {
                return List.of();
            }
        });
    }

    /** The values Onboard would use: the Config tab's, the password from the environment when the tab has none. */
    CommanderConnect connectSettings() {
        CommanderConnect cc = connect.get();
        if (cc.password().isEmpty()) {
            String fromEnv = env.getOrDefault(CommanderConnect.PASSWORD_ENV, "");
            if (!fromEnv.isEmpty()) {
                return new CommanderConnect(cc.lab(), cc.email(), cc.serviceId(), fromEnv);
            }
        }
        return cc;
    }

    /** The tool running now, or null. */
    Tool running() {
        synchronized (lock) {
            return running;
        }
    }

    Outcome outcome(Tool tool) {
        synchronized (lock) {
            return outcomes.getOrDefault(tool, Outcome.NONE);
        }
    }

    String notice() {
        synchronized (lock) {
            return notice;
        }
    }

    List<String> lines() {
        return log.lines();
    }

    /** The question a tool's button asks first, naming the rig, since every tool changes it. */
    String confirmText(Tool tool, String register) {
        String on = " on rig " + rigName();
        return switch (tool) {
            case PAIR -> "Pair every register" + on + " with its Commander? Provision runs first and after; "
                    + "each register reboots once it is paired.";
            case ONBOARD -> {
                CommanderConnect cc = connectSettings();
                yield "Onboard the Commander" + on + " to Commander Connect " + cc.lab() + " as " + cc.email()
                        + " (service id " + cc.serviceId() + ")? The cloud tunnel starts if it is not running.";
            }
            case REBOOT_C18 -> "Reboot " + register + on + "? Anything on its screen is lost; provision brings its "
                    + "pos-shim back once it is up.";
            case REBOOT_COMMANDER -> "Reboot the Commander" + on + "? The registers lose it until it is back "
                    + "(up to 35 minutes for SSH and CGILink).";
            case PUSH_SHIM -> "Push the pos-shim to " + register + on + "? Provision pushes the pos-shim, the browser "
                    + "override and the CDP relay to the rig's registers and starts the shim.";
        };
    }

    // ------------------------------------------------------------ starting a tool

    /**
     * Starts {@code tool}, or refuses it and says why in {@link #notice()}.
     *
     * @param register the register a Reboot C18 reboots; ignored by the other tools
     * @return "" when started, else the refusal
     */
    String start(Tool tool, String register) {
        String why = refusal();
        String rigName = rigName();
        String target = register == null ? "" : register.strip();
        boolean perRegister = tool == Tool.REBOOT_C18 || tool == Tool.PUSH_SHIM;
        if (why.isEmpty() && perRegister && !registers().contains(target)) {
            why = target.isEmpty() ? (tool == Tool.REBOOT_C18 ? "No register named to reboot."
                    : "No register named to push the shim to.")
                    : "Rig " + rigName + " has no register " + target + ".";
        }
        CommanderConnect cc = connectSettings();
        if (why.isEmpty() && tool == Tool.ONBOARD && !cc.problems().isEmpty()) {
            why = "Commander Connect is not set up on the Config tab: " + String.join("; ", cc.problems()) + ".";
        }
        if (why.isEmpty()) {
            why = busy.begin(tool.kind);
        }
        if (!why.isEmpty()) {
            setNotice(why);
            return why;
        }
        String title = perRegister ? tool.title + " (" + target + ")" : tool.title;
        synchronized (lock) {
            running = tool;
            notice = "";
            outcomes.put(tool, new Outcome("running", title + " running on rig " + rigName + "…"));
        }
        log.start(title);
        log.line(title + " on rig " + rigName);
        try {
            worker.accept(() -> work(tool, title, rigName, target, cc));
        } catch (RuntimeException e) {
            finish(tool, title, false, "could not start: " + DownloadsService.message(e));
        }
        return "";
    }

    void setNotice(String text) {
        synchronized (lock) {
            notice = text == null ? "" : text;
        }
    }

    /** The tool itself, on the worker. Never throws: whatever happens ends in an outcome and frees the flag. */
    private void work(Tool tool, String title, String rigName, String register, CommanderConnect cc) {
        LogSay say = new LogSay(log);
        boolean ok = false;
        String error = "";
        try {
            ok = switch (tool) {
                case PAIR -> tools.pair(rigName, say);
                case ONBOARD -> tools.onboard(rigName, cc, say);
                case REBOOT_C18 -> tools.rebootRegister(rigName, register, say);
                case REBOOT_COMMANDER -> tools.rebootCommander(rigName, say);
                case PUSH_SHIM -> tools.pushShim(rigName, register, say);
            };
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + DownloadsService.message(e);
            log.line("ERROR " + error);
        }
        if (!ok && error.isEmpty()) {
            error = say.lastError().isEmpty() ? "failed (see the log)" : say.lastError();
        }
        finish(tool, title, ok, error);
    }

    private void finish(Tool tool, String title, boolean ok, String error) {
        String message = ok ? title + " done." : title + " failed: " + error;
        log.line(ok ? "DONE  " + title : "FAIL  " + title + ": " + error);
        log.finish(ok ? "" : message, message);
        synchronized (lock) {
            outcomes.put(tool, new Outcome(ok ? "done" : "failed", message));
            running = null;
        }
        busy.end(tool.kind);
    }

    /** The tool's lines into the log, remembering the last error line, which is the failure's reason. */
    private static final class LogSay implements Say {

        private final JobLog log;
        private volatile String lastError = "";

        LogSay(JobLog log) {
            this.log = log;
        }

        @Override
        public void out(String line) {
            for (String part : line.split("\n", -1)) {
                log.line(part);
            }
        }

        @Override
        public void err(String line) {
            for (String part : line.split("\n", -1)) {
                log.line(part);
                if (!part.isBlank()) {
                    lastError = part.strip();
                }
            }
        }

        String lastError() {
            return lastError;
        }
    }
}
