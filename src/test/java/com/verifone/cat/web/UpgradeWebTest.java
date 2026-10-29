package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.build.FakeArtifactory;
import com.verifone.cat.shell.RigBusy;
import com.verifone.cat.sims.SimFetch;
import com.verifone.cat.upgrade.NewInstallTools;
import com.verifone.cat.upgrade.StepResult;

import io.javalin.Javalin;

/**
 * Install Upgrade over HTTP, with a fake installer in place of {@code NewInstall}: no rig, no SSH, no
 * Commander, and no C:\CICD. The build comes from {@link FakeArtifactory} or a temp builds root.
 * Evidence about this repository only.
 */
class UpgradeWebTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String RIG = "lab";

    @TempDir
    Path root;

    private FakeArtifactory fake;
    private DownloadsService downloads;
    private UpgradeService upgrades;
    private FakeInstaller installer;
    private final AtomicBoolean runBusy = new AtomicBoolean(false);
    private String rig = RIG;
    private Javalin app;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String host = InetAddress.getLoopbackAddress().getHostAddress();

    /** A fake PSI installer: records what it was asked, and answers with {@link #result}. */
    static final class FakeInstaller implements UpgradeService.Installer {
        final List<String> rigs = new CopyOnWriteArrayList<>();
        final List<NewInstallTools> tools = new CopyOnWriteArrayList<>();
        volatile List<StepResult> result = List.of(StepResult.done("install_buypass"));

        @Override
        public List<StepResult> install(String rig, NewInstallTools t, Map<String, String> env,
                Consumer<String> log) {
            rigs.add(rig);
            tools.add(t);
            log.accept("fake installer for " + rig);
            return result;
        }
    }

    /** A fake psi-init: records the rigs it reset, and answers {@link #ok}. */
    final List<String> psiRigs = new CopyOnWriteArrayList<>();
    volatile boolean psiOk = true;

    /** A fake Simulator Build side: what is running, and what each replacement answers. */
    final Map<String, String> runningSims = new java.util.concurrent.ConcurrentHashMap<>();
    final List<String> replaced = new CopyOnWriteArrayList<>();
    volatile java.util.concurrent.CountDownLatch installGate;
    private RigBusy busy;

    private UpgradeService service(java.util.function.Function<Path, NewInstallTools> tools) {
        return new UpgradeService(downloads, () -> rig, busy, installer, tools,
                (r, env, log) -> {
                    psiRigs.add(r);
                    log.accept("psi-init for " + r);
                    return psiOk;
                },
                new UpgradeService.SimReplacer() {
                    @Override
                    public Map<String, String> running(String r) {
                        return Map.copyOf(runningSims);
                    }

                    @Override
                    public List<SimFetch.FetchResult> replace(String r, String branch, String build,
                            Consumer<String> log) {
                        replaced.add(branch + " " + build);
                        return List.of(new SimFetch.FetchResult("Petro Simulator Suite", true,
                                "Petro Simulator Suite: replaced with " + branch + " " + build));
                    }
                },
                Map.of());
    }

    @BeforeEach
    void setUp() {
        fake = new FakeArtifactory(DownloadsWebTest.tree());
        downloads = new DownloadsService(() -> fake.client(), root);
        installer = new FakeInstaller();
        busy = new RigBusy();
        busy.attachRun(() -> runBusy.get() ? RigBusy.RUN : "");
        upgrades = service(NewInstallTools::defaults);
        serve(upgrades);
    }

    private void serve(UpgradeService service) {
        if (app != null) {
            app.stop();
        }
        app = Javalin.create(config -> config.showJavalinBanner = false);
        DownloadsRoutes.register(app, downloads);
        UpgradeRoutes.register(app, service, downloads);
        app.start(host, 0);
    }

    @AfterEach
    void stop() {
        app.stop();
        fake.close();
    }

    private String uri(String path) {
        return "http://" + host + ":" + app.port() + path;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(uri(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String value, boolean htmx)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("value="
                        + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sets a Downloads choice field over HTTP, then waits for any listing it started. */
    private void set(String field, String value) throws IOException, InterruptedException {
        post("/downloads/set?field=" + field, value, true);
        downloads.awaitIdle(WAIT);
    }

    /** Posts Start, then waits for the install to finish. */
    private HttpResponse<String> startInstall() throws IOException, InterruptedException {
        HttpResponse<String> response = post("/upgrade/start", "", true);
        downloads.awaitIdle(WAIT);
        return response;
    }

    @Test
    void thePageRendersTheDownloadsPickersAndTheStages() throws Exception {
        HttpResponse<String> response = get("/upgrade");

        assertEquals(200, response.statusCode());
        String html = response.body();
        assertTrue(html.contains("<h1>Install Upgrade</h1>"), html);
        assertTrue(html.contains("id=\"downloads-panel\""), html);
        assertTrue(html.contains("id=\"upgrade-panel\""), html);
        assertTrue(html.contains("Start upgrade"), html);
        assertTrue(html.contains("Stages"), html);
        assertFalse(html.contains("Not built yet"), html);
    }

    @Test
    void startIsRefusedWhileARunIsInProgress() throws Exception {
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");
        set("mode", "new_install");
        runBusy.set(true);

        HttpResponse<String> response = post("/upgrade/start", "", true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("A run is already in progress."), response.body());
        assertTrue(installer.rigs.isEmpty());
    }

    @Test
    void anInstallHoldsTheOneBusyFlagSoASimulatorLaunchIsRefused() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        UpgradeService.Installer slow = (r, t, env, log) -> {
            inside.countDown();
            try {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(StepResult.done("install_buypass"));
        };
        upgrades = new UpgradeService(downloads, () -> rig, busy, slow, NewInstallTools::defaults,
                (r, env, log) -> true, null, Map.of());
        serve(upgrades);
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");
        set("mode", "new_install");

        post("/upgrade/start", "", true);
        assertTrue(inside.await(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(RigBusy.INSTALL, busy.own());
        assertEquals("An install is already in progress.", busy.begin(RigBusy.SIMS_LAUNCH));
        assertTrue(get(UpgradePage.PATH).body().contains("hx-post=\"/upgrade/stop\""), "Stop is offered");

        release.countDown();
        downloads.awaitIdle(WAIT);
        assertEquals("", busy.own());
        assertEquals("", busy.begin(RigBusy.SIMS_LAUNCH));
    }

    @Test
    void stopEndsTheInstallBeforeItsNextStage() throws Exception {
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        UpgradeService.Installer slow = (r, t, env, log) -> {
            inside.countDown();
            try {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(StepResult.done("install_buypass"));
        };
        upgrades = new UpgradeService(downloads, () -> rig, busy, slow, NewInstallTools::defaults,
                (r, env, log) -> {
                    psiRigs.add(r);
                    return true;
                }, null, Map.of());
        serve(upgrades);
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");
        set("mode", "new_install");
        post("/upgrade/start", "", true);
        assertTrue(inside.await(30, java.util.concurrent.TimeUnit.SECONDS));

        post("/upgrade/stop", "", true);
        release.countDown();
        downloads.awaitIdle(WAIT);

        assertTrue(psiRigs.isEmpty(), "psi-init never ran after Stop");
        assertEquals("stopped", upgrades.message());
        assertTrue(get(UpgradePage.PATH).body().contains("psi-init --no-pairing (after PSI): stopped"));
        assertEquals("", busy.own());
    }

    @Test
    void startNeedsTheRigNamed() throws Exception {
        rig = "";
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");
        set("mode", "new_install");

        String html = get("/upgrade").body();

        assertTrue(html.contains("No rig: start CAT with --rig before an install."), html);
        assertTrue(html.contains("disabled"), html);
    }

    @Test
    void auIsNotBuiltYetAndNamesItsModule() throws Exception {
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");
        set("mode", "classic");

        String html = get("/upgrade").body();

        assertTrue(html.contains("AU (classic) is not built yet (petrotest/upgrade/classic.py)"), html);
    }

    @Test
    void aSimulatorBuildNeedsItsNumberAndIsRefusedWhileOneRuns() throws Exception {
        set("simBranch", "master");
        assertTrue(upgrades.refusal().contains("a Simulator Build number on master"), upgrades.refusal());

        set("simBuild", "74");
        assertEquals("", upgrades.refusal());
        runningSims.put("Petro Simulator Suite", "listening on 1053");

        post("/upgrade/start", "", true);
        downloads.awaitIdle(WAIT);

        assertTrue(upgrades.notice().startsWith("simulators are running, so the Simulator Build cannot replace them: "
                + "Petro Simulator Suite (listening on 1053)"), upgrades.notice());
        assertTrue(replaced.isEmpty());
        assertTrue(installer.rigs.isEmpty());
    }

    @Test
    void skipWithASimulatorBuildReplacesOnlyTheSimulators() throws Exception {
        set("simBranch", "master");
        set("simBuild", "74");

        startInstall();

        assertEquals(List.of("master 74"), replaced);
        assertTrue(installer.rigs.isEmpty());
        assertTrue(psiRigs.isEmpty());
        String panel = get(UpgradePage.PATH).body();
        assertTrue(panel.contains("Replace the simulators: done"), panel);
        assertTrue(panel.contains("Install it (PSI): skipped"), panel);
    }

    @Test
    void skipInstallsNothing() throws Exception {
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");

        assertTrue(upgrades.refusal().contains("an Upgrade mode other than Skip, or a Simulator Build"),
                upgrades.refusal());
    }

    @Test
    void psiInstallDownloadsTheBuildThenRunsTheInstaller() throws Exception {
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");
        set("mode", "new_install");

        HttpResponse<String> response = startInstall();

        assertEquals(200, response.statusCode());
        assertEquals(List.of(RIG), installer.rigs);
        assertEquals(root.resolve("Solutions1082").resolve("buypass"), installer.tools.get(0).buildDir());
        assertTrue(Files.isRegularFile(root.resolve("Solutions1082/buypass/Petro_Suite_Installer.jar")));
        assertEquals("", downloads.busy());
        assertEquals("done", downloads.job().state());
        assertEquals("Install Upgrade done. No test was run.", downloads.job().message());
        assertEquals(List.of(RIG), psiRigs);
        String panel = get(UpgradePage.PATH).body();
        assertTrue(panel.contains("Install it (PSI): done"), panel);
        assertTrue(panel.contains("psi-init --no-pairing (after PSI): done"), panel);
        assertTrue(panel.contains("Replace the simulators: skipped"), panel);
        String downloadsPanel = get(DownloadsPanel.PATH).body();
        assertTrue(downloadsPanel.contains("fake installer for lab"), downloadsPanel);
    }

    @Test
    void anInstallerFailureStopsTheStageAndSaysWhy() throws Exception {
        installer.result = List.of(StepResult.failed("install_buypass", "breadcrumb missing"));
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");
        set("mode", "new_install");

        startInstall();

        String panel = get(UpgradePage.PATH).body();
        assertTrue(panel.contains("Install it (PSI): failed"), panel);
        assertTrue(panel.contains("breadcrumb missing"), panel);
        assertEquals("failed", downloads.job().state());
    }

    @Test
    void aBuildAlreadyOnThisPcIsInstalledWithoutADownload() throws Exception {
        Path dir = root.resolve("Solutions1082").resolve("buypass");
        Files.createDirectories(dir);
        Files.write(dir.resolve("overlay.zip"), new byte[] {1});
        Files.write(dir.resolve("Petro_Suite_Installer.jar"), new byte[] {2});
        Files.write(dir.resolve("Auto_Upgrade.jar"), new byte[] {3});
        set("mode", "new_install");
        int requests = fake.requests.size();

        startInstall();

        assertEquals(List.of(RIG), installer.rigs);
        assertEquals(dir, installer.tools.get(0).buildDir());
        assertEquals(requests, fake.requests.size(), "nothing was downloaded");
        assertEquals("done", downloads.job().state());
    }

    @Test
    void toolsThatAreMissingRefuseTheInstallBeforeAnythingRuns() throws Exception {
        Path dir = root.resolve("Solutions1082").resolve("buypass");
        Files.createDirectories(dir);
        Files.write(dir.resolve("overlay.zip"), new byte[] {1});
        Files.write(dir.resolve("Petro_Suite_Installer.jar"), new byte[] {2});
        Files.write(dir.resolve("Auto_Upgrade.jar"), new byte[] {3});
        Map<String, String> env = Map.of(
                "PETROTEST_JAVA17_BIN", root.resolve("no-java.exe").toString(),
                "PETROTEST_NETLOADER_JAR", root.resolve("no-netloader.jar").toString(),
                "PETROTEST_UPGRADE_ROOT_ACCESS_PATH", root.resolve("no-root.tgz").toString(),
                "PETROTEST_UPGRADE_GEMREG_LICENSE_PATH", root.resolve("no-gemreg.tgz").toString());
        upgrades = service(b -> NewInstallTools.forBuild(b, env));
        serve(upgrades);
        set("mode", "new_install");

        startInstall();

        assertTrue(installer.rigs.isEmpty(), "the installer was not started");
        String panel = get(UpgradePage.PATH).body();
        assertTrue(panel.contains("Install it (PSI): failed"), panel);
        assertTrue(panel.contains("Nothing was attempted."), panel);
    }

    @Test
    void theConfirmNamesTheRigAndTheBuild() throws Exception {
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");
        set("mode", "new_install");

        String html = get("/upgrade").body();

        assertTrue(html.contains("Install Solutions 1082 (buypass) on rig lab?"), html);
        assertTrue(html.contains("hx-confirm="), html);
        assertTrue(html.contains("reformats the Commander"), html);
    }

    @Test
    void startWithoutHtmxIsRefusedAndNothingRuns() throws Exception {
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");
        set("mode", "new_install");

        HttpResponse<String> response = post("/upgrade/start", "", false);
        downloads.awaitIdle(WAIT);

        assertEquals(403, response.statusCode());
        assertTrue(installer.rigs.isEmpty());
        assertEquals("", downloads.busy());
    }
}
