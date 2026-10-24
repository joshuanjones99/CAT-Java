package com.verifone.cat.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding the JaCoCo tools and building their argv. No real jacococli runs and nothing under C:\CICD is read. */
class JacocoToolsTest {

    @TempDir
    Path tmp;

    /** Writes a jar with the given {@code Implementation-Version}, or with no manifest when null. */
    private static Path jar(Path file, String version) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream raw = Files.newOutputStream(file)) {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            if (version != null) {
                manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
            }
            try (JarOutputStream jar = new JarOutputStream(raw, manifest)) {
                jar.flush();
            }
        }
        return file;
    }

    private Jacoco.Locations locations(Path java) {
        return new Jacoco.Locations(tmp.resolve("tools"), tmp.resolve("no-pats"), java);
    }

    @Test
    void aCliOlderThanJava17SupportIsRefusedByName() throws IOException {
        jar(tmp.resolve("tools/jacoco/jacococli.jar"), "0.8.4");

        JacocoUnavailable refused = assertThrows(JacocoUnavailable.class,
                () -> Jacoco.findTools(locations(Path.of("java.exe")), false));

        assertTrue(refused.getMessage().matches("(?s).*too old.*0\\.8\\.4.*"), refused.getMessage());
    }

    @Test
    void theCliAndTheJavaAreFoundAndTheAgentIsOptional() throws IOException {
        Path cli = jar(tmp.resolve("tools/jacoco/jacococli.jar"), "0.8.8");
        Path java = tmp.resolve("tools/Java17/jdk-17.0.5+8/bin/java.exe");
        Files.createDirectories(java.getParent());
        Files.write(java, new byte[0]);

        JacocoTools tools = Jacoco.findTools(locations(null), false);

        assertEquals(cli, tools.cli());
        assertEquals(java, tools.java());
        assertNull(tools.agent());
    }

    @Test
    void theAgentIsRequiredWhenAskedFor() throws IOException {
        jar(tmp.resolve("tools/jacoco/jacococli.jar"), "0.8.8");

        JacocoUnavailable missing = assertThrows(JacocoUnavailable.class,
                () -> Jacoco.findTools(locations(Path.of("java.exe")), true));

        assertTrue(missing.getMessage().startsWith("no jacocoagent.jar at"), missing.getMessage());
    }

    @Test
    void aMissingCliNamesWhereToPutIt() {
        JacocoUnavailable missing = assertThrows(JacocoUnavailable.class,
                () -> Jacoco.findTools(locations(Path.of("java.exe")), false));

        assertTrue(missing.getMessage().contains("0.8.7 or later"), missing.getMessage());
        assertTrue(missing.getMessage().contains(tmp.resolve("tools/jacoco").toString()), missing.getMessage());
    }

    @Test
    void noJavaAnywhereIsAnError() throws IOException {
        jar(tmp.resolve("tools/jacoco/jacococli.jar"), "0.8.8");

        JacocoUnavailable noJava = assertThrows(JacocoUnavailable.class,
                () -> Jacoco.findTools(locations(null), false));

        assertTrue(noJava.getMessage().startsWith("no Java to run jacococli"), noJava.getMessage());
    }

    @Test
    void aJarWithNoReadableVersionIsNotRefused() throws IOException {
        Path unversioned = jar(tmp.resolve("unversioned.jar"), null);
        Path garbage = tmp.resolve("garbage.jar");
        Files.writeString(garbage, "not a zip");

        assertEquals(0, Jacoco.jarVersion(unversioned).length);
        assertEquals(0, Jacoco.jarVersion(garbage).length);
        assertEquals(0, Jacoco.jarVersion(tmp.resolve("absent.jar")).length);
    }

    @Test
    void versionsCompareAsNumbersNotAsText() {
        assertTrue(Jacoco.compareVersions(new int[] {0, 8, 4}, Jacoco.MIN_CLI_VERSION) < 0);
        assertTrue(Jacoco.compareVersions(new int[] {0, 8, 8}, Jacoco.MIN_CLI_VERSION) > 0);
        assertTrue(Jacoco.compareVersions(new int[] {0, 10, 0}, Jacoco.MIN_CLI_VERSION) > 0);
        assertEquals(0, Jacoco.compareVersions(new int[] {0, 8, 7}, Jacoco.MIN_CLI_VERSION));
    }

    @Test
    void aFailureSaysWhatFailedNotWhereInTheStack() {
        String output = String.join("\n",
                "[INFO] Loading execution data file x\\mobile.exec.",
                "Exception in thread \"main\" java.io.IOException: Error while analyzing x.jar@A.class",
                "\tat org.jacoco.cli.internal.core.analysis.Analyzer.analyzerError(Analyzer.java:163)",
                "Caused by: java.lang.IllegalStateException: Can't add different class with same name: A",
                "\tat org.jacoco.cli.internal.asm.ClassReader.accept(ClassReader.java:748)",
                "\t... 9 more");

        String tail = Jacoco.tail(output);

        assertTrue(tail.contains("Can't add different class with same name"), tail);
        assertFalse(tail.contains("at org.jacoco"), tail);
        assertFalse(tail.contains("[INFO]"), tail);
    }

    @Test
    void theTailKeepsTheLastFourMeaningfulLines() {
        String output = "one\ntwo\nthree\nfour\nfive";

        assertEquals("two | three | four | five", Jacoco.tail(output));
    }

    @Test
    void theArgfileQuotesEveryArgument() {
        assertEquals("\"C:\\\\Program Files\\\\a \\\"b\\\"\"", Jacoco.quote("C:\\Program Files\\a \"b\""));
    }

    @Test
    void dumpArgsNameTheHostPortAndFile() {
        List<String> args = Jacoco.dumpArgs("192.168.1.10", 6300, Path.of("out", "fcc.exec"), true);

        assertEquals(List.of("dump", "--address", "192.168.1.10", "--port", "6300", "--destfile",
                Path.of("out", "fcc.exec").toString(), "--reset", "--quiet"), args);
    }

    @Test
    void reportArgsCarryEveryClassAndSourceRoot() {
        List<String> args = Jacoco.reportArgs(List.of(Path.of("a.exec")),
                List.of(Path.of("one.jar"), Path.of("two.war")), "fcc", Path.of("html"), Path.of("fcc.xml"),
                List.of(Path.of("src1"), Path.of("src2")));

        assertEquals(2, args.stream().filter("--classfiles"::equals).count());
        assertEquals(2, args.stream().filter("--sourcefiles"::equals).count());
        assertEquals(List.of("report", "a.exec"), args.subList(0, 2));
        assertTrue(args.contains("--html"));
    }

    @Test
    void reportArgsWithoutHtmlWriteXmlOnly() {
        List<String> args = Jacoco.reportArgs(List.of(Path.of("a.exec")), List.of(Path.of("one.jar")), "fcc", null,
                Path.of("fcc.xml"), List.of());

        assertFalse(args.contains("--html"));
        assertTrue(args.contains("--xml"));
    }

    @Test
    void theCandidatesPreferTheToolsRootOverPats() throws IOException {
        Path inTools = jar(tmp.resolve("tools/jacoco/jacococli.jar"), "0.8.8");
        jar(tmp.resolve("pats/Bin/Lib/jacococli.jar"), "0.8.8");

        JacocoTools tools = Jacoco.findTools(new Jacoco.Locations(tmp.resolve("tools"), tmp.resolve("pats"),
                Path.of("java.exe")), false);

        assertEquals(inTools, tools.cli());
    }

    @Test
    void aPatsCliIsUsedWhenTheToolsRootHasNone() throws IOException {
        Path inPats = jar(tmp.resolve("pats/Bin/Lib/jacococli.jar"), "0.8.8");

        JacocoTools tools = Jacoco.findTools(new Jacoco.Locations(tmp.resolve("tools"), tmp.resolve("pats"),
                Path.of("java.exe")), false);

        assertEquals(inPats, tools.cli());
    }

    @Test
    void theEnvironmentNamesTheToolsRootAndPats() {
        Jacoco.Locations where = Jacoco.Locations.fromEnvironment(Map.of("CAT_TOOLS_ROOT", "D:/Tools",
                "CAT_PATS_RUN", "D:/Pats"));

        assertEquals(Path.of("D:/Tools"), where.toolsRoot());
        assertEquals(Path.of("D:/Pats"), where.patsRun());
    }
}
