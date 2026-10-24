package com.verifone.cat.pats.runner.handlers.configuration;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.handlers.inits.InitsState;
import com.verifone.cat.pats.runner.handlers.inits.TestActions;
import com.verifone.cat.pats.runner.handlers.inits.TestClock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds a configuration family's run for a test, over a {@code @TempDir} petroapps checkout and tools root, the
 * fake register and the fake clock. Nothing here reads the real petroapps checkout or writes to {@code C:\CICD}.
 *
 * <p>Test support only.
 */
final class ConfigFixtures {

    private ConfigFixtures() {
    }

    /** A context over {@code folder}: {@code folder/petroapps} and {@code folder/tools} are created. */
    static ConfigurationContext context(Path folder, TestClock clock, RegisterPos register,
            StuckCloseRecovery recovery, CloudSender cloud) throws Exception {
        return context(folder, clock, register, recovery, cloud, Optional::empty, CaseFiles.NONE);
    }

    /** {@link #context(Path, TestClock, RegisterPos, StuckCloseRecovery, CloudSender)} with FEPSIM v2 and case files. */
    static ConfigurationContext context(Path folder, TestClock clock, RegisterPos register,
            StuckCloseRecovery recovery, CloudSender cloud, Supplier<Optional<BuypassV2>> buypass,
            CaseFiles caseFiles) throws Exception {
        Path petroapps = Files.createDirectories(folder.resolve("petroapps"));
        Path tools = Files.createDirectories(folder.resolve("tools"));
        InitsState inits = new InitsState(() -> folder.resolve("petro.log"), clock.waits(),
                new InitsState.Timing(Duration.ofMillis(200), Duration.ofMillis(50), Duration.ofMillis(600),
                        Duration.ofMillis(300), Duration.ofMillis(1500)));
        return new ConfigurationContext(new PetroappsFiles(petroapps), tools, register, cloud, recovery,
                buypass, caseFiles, inits, clock.waits());
    }

    /** Writes {@code content} to {@code file}, creating its folders: a petroapps or tools file for a test. */
    static Path write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** A run of {@code kase} with the configuration family registered, and {@code fixtures} as its fixtures. */
    static CaseRun caseRun(ConfigurationContext context, FixtureSource fixtures, RunnableCase kase,
            Set<FixtureName> declared) {
        ActionRegistry registry = new ActionRegistry();
        ConfigurationFamily.register(registry, context);
        return TestActions.run(kase, registry, fixtures, declared);
    }

    /** Fixtures that refuse every name: for a case that must not reach a device. */
    static FixtureSource noFixtures() {
        return name -> {
            throw new IllegalStateException("no fixture " + name + " in this test");
        };
    }
}
