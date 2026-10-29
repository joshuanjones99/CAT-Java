package com.verifone.cat.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * The AU (classic) path against scripted jars, a scripted CGILink and an in-memory SSH target: the Python's
 * {@code test_upgrade_classic}. No hardware, no real jar.
 */
class ClassicUpgradeTest {

    private static final String RIG = "/upgrade/rigs/psi.yaml";
    private static final String TOKEN_REPLY = "<reply><token>t-42</token></reply>";
    private static final String DONE_REPLY = "<reply><status>UPGRADE_COMPLETED_SUCCESSFULLY</status></reply>";

    @TempDir
    Path dir;

    private Rig rig() throws Exception {
        return RigLoader.loadFile(Path.of(getClass().getResource(RIG).toURI())).rig();
    }

    private static ClassicUpgradeTools tools() {
        return new ClassicUpgradeTools("AutoUpgrade.jar", "NetLoaderClient.jar", "overlay.zip", "root-access.tgz",
                "gemreg-license.tgz", null);
    }

    private static ClassicUpgrade.Settings fast() {
        return new ClassicUpgrade.Settings(Duration.ZERO, Duration.ofMinutes(30), Duration.ofMillis(200),
                Duration.ofMillis(10));
    }

    static Element xml(String text) {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Answers {@code vupgradesummary} from a queue (the last answer repeats); records every call and OTP read. */
    static final class ScriptedCgi implements UpgradeCgi {
        final List<String> calls = new ArrayList<>();
        final Deque<String> summaries = new ArrayDeque<>();
        RuntimeException closeDayFault;
        String lastOtp;

        ScriptedCgi(String... replies) {
            summaries.addAll(List.of(replies));
        }

        @Override
        public Element upgradeSummary(Supplier<String> readOtp) {
            calls.add("vupgradesummary");
            lastOtp = readOtp.get();
            String reply = summaries.size() > 1 ? summaries.pop() : summaries.peek();
            return xml(reply);
        }

        @Override
        public void closeDay(Supplier<String> readOtp) {
            calls.add("cclosepdnow");
            if (closeDayFault != null) {
                throw closeDayFault;
            }
        }

        @Override
        public void beginUpgrade(String token, Supplier<String> readOtp) {
            calls.add("cbeginupgrade " + token);
        }
    }

    /** Records each jar's argv and timeout; answers by the jar's name. */
    static final class Jar implements Jars {
        final List<List<String>> calls = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        Result overlay = new Result(0, "", "");
        Result lic = new Result(0, "", "");
        Result netloader = new Result(0, RootAccess.NETLOADER_SUCCESS, "");

        @Override
        public Result run(List<String> argv, Duration timeout) throws IOException {
            calls.add(List.copyOf(argv));
            timeouts.add(timeout);
            if (argv.contains("upgr")) {
                if (overlay == null) {
                    throw new IOException("timed out after 5000 ms: java");
                }
                return overlay;
            }
            return argv.contains("lic") ? lic : netloader;
        }
    }

    private static InMemoryTransport box() {
        return new InMemoryTransport("192.0.2.11").command("cat /var/logins/tat", new CommandResult(0, "424242\n", ""));
    }

    private List<StepResult> run(Jar jars, ScriptedCgi cgi, InMemoryTransport box) throws Exception {
        return new ClassicUpgrade(tools(), target -> box, jars, host -> cgi, fast(), line -> { }).run(rig(), Map.of());
    }

    private static List<String> steps(List<StepResult> results) {
        return results.stream().map(r -> r.step() + ":" + r.status().wire()).collect(Collectors.toList());
    }

    @Test
    void aFullUpgradeRunsEveryStepInOrderAndPushesEveryRegister() throws Exception {
        Jar jars = new Jar();
        ScriptedCgi cgi = new ScriptedCgi(TOKEN_REPLY, "<reply><status>RUNNING</status></reply>", DONE_REPLY);
        InMemoryTransport box = box();

        List<StepResult> results = run(jars, cgi, box);

        assertEquals(List.of("bypass_mem_size_check:done", "push_commander_overlay:done", "cgi_upgrade_dance:done",
                "push_commander_root_access:done", "push_register_root_access:done"), steps(results));
        assertEquals(List.of("vupgradesummary", "cclosepdnow", "cbeginupgrade t-42", "vupgradesummary",
                "vupgradesummary"), cgi.calls);
        assertEquals("424242", cgi.lastOtp, "the OTP is the Commander's /var/logins/tat over SSH");
        assertEquals(List.of("java", "-jar", "AutoUpgrade.jar", "-q", "upgr", "overlay.zip", "192.0.2.11", "443", "0"),
                jars.calls.get(0));
        assertEquals(List.of("java", "-jar", "AutoUpgrade.jar", "-q", "lic", "gemreg-license.tgz", "192.0.2.11",
                "443", "0"), jars.calls.get(1));
        assertEquals("root-access.tgz", jars.calls.get(2).get(5));
        assertEquals(List.of("java", "-jar", "NetLoaderClient.jar", "192.0.2.101", "root-access.tgz"),
                jars.calls.get(3));
        assertTrue(box.ran().get(0).contains("bypass.mem.size.check=true"), box.ran().toString());
        assertTrue(box.closed());
    }

    @Test
    void theOverlayPushGetsItsOwnBudgetNotTheSshTimeout() throws Exception {
        Jar jars = new Jar();
        run(jars, new ScriptedCgi(TOKEN_REPLY, DONE_REPLY), box());
        assertEquals(Duration.ofMinutes(30), jars.timeouts.get(0));
        assertEquals(Duration.ofMinutes(30), ClassicUpgrade.Settings.defaults().overlayPushTimeout());
        assertEquals(Duration.ofSeconds(190), ClassicUpgrade.Settings.defaults().overlaySettle());
        assertTrue(jars.timeouts.get(1).compareTo(Duration.ofMinutes(5)) < 0, "the lic push is on the SSH timeout");
    }

    @Test
    void aFailedBypassStopsBeforeAnyPush() throws Exception {
        Jar jars = new Jar();
        InMemoryTransport box = new InMemoryTransport("192.0.2.11").failRunsWith(new DeviceError("no route"));
        List<StepResult> results = run(jars, new ScriptedCgi(TOKEN_REPLY), box);

        assertEquals(List.of("bypass_mem_size_check:failed"), steps(results));
        assertTrue(jars.calls.isEmpty());
        assertTrue(box.closed());
    }

    @Test
    void aFailedOverlayPushStopsBeforeTheCgiDance() throws Exception {
        Jar jars = new Jar();
        jars.overlay = new Jars.Result(3, "", "boom");
        ScriptedCgi cgi = new ScriptedCgi(TOKEN_REPLY);
        List<StepResult> results = run(jars, cgi, box());

        assertEquals(List.of("bypass_mem_size_check:done", "push_commander_overlay:failed"), steps(results));
        assertEquals("Auto_Upgrade.jar exited 3: boom", results.get(1).detail());
        assertTrue(cgi.calls.isEmpty());
    }

    @Test
    void anOverlayPushThatTimesOutFails() {
        Jar jars = new Jar();
        jars.overlay = null;
        StepResult result = new ClassicUpgrade(tools(), t -> box(), jars, h -> null, fast(), l -> { })
                .pushCommanderOverlay("192.0.2.11");
        assertTrue(result.failed());
        assertTrue(result.detail().contains("timed out"), result.detail());
    }

    @Test
    void aFailedCgiDanceStopsBeforeTheRootAccessPushes() throws Exception {
        Jar jars = new Jar();
        ScriptedCgi cgi = new ScriptedCgi(TOKEN_REPLY);
        cgi.closeDayFault = new DeviceError("cclosepdnow faulted");
        List<StepResult> results = run(jars, cgi, box());

        assertEquals("cgi_upgrade_dance:failed", steps(results).get(2));
        assertEquals("cclosepdnow faulted", results.get(2).detail());
        assertEquals(1, jars.calls.size(), "only the overlay push ran");
    }

    @Test
    void aSummaryWithNoTokenFailsTheDance() {
        StepResult result = ClassicUpgrade.runCgiUpgradeDance(new ScriptedCgi("<reply><x>1</x></reply>"), () -> "",
                Duration.ofMillis(50), Duration.ofMillis(10));
        assertTrue(result.failed());
        assertTrue(result.detail().contains("no recognized upgrade-token element"), result.detail());
    }

    @Test
    void anUpgradeThatNeverCompletesTimesOut() {
        ScriptedCgi cgi = new ScriptedCgi(TOKEN_REPLY, "<reply><status>RUNNING</status></reply>");
        StepResult result = ClassicUpgrade.runCgiUpgradeDance(cgi, () -> "", Duration.ofMillis(60),
                Duration.ofMillis(10));
        assertTrue(result.failed());
        assertTrue(result.detail().contains("Commander AU upgrade completion (vupgradesummary)"), result.detail());
    }

    @Test
    void aFailedLicPushStopsBeforeTheRegisters() throws Exception {
        Jar jars = new Jar();
        jars.lic = new Jars.Result(1, "", "<faultCode>CGIPortal.InternalError</faultCode><e:message>nope</e:message>");
        List<StepResult> results = run(jars, new ScriptedCgi(TOKEN_REPLY, DONE_REPLY), box());

        assertEquals("push_commander_root_access:failed", steps(results).get(results.size() - 1));
        assertEquals("Auto_Upgrade.jar exited 1: CGIPortal.InternalError: nope", results.get(3).detail());
        assertFalse(jars.calls.stream().anyMatch(argv -> argv.contains("NetLoaderClient.jar")));
    }

    // -- the tools -------------------------------------------------------------------------------------------------

    @Test
    void fromEnvNamesEveryMissingVariable() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClassicUpgradeTools.fromEnv(Map.of()));
        assertTrue(e.getMessage().startsWith("UpgradeMode.CLASSIC requires these environment variables, missing: "
                + "PETROTEST_AUTO_UPGRADE_JAR, PETROTEST_NETLOADER_JAR"), e.getMessage());
        assertTrue(e.getMessage().endsWith("Nothing was attempted."));
    }

    @Test
    void fromEnvReadsEveryPathAndTheJavaOverride() {
        Map<String, String> env = Map.of("PETROTEST_AUTO_UPGRADE_JAR", "a.jar", "PETROTEST_NETLOADER_JAR", "n.jar",
                "PETROTEST_UPGRADE_OVERLAY_PATH", "o.zip", "PETROTEST_UPGRADE_ROOT_ACCESS_PATH", "r.tgz",
                "PETROTEST_UPGRADE_GEMREG_LICENSE_PATH", "g.tgz");
        ClassicUpgradeTools tools = ClassicUpgradeTools.fromEnv(env);
        assertEquals("a.jar", tools.autoUpgradeJar());
        assertEquals("o.zip", tools.overlayPath());
        assertEquals("g.tgz", tools.gemregLicensePath());
        assertEquals("java", tools.javaBin());

        Map<String, String> withJava = new java.util.HashMap<>(env);
        withJava.put("PETROTEST_JAVA_BIN", "/opt/java17/bin/java");
        assertEquals("/opt/java17/bin/java", ClassicUpgradeTools.fromEnv(withJava).javaBin());
    }

    @Test
    void forBuildTakesTheBuildsOverlayAndJarAndNamesWhatIsMissing() throws IOException {
        // Controller defaults rooted here, so the test never looks at the real C:\CICD.
        Files.writeString(dir.resolve("overlay.zip"), "o");
        Files.writeString(dir.resolve("Auto_Upgrade.jar"), "a");
        Files.writeString(dir.resolve("java.exe"), "j");
        NewInstallTools defaults = new NewInstallTools(dir, dir.resolve("java.exe").toString(),
                dir.resolve("NetLoaderClient.jar").toString(), dir.resolve("root.tgz").toString(),
                dir.resolve("gemreg.tgz").toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClassicUpgradeTools.forBuild(defaults));
        assertEquals("the AU upgrade needs these files, missing: " + dir.resolve("NetLoaderClient.jar") + ", "
                + dir.resolve("root.tgz") + ", " + dir.resolve("gemreg.tgz"), e.getMessage());

        for (String name : List.of("NetLoaderClient.jar", "root.tgz", "gemreg.tgz")) {
            Files.writeString(dir.resolve(name), "x");
        }
        ClassicUpgradeTools tools = ClassicUpgradeTools.forBuild(defaults);
        assertEquals(dir.resolve("overlay.zip").toString(), tools.overlayPath());
        assertEquals(dir.resolve("Auto_Upgrade.jar").toString(), tools.autoUpgradeJarPath());
    }

    @Test
    void theVhqPathFailsSayingWhatItNeeds() {
        List<StepResult> results = VhqUpgrade.run();
        assertEquals(1, results.size());
        assertTrue(results.get(0).failed());
        assertEquals("upgrade_vhq", results.get(0).step());
        assertTrue(results.get(0).detail().startsWith("not implemented — needs a live VHQ tenant"));
    }
}
