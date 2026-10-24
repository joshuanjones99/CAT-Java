package com.verifone.cat.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.sims.ToolsRoot;

/**
 * A workstation that exists only under a {@code @TempDir}. Every root the checks read is set in the
 * variable map to a folder beneath it, and the home and repository are temporary too, so no check
 * here can reach {@code C:\CICD}, the real home directory or a real credential. The command runner
 * is fake: it records what it was asked and answers with {@link #answer}.
 */
final class SetupFixture {

    final Path root;
    final Path tools;
    final Path home;
    final Path repo;
    final Path petroapps;
    final Path results;
    final Path rigs;
    final Path localAppData;
    final Map<String, String> vars;
    final List<List<String>> commands = new ArrayList<>();
    SetupEnv.CommandResult answer = new SetupEnv.CommandResult(1, "", "not configured");
    final SetupEnv env;

    SetupFixture(Path root) throws IOException {
        this.root = root;
        this.tools = root.resolve("tools");
        this.home = root.resolve("home");
        this.repo = root.resolve("repo");
        this.petroapps = root.resolve("petroapps");
        this.results = root.resolve("results");
        this.rigs = root.resolve("rigs");
        this.localAppData = root.resolve("local");
        for (Path dir : List.of(tools, home, repo, petroapps, rigs, localAppData)) {
            Files.createDirectories(dir);
        }
        this.vars = Map.of(
                ToolsRoot.ENV, tools.toString(),
                "PETROAPPS_ROOT", petroapps.toString(),
                ReportPaths.RESULTS_DIR_ENV, results.toString(),
                RigLoader.RIGS_DIR_ENV, rigs.toString(),
                "LOCALAPPDATA", localAppData.toString());
        this.env = new SetupEnv(vars, home, repo, (argv, timeout) -> {
            commands.add(List.copyOf(argv));
            return answer;
        });
    }

    /** An environment over {@code overrides} layered on the fixture's roots (a null value removes the key). */
    SetupEnv withVars(Map<String, String> overrides) {
        Map<String, String> merged = new HashMap<>(vars);
        overrides.forEach((key, value) -> {
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        });
        return new SetupEnv(merged, home, repo, (argv, timeout) -> {
            commands.add(List.copyOf(argv));
            return answer;
        });
    }

    /** Creates a file, and its folders, with the given text. */
    Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    /** Creates a folder, and its parents. */
    Path dir(Path folder) throws IOException {
        return Files.createDirectories(folder);
    }
}
