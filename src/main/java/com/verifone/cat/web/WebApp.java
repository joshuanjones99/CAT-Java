package com.verifone.cat.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.prefs.WebSettings;
import com.verifone.cat.results.ResultsLibrary;
import com.verifone.cat.results.RunIndex;
import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runservice.RunService;
import com.verifone.cat.shell.RigBusy;
import com.verifone.cat.shell.Shell;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;

/**
 * The web UI: the site shell (docs/web-ui.md) and its pages. The Python CAT's {@code cat/web/app.py} routes and the
 * shell parts of {@code CatService}.
 *
 * <p>Like the Python CAT's {@code catt web}, it serves localhost only: {@link #start} binds 127.0.0.1, so nothing
 * else on the bench network can press keys on the POS.
 *
 * <p>The shell ({@link Shell}) is one per app: the toasts every page shows, the loading line, the setup status the
 * Setup marker and the Run page's warning show, the one {@link RigBusy} flag the run, Install Upgrade and Launch
 * Simulators share, the open pages (each page's {@code /_cat/shell} poll is its heartbeat) and Shut down CAT.
 * {@code /_cat/ping} answers {@code CAT}, so a second start knows the port is CAT's ({@link WebServe}).
 *
 * <p>Start run is gated here, before the run tab's own handler, as the Python's {@code _signed_in_launch}: no run
 * while a sign-in is missing or Java 17 / the simulators are downloading (unless started with {@code --no-setup}),
 * nor while an install or a simulator action holds the rig; and Start run pressed while a plan's case list is being
 * fetched from Jira starts the run when it lands.
 */
public final class WebApp {

    /** The host the UI binds. Never 0.0.0.0: these buttons drive a live register. */
    public static final String BIND_HOST = "127.0.0.1";

    /** What {@link #PING_PATH} answers, so a second start knows the port is CAT's. */
    public static final String PING_PATH = "/_cat/ping";
    public static final String PING_REPLY = "CAT";

    private WebApp() {}

    /** Everything an app is built from. Tests replace the parts that would reach the machine. */
    static final class Parts {
        PosShimClient shim;
        String title = "CAT-Java";
        String rig = "";
        ResultsLibrary results;
        ConfigForm configForm;
        RunService run;
        SimulatorsService sims;
        Path settingsFile = WebSettings.defaultPath();
        AuthoringService authoring;
        Shell shell = new Shell();
        SetupService setup;
        DownloadsService downloads;
        Map<String, String> env = System.getenv();
        /** What Shut down CAT does after the shutdown hooks: stop the server (and, in {@code serve}, the process). */
        Runnable exit;
    }

    public static Javalin create(PosShimClient shim, String title) {
        return create(shim, title, ResultsLibrary.fromEnvironment(System.getenv()), ConfigForm.standard(),
                RunService.standard("", System.getenv()), SimulatorsService.standard());
    }

    /**
     * As {@link #create(PosShimClient, String)}, for the rig the operator named with {@code --rig}: a rig
     * name, or a path to a rig file, exactly as it was typed. The Run tab's Start launches on it and its
     * preflight checks it. Empty means no rig, and Start is refused.
     */
    public static Javalin create(PosShimClient shim, String title, String rig) {
        return create(shim, title, rig, ConfigForm.standard());
    }

    /** As {@link #create(PosShimClient, String, String)}, with the Config tab's form injected (tests use a temp file). */
    public static Javalin create(PosShimClient shim, String title, String rig, ConfigForm configForm) {
        return create(shim, title, ResultsLibrary.fromEnvironment(System.getenv()), configForm,
                RunService.standard(rig, System.getenv()), SimulatorsService.standard());
    }

    /** As {@link #create(PosShimClient, String)}, with the Launch Simulators page's service injected (tests use fake processes). */
    static Javalin create(PosShimClient shim, String title, SimulatorsService sims) {
        return create(shim, title, ResultsLibrary.fromEnvironment(System.getenv()), ConfigForm.standard(),
                RunService.standard("", System.getenv()), sims);
    }

    /**
     * The same app, with the Results page reading {@code resultsRoot} and its index beside
     * it, so a test never touches the operator's results or {@code ~/.cat}.
     */
    public static Javalin create(PosShimClient shim, String title, Path resultsRoot) {
        return create(shim, title, new ResultsLibrary(resultsRoot, resultsRoot.resolve(RunIndex.INDEX_FILE)),
                ConfigForm.standard(), RunService.standard("", System.getenv()), SimulatorsService.standard());
    }

    /** As {@link #create(PosShimClient, String)}, with the Config tab's form injected (tests use a temp file). */
    public static Javalin create(PosShimClient shim, String title, ConfigForm configForm) {
        return create(shim, title, ResultsLibrary.fromEnvironment(System.getenv()), configForm,
                RunService.standard("", System.getenv()), SimulatorsService.standard());
    }

    /**
     * As {@link #create(PosShimClient, String, ConfigForm)}, with the Run tab's service injected too: tests
     * pass a fake engine, so no run reaches a rig.
     */
    public static Javalin create(PosShimClient shim, String title, ConfigForm configForm, RunService run) {
        return create(shim, title, ResultsLibrary.fromEnvironment(System.getenv()), configForm, run,
                SimulatorsService.standard());
    }

    /** As {@link #create(PosShimClient, String)}, with the web settings file injected (tests use a temp file). */
    static Javalin createWithSettings(PosShimClient shim, String title, Path settingsFile) {
        Parts parts = parts(shim, title, ResultsLibrary.fromEnvironment(System.getenv()), ConfigForm.standard(),
                RunService.standard("", System.getenv()), SimulatorsService.standard());
        parts.settingsFile = settingsFile;
        return build(parts);
    }

    /** As {@link #create(PosShimClient, String)}, with the Authoring page's service injected (tests use a temp folder). */
    static Javalin createWithAuthoring(PosShimClient shim, String title, AuthoringService authoring) {
        Parts parts = parts(shim, title, ResultsLibrary.fromEnvironment(System.getenv()), ConfigForm.standard(),
                RunService.standard("", System.getenv()), SimulatorsService.standard());
        parts.authoring = authoring;
        return build(parts);
    }

    private static Javalin create(PosShimClient shim, String title, ResultsLibrary results, ConfigForm configForm,
            RunService run, SimulatorsService sims) {
        return build(parts(shim, title, results, configForm, run, sims));
    }

    static Parts parts(PosShimClient shim, String title, ResultsLibrary results, ConfigForm configForm,
            RunService run, SimulatorsService sims) {
        Parts parts = new Parts();
        parts.shim = shim;
        parts.title = title;
        parts.results = results;
        parts.configForm = configForm;
        parts.run = run;
        parts.sims = sims;
        return parts;
    }

    /** The app, from its parts. Nothing here probes the machine; a setup check runs only when {@code serve} asks. */
    static Javalin build(Parts parts) {
        Shell shell = parts.shell;
        RunService run = parts.run;
        ConfigForm configForm = parts.configForm;
        SetupService setup = parts.setup != null ? parts.setup : new SetupService(
                () -> com.verifone.cat.setup.SetupAudit.standard(com.verifone.cat.setup.SetupEnv.system()),
                env -> com.verifone.cat.setup.ToolStaging.NONE, shell, Runnable::run, Runnable::run, false);
        DownloadsService downloads = parts.downloads != null ? parts.downloads
                : DownloadsService.standard(parts.env);
        // Start run's Downloads are the page's choice, never a saved file's (webrun's hook).
        RunRoutes.useDownloads(run, downloads);
        // The run service's own start refusals: the sign-ins (when required), and the tools download.
        run.gates(setup::credentialGapsByName, shell::toolsStaging, () -> false);
        AuthoringService authoring = parts.authoring != null ? parts.authoring
                : AuthoringService.standard(run, configForm);
        SimulatorsService sims = parts.sims.withShell(shell).withCcOnboard(configForm::ccOnboard);

        RigBusy busy = shell.busy();
        busy.attachRun(() -> {
            RunService.View view = run.view();
            return view.busy() ? RigBusy.RUN : view.preflighting() ? RigBusy.PREFLIGHT : "";
        });
        shell.holdWhile(() -> {
            var schedule = run.scheduleView();
            if (schedule.continuous()) {
                return "a continuous run";
            }
            return schedule.pending().isEmpty() ? "" : "a scheduled run";
        });
        configForm.attachDownloads(downloads.asState());
        UpgradeService upgrades = UpgradeService.standard(downloads, run::rig, busy, parts.env);
        shell.onShutdown(() -> {
            if (run.view().busy()) {
                run.stop();
            }
        });
        shell.onShutdown(upgrades::stop);
        shell.onShutdown(sims::cancel);

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.staticFiles.add(files -> {
                files.hostedPath = "/static";
                files.directory = "/public";
                files.location = Location.CLASSPATH;
            });
        });
        Runnable exit = parts.exit != null ? parts.exit : () -> stopLater(app);

        app.before(ctx -> Layout.bind(shell));
        app.after(ctx -> Layout.unbind());
        app.before("/run/start", ctx -> gateRunStart(ctx, shell, setup, configForm, run));

        app.get("/", ctx -> ctx.redirect(Section.RUN.path()));
        app.get(PING_PATH, ctx -> ctx.contentType("text/plain").result(PING_REPLY));
        app.get("/_cat/shell", ctx -> {
            shell.heartbeat(ctx.queryParam("page"), System.nanoTime());
            long since = parseLong(ctx.queryParam("since"));
            ctx.json(shellState(shell, since));
        });
        app.post("/_cat/page-closed", ctx -> {
            shell.pageClosed(ctx.queryParam("page"));
            ctx.status(HttpStatus.NO_CONTENT);
        });
        app.post("/_cat/shutdown", ctx -> {
            if (!"true".equals(ctx.header("HX-Request"))) {
                ctx.status(HttpStatus.FORBIDDEN).result("Shut down comes from the CAT-Java page only.");
                return;
            }
            shell.notify("Shutting down CAT…", "info", 5);
            shell.shutdown();
            ctx.status(HttpStatus.NO_CONTENT);
            exit.run();
        });

        RunRoutes.register(app, configForm, run);
        RunConfigRoutes.register(app, configForm);
        ConfigSections.register(app, configForm, downloads);
        ResultsPage.routes(app, parts.results);
        AuthoringRoutes.register(app, authoring);
        DownloadsRoutes.register(app, downloads);
        UpgradeRoutes.register(app, upgrades, downloads);
        SimulatorsRoutes.register(app, sims);
        SetupRoutes.register(app, setup);
        SettingsRoutes.register(app, parts.settingsFile, parts.env, System.getProperty("user.home"));
        app.get("/tools", ctx -> ctx.html(Layout.page(Section.TOOLS, "Tools", ToolsPage.body())));
        // The Python CAT's Manual page became Tools; the old address still works.
        app.get("/manual", ctx -> ctx.redirect(Section.TOOLS.path()));

        PosShimClient shim = parts.shim;
        String title = parts.title;
        app.get("/pos", ctx -> ctx.html(Layout.page(Section.POS, "POS",
                PosPage.body(title, shim.baseUri().toString()))));
        app.get("/fragments/health", ctx -> fragment(ctx, () -> PosPage.health(shim.health())));
        app.get("/fragments/screen", ctx -> fragment(ctx, () -> PosPage.screen(shim.screen())));
        app.get("/fragments/keys", ctx -> fragment(ctx, () -> PosPage.keys(shim.supportedKeys())));
        app.post("/keys", ctx -> {
            // htmx sends HX-Request on every call. A cross-site form post cannot set a
            // custom header, so requiring it stops another page in the operator's
            // browser from pressing keys through this server.
            if (!"true".equals(ctx.header("HX-Request"))) {
                ctx.status(HttpStatus.FORBIDDEN).result("Key presses come from the CAT-Java page only.");
                return;
            }
            String key = ctx.formParam("key");
            if (key == null || key.isBlank()) {
                ctx.status(HttpStatus.BAD_REQUEST).result("No key named.");
                return;
            }
            fragment(ctx, () -> PosPage.keyResult(shim.pressKeys(List.of(key))));
        });
        return app;
    }

    /**
     * The Python's {@code _startable_config} and {@code _signed_in_launch}, ahead of the run tab's Start: a refusal
     * answers with the Overview panel and the run tab's handler never runs.
     */
    static void gateRunStart(Context ctx, Shell shell, SetupService setup, ConfigForm form, RunService run) {
        if (!"true".equals(ctx.header("HX-Request"))) {
            return; // the run tab refuses it itself
        }
        String held = shell.busy().own();
        String why = held.isEmpty() ? "" : shell.busy().refusal();
        if (why.isEmpty()) {
            why = setup.runRefusal();
        }
        if (why.isEmpty() && form.missingRequirements().isEmpty()) {
            String warning = setup.rejectedWarning();
            if (!warning.isEmpty()) {
                shell.notify(warning, "warning", 15);
            }
            return;
        }
        if (why.isEmpty()) {
            String waiting = form.runWhenFetched();
            if (waiting.isEmpty()) {
                return; // nothing to run: the run tab says what to pick
            }
            form.onFetched(() -> {
                var plan = com.verifone.cat.runservice.RunStart.from(form, run.rig());
                if (plan.startable()) {
                    run.start(plan.config());
                }
            });
            shell.notify(waiting, "info", 10);
            ctx.html(RunRoutes.overview(form, run));
            ctx.skipRemainingHandlers();
            return;
        }
        shell.notify(why, why.startsWith("Run not started") ? "error" : "warning", 15);
        run.refuse(why);
        ctx.html(RunRoutes.overview(form, run));
        ctx.skipRemainingHandlers();
    }

    /** What a page's poll gets: the toasts after {@code since}, the loading line and the setup status. */
    static Map<String, Object> shellState(Shell shell, long since) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("last", shell.lastToast());
        List<Map<String, Object>> toasts = new ArrayList<>();
        for (Shell.Toast toast : shell.toastsSince(since)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", toast.id());
            row.put("message", toast.message());
            row.put("kind", toast.kind());
            row.put("timeout", toast.timeoutSeconds());
            toasts.add(row);
        }
        state.put("toasts", toasts);
        state.put("loading", shell.loadingLine());
        state.put("setup", shell.setupStatus());
        return state;
    }

    private static long parseLong(String text) {
        try {
            return text == null ? 0 : Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Stops the server from another thread, so the request that asked can answer first. */
    private static void stopLater(Javalin app) {
        Thread thread = new Thread(app::stop, "cat-web-shutdown");
        thread.setDaemon(false);
        thread.start();
    }

    public static Javalin start(PosShimClient shim, String title, int port) {
        return create(shim, title).start(BIND_HOST, port);
    }

    /** As {@link #start(PosShimClient, String, int)}, for the rig named with {@code --rig}. See {@link #create(PosShimClient, String, String)}. */
    public static Javalin start(PosShimClient shim, String title, String rig, int port) {
        return create(shim, title, rig).start(BIND_HOST, port);
    }

    private static void fragment(Context ctx, Supplier<String> render) {
        String html;
        try {
            html = render.get();
        } catch (DeviceError e) {
            html = PosPage.error(e);
        }
        ctx.html(html);
    }
}
