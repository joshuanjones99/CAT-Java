package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimContextTest {

    @TempDir
    Path tools;

    private static SimEntry row(String cwd) {
        return SimEntry.exeRow("Row").cwd(cwd).exe("{cwd}/x.exe").build();
    }

    private static void touch(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.createFile(path);
    }

    @Test
    void substitutesEveryPlaceholderTheRegistryUses() {
        SimContext ctx = SimContext.of(tools, "controller-host");
        SimEntry entry = row("BUYPASS");
        String text = ctx.substitute("{tools_root}|{java_home}|{jaxb}|{cwd}|{controller_host}|{username}", entry);
        assertEquals(tools + "|" + tools.resolve("Java17") + "|"
                + tools.resolve("Java17").resolve("jaxb-ri").resolve("mod") + "|"
                + tools.resolve("BUYPASS") + "|controller-host|" + ctx.username(), text);
    }

    @Test
    void unknownPlaceholderIsAnErrorNamingTheRow() {
        SimContext ctx = SimContext.of(tools, "");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ctx.substitute("{tools_rot}/x", row("")));
        assertTrue(e.getMessage().contains("unknown placeholder"), e.getMessage());
        assertTrue(e.getMessage().contains("Row"), e.getMessage());
    }

    @Test
    void dottedPlaceholderIsAnErrorNotPassedThrough() {
        SimContext ctx = SimContext.of(tools, "");
        assertThrows(IllegalArgumentException.class, () -> ctx.substitute("{tools_root.parent}", row("")));
    }

    @Test
    void strayBraceIsAnError() {
        SimContext ctx = SimContext.of(tools, "");
        assertThrows(IllegalArgumentException.class, () -> ctx.substitute("a}b", row("")));
        assertThrows(IllegalArgumentException.class, () -> ctx.substitute("{tools_root", row("")));
    }

    @Test
    void javawPicksTheHighestVersionThatHasJavaw() throws IOException {
        touch(tools.resolve("Java17").resolve("jdk-17.0.5+8").resolve("bin").resolve("javaw.exe"));
        touch(tools.resolve("Java17").resolve("jdk17.0.7_7").resolve("bin").resolve("javaw.exe"));
        Files.createDirectories(tools.resolve("Java17").resolve("jdk-99")); // no javaw: ignored
        Optional<Path> exe = SimContext.javaw(tools, "Java17");
        assertEquals(tools.resolve("Java17").resolve("jdk17.0.7_7").resolve("bin").resolve("javaw.exe"),
                exe.orElseThrow());
    }

    @Test
    void javawIsAbsentWhenNoJdkIsStaged() {
        assertFalse(SimContext.javaw(tools, "Java17").isPresent());
    }

    @Test
    void argvLeadsWithTheResolvedJavawAndAJdkOverrideReplacesIt() throws IOException {
        Path jdk = tools.resolve("Java17").resolve("jdk-17.0.5+8");
        touch(jdk.resolve("bin").resolve("javaw.exe"));
        SimEntry entry = SimEntry.javaw("Row").argv("-jar", "a.jar").build();
        List<String> argv = SimContext.of(tools, "").argv(entry);
        assertTrue(argv.get(0).endsWith("javaw.exe"), argv.get(0));
        assertEquals(List.of("-jar", "a.jar"), argv.subList(1, argv.size()));
        Path override = tools.resolve("override");
        touch(override.resolve("bin").resolve("javaw.exe"));
        List<String> overridden = SimContext.of(tools, "").withJdk(override, null).argv(entry);
        assertEquals(override.resolve("bin").resolve("javaw.exe").toString(), overridden.get(0));
    }

    @Test
    void javawWithNoRuntimeFailsAsAnIllegalState() {
        SimEntry entry = SimEntry.javaw("Row").argv("-jar", "a.jar").build();
        assertThrows(IllegalStateException.class, () -> SimContext.of(tools, "").argv(entry));
    }

    @Test
    void matchTokenIsTheExplicitMatchThenTheTokenAfterJarThenTheLastArgThenTheName() {
        SimContext ctx = SimContext.of(tools, "");
        SimEntry explicit = SimEntry.javaw("A").argv("-jar", "x.jar").match("Explicit.jar").build();
        assertEquals("Explicit.jar", ctx.matchToken(explicit));
        SimEntry jar = SimEntry.javaw("B").argv("-agent", "-jar", "{cwd}/LoySim9.1.jar").cwd("PCATS01").build();
        assertEquals(tools.resolve("PCATS01") + "/LoySim9.1.jar", ctx.matchToken(jar));
        SimEntry last = SimEntry.javaw("C").argv("-classpath", "lib", "com.example.Main").build();
        assertEquals("com.example.Main", ctx.matchToken(last));
        SimEntry none = SimEntry.javaw("D").build();
        assertEquals("D", ctx.matchToken(none));
    }

    @Test
    void bridgeDllIsFoundBesideTheResolvedJavaw() throws IOException {
        Path jdk = tools.resolve("Java17").resolve("jdk-17.0.5+8");
        touch(jdk.resolve("bin").resolve("javaw.exe"));
        SimContext ctx = SimContext.of(tools, "");
        assertFalse(ctx.bridgeDll().isPresent());
        touch(jdk.resolve("bin").resolve("windowsaccessbridge-64.dll"));
        assertTrue(ctx.bridgeDll().isPresent());
    }

    @Test
    void toolsRootResolvesEnvironmentThenRigThenDefault() {
        Path rig = Path.of("D:/rig-tools");
        assertEquals(Path.of("E:/env-tools"), ToolsRoot.resolve("E:/env-tools", rig));
        assertEquals(rig, ToolsRoot.resolve("  ", rig));
        assertEquals(rig, ToolsRoot.resolve(null, rig));
        assertEquals(ToolsRoot.DEFAULT, ToolsRoot.resolve(null, null));
    }
}
