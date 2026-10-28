package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.sims.FakeAccessBridge.Node;

/**
 * The supervisor's click flow over fake windows and a fake bridge: the checks that stop a row before
 * it is spawned, the window lookup by PID, the minimise on the way out, and the {@code windowed:}
 * minimising. No window, DLL or home-directory file of this machine is touched: the properties file
 * and the JDK are staged under {@code @TempDir}.
 */
class AccessBridgeClickerTest {

    private static final long PID = 4242;
    private static final long HWND = 0x5150;

    @TempDir
    Path tools;

    private final FakeWindowOps windows = new FakeWindowOps();
    private final List<Path> opened = new CopyOnWriteArrayList<>();
    private final List<Duration> pauses = new CopyOnWriteArrayList<>();
    private FakeAccessBridge bridge = new FakeAccessBridge(HWND, Node.panel(Node.button("Start Host")));

    /** Records each pause and waits a millisecond, so polling loops neither spin nor drag. */
    private final Consumer<Duration> pause = d -> {
        pauses.add(d);
        Park.park(Duration.ofMillis(1));
    };

    private Path properties() {
        return tools.resolve(".accessibility.properties");
    }

    private AccessBridgeClicker clicker(boolean onWindows) {
        return new AccessBridgeClicker(windows, dll -> {
            opened.add(dll);
            return bridge;
        }, properties(), onWindows, Duration.ofMillis(10), pause);
    }

    private Path stageJdk(boolean withDll) throws IOException {
        Path bin = Files.createDirectories(tools.resolve("Java17").resolve("jdk-17.0.5+8").resolve("bin"));
        Files.createFile(bin.resolve("javaw.exe"));
        if (withDll) {
            Files.createFile(bin.resolve("windowsaccessbridge-64.dll"));
        }
        return bin;
    }

    private void enable() throws IOException {
        Files.writeString(properties(), "assistive_technologies=com.sun.java.accessibility.AccessBridge\n"
                + "screen_magnifier_present=true\n");
    }

    private SimContext ctx() {
        return SimContext.of(tools, "");
    }

    private static SimEntry loysim() {
        return SimEntry.javaw("PCATS01 Loyalty").cwd("PCATS01").argv("-jar", "{cwd}/LoySim9.1.jar")
                .click("Start Host").clicked().build();
    }

    private static long deadlineIn(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    // ----- unavailable ------------------------------------------------------------------

    @Test
    void offWindowsNothingCanBeClicked() throws IOException {
        stageJdk(true);
        enable();
        assertEquals(Optional.of("the Java Access Bridge needs Windows"), clicker(false).unavailable(ctx()));
    }

    @Test
    void noJavawIsNamedWithTheJavaHome() {
        assertEquals(Optional.of("no javaw.exe under " + tools.resolve("Java17")), clicker(true).unavailable(ctx()));
    }

    @Test
    void noBridgeDllIsNamedBesideTheJavaw() throws IOException {
        Path bin = stageJdk(false);
        enable();
        assertEquals(Optional.of("windowsaccessbridge-64.dll not found beside " + bin.resolve("javaw.exe")),
                clicker(true).unavailable(ctx()));
    }

    @Test
    void aBridgeNotSwitchedOnGivesTheExactJabswitchLine() throws IOException {
        Path bin = stageJdk(true);

        Optional<String> why = clicker(true).unavailable(ctx());

        assertEquals(Optional.of("the Java Access Bridge is not enabled for this Windows user (no "
                + "assistive_technologies=com.sun.java.accessibility.AccessBridge in " + properties() + "): run \""
                + bin.resolve("jabswitch.exe") + "\" -enable once, then start again"), why);
    }

    @Test
    void aStagedJdkAndAnEnabledBridgeCanBeClicked() throws IOException {
        stageJdk(true);
        enable();
        assertEquals(Optional.empty(), clicker(true).unavailable(ctx()));
    }

    @Test
    void theEnabledFlagIsTheAssistiveTechnologiesLine() throws IOException {
        Path file = properties();
        assertFalse(AccessBridgeClicker.accessBridgeEnabled(file), "a missing file is off");
        Files.writeString(file, "#assistive_technologies=com.sun.java.accessibility.AccessBridge\n");
        assertFalse(AccessBridgeClicker.accessBridgeEnabled(file), "a comment is off");
        Files.writeString(file, "assistive_technologies=org.other.Thing\n");
        assertFalse(AccessBridgeClicker.accessBridgeEnabled(file), "another technology is off");
        Files.writeString(file, "screen_magnifier_present=true\r\n assistive_technologies = "
                + "com.sun.java.accessibility.AccessBridge\r\n");
        assertTrue(AccessBridgeClicker.accessBridgeEnabled(file));
    }

    // ----- click ------------------------------------------------------------------------

    @Test
    void theMainWindowIsFoundByPidClickedThroughTheBundledDllAndMinimised() throws IOException {
        Path bin = stageJdk(true);
        enable();
        windows.windows.put(PID, List.of(new WindowOps.TopWindow(HWND, false)));
        windows.hiddenFor = 3;

        Clicker.Clicked clicked = clicker(true).click(loysim(), new FakeProcess(PID), ctx(),
                deadlineIn(Duration.ofSeconds(10)));

        assertEquals(new Clicker.Clicked(true, "clicked Start Host"), clicked);
        assertEquals(List.of("Start Host"), bridge.clicked);
        assertEquals(List.of(HWND), windows.minimised);
        assertEquals(List.of(bin.resolve("windowsaccessbridge-64.dll")), opened);
        assertTrue(pauses.contains(AccessBridgeClicker.POLL), "the lookup polled while the window was hidden");
    }

    @Test
    void theBridgeIsOpenedOncePerDll() throws IOException {
        stageJdk(true);
        enable();
        windows.windows.put(PID, List.of(new WindowOps.TopWindow(HWND, false)));
        AccessBridgeClicker clicker = clicker(true);

        clicker.click(loysim(), new FakeProcess(PID), ctx(), deadlineIn(Duration.ofSeconds(10)));
        clicker.click(loysim(), new FakeProcess(PID), ctx(), deadlineIn(Duration.ofSeconds(10)));

        assertEquals(1, opened.size());
    }

    @Test
    void aFailedClickIsMinimisedAndCarriesTheAvailableButtons() throws IOException {
        stageJdk(true);
        enable();
        bridge = new FakeAccessBridge(HWND, Node.panel(Node.button("Stop Host")));
        windows.windows.put(PID, List.of(new WindowOps.TopWindow(HWND, false)));

        Clicker.Clicked clicked = clicker(true).click(loysim(), new FakeProcess(PID), ctx(),
                deadlineIn(Duration.ofSeconds(10)));

        assertEquals(new Clicker.Clicked(false, "clicked nothing; 'Start Host' not found (available: Stop Host)"),
                clicked);
        assertEquals(List.of(HWND), windows.minimised);
    }

    @Test
    void noWindowWithinTheBudgetIsARow() throws IOException {
        stageJdk(true);
        enable();

        Clicker.Clicked clicked = clicker(true).click(loysim(), new FakeProcess(PID), ctx(),
                deadlineIn(Duration.ofMillis(200)));

        assertEquals(new Clicker.Clicked(false, "no window handle for pid 4242 within the start budget"), clicked);
        assertTrue(bridge.clicked.isEmpty());
        assertTrue(windows.minimised.isEmpty());
    }

    @Test
    void aProcessThatExitsBeforeShowingAWindowStopsTheLookupAtOnce() throws IOException {
        stageJdk(true);
        enable();
        FakeProcess process = new FakeProcess(PID);
        process.alive = false;
        process.exitCode = 1;

        long started = System.nanoTime();
        Clicker.Clicked clicked = clicker(true).click(loysim(), process, ctx(), deadlineIn(Duration.ofSeconds(30)));

        assertEquals(new Clicker.Clicked(false, "exited with 1 before showing a window"), clicked);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void aClickThatCannotBeMadeHereIsARowNotAThrow() {
        Clicker.Clicked clicked = clicker(false).click(loysim(), new FakeProcess(PID), ctx(),
                deadlineIn(Duration.ofSeconds(1)));
        assertEquals(new Clicker.Clicked(false, "the Java Access Bridge needs Windows"), clicked);
    }

    // ----- windowed ---------------------------------------------------------------------

    @Test
    void windowedMinimisesEachShownWindowOnceUntilTheDeadline() {
        windows.windows.put(PID, List.of(new WindowOps.TopWindow(1, false), new WindowOps.TopWindow(2, true),
                new WindowOps.TopWindow(3, false)));

        clicker(true).watchAndMinimise(new FakeProcess(PID), deadlineIn(Duration.ofMillis(100)));

        assertEquals(List.of(1L, 3L), windows.minimised, "once each, and never one already minimised");
        assertTrue(windows.lookups.get() > 1, "it watched the whole budget");
    }

    @Test
    void windowedStopsWatchingWhenTheProcessExits() {
        FakeProcess process = new FakeProcess(PID);
        process.alive = false;

        clicker(true).watchAndMinimise(process, deadlineIn(Duration.ofSeconds(30)));

        assertEquals(1, windows.lookups.get());
    }
}
