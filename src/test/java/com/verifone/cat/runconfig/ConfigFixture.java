package com.verifone.cat.runconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.plans.PlanSource;
import com.verifone.cat.plans.TestPlan;

/**
 * A small offline plan world for the Config tab's tests: two plans, four cases, one of them a
 * {@code .feature} with a tag, one with no migrated file, and the files on disk they name.
 *
 * <p>Test evidence only. The seed here is a fake, so nothing reads the shipped snapshot.
 */
public final class ConfigFixture {

    public static final String PLAN = "PETROCORE-135127";
    public static final String OLD_PLAN = "PETROCORE-99001";

    /** Imports configuration: a PATS action that changes rig state. */
    public static final String IMPORTER = "PETROCORE-1";
    /** A plain case after the importer. */
    public static final String PLAIN = "PETROCORE-2";
    /** A {@code .feature} tagged {@code @fuel}; its actions are not readable. */
    public static final String FUEL = "PETROCORE-3";
    /** Not migrated: no file. */
    public static final String NOT_RUN = "PETROCORE-4";

    public static final String IMPORTER_NODE = "tests/suites/charity/PETROCORE-1-TestCase.xml";
    public static final String PLAIN_NODE = "tests/suites/charity/PETROCORE-2-TestCase.xml";
    public static final String FUEL_NODE = "tests/suites/charity/PETROCORE-3-TestCase.feature";

    private ConfigFixture() {}

    /** The checkout the case files live in, under {@code tmp}. */
    public static Path repoRoot(Path tmp) throws IOException {
        Path root = tmp.resolve("repo");
        Path dir = root.resolve("tests/suites/charity");
        Files.createDirectories(dir);
        write(root.resolve(IMPORTER_NODE), """
                <testscript>
                  <testcase name="import">
                    <action name="IMPORT_CONFIGURATION"/>
                    <action name="LOGIN"/>
                  </testcase>
                </testscript>
                """);
        write(root.resolve(PLAIN_NODE), """
                <testscript>
                  <testcase name="plain">
                    <action name="SALE"/>
                  </testcase>
                </testscript>
                """);
        write(root.resolve(FUEL_NODE), """
                @fuel
                Feature: Fuel
                  Scenario: Pump one
                    Given a fuel sale
                """);
        return root;
    }

    /** A catalogue whose cache is empty and whose seed is this fixture's plans. */
    public static PlanCatalogue catalogue(Path tmp, Path root) {
        return new PlanCatalogue(tmp.resolve("cache"), PlanSource.unavailable("no network in tests"),
                seed(), root);
    }

    /** A form on {@code tmp}: its saved file is {@code tmp/cfg/last-config.json}. */
    public static ConfigForm form(Path tmp) throws IOException {
        Path root = repoRoot(tmp);
        ConfigForm form = new ConfigForm(catalogue(tmp, root), tmp.resolve("cfg").resolve("last-config.json"), root);
        form.load();
        return form;
    }

    /** The fake seed: two plans, the first with cases and a requirement row. */
    public static PlanSource seed() {
        return new PlanSource() {
            @Override
            public List<TestPlan> catalogue() {
                return List.of(
                        new TestPlan(PLAN, "Charity Donations", "Automated", "Active", "4", "", ""),
                        new TestPlan(OLD_PLAN, "<b>Old</b> plan", "Deprecated", "Deprecated", "1", "", ""));
            }

            @Override
            public List<PlanCase> cases(String planKey) {
                if (!PLAN.equals(planKey)) {
                    return List.of();
                }
                return List.of(
                        new PlanCase(IMPORTER, "Import the configuration", IMPORTER_NODE),
                        new PlanCase(PLAIN, "Plain sale", PLAIN_NODE),
                        new PlanCase(FUEL, "Fuel sale", FUEL_NODE),
                        new PlanCase(NOT_RUN, "Not migrated yet", null));
            }

            @Override
            public List<PlanRequirements> requirements() {
                return List.of(new PlanRequirements(PLAN, "", true, List.of("Fuel Sim"), List.of()));
            }
        };
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
