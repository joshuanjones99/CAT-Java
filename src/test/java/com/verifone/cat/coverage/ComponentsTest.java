package com.verifone.cat.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The component table: which JVM an exec file belongs to, and where its class files are named. */
class ComponentsTest {

    @ParameterizedTest
    @CsvSource({
            "fcc.exec, fcc",
            "coresvcs.exec, coresvcs",
            "pos-tomcat-topaz101.exec, pos-tomcat",
            "pos-tomcat.exec, pos-tomcat",
            "commander-tomcat.exec, commander-tomcat",
    })
    void anExecFileNamesItsJvm(String file, String component) {
        assertEquals(component, Components.componentFor(file).orElseThrow().name());
    }

    @ParameterizedTest
    @CsvSource({"mystery.exec", "pos-tomcatX.exec", "fcc.jar"})
    void anExecFileNothingNamesIsNotReported(String file) {
        assertEquals(Optional.empty(), Components.componentFor(file));
    }

    @Test
    void everyFileOutputJvmInThePetroappsStartScriptsIsInTheTable() {
        for (String vm : List.of("coresvcs", "fcc", "stc", "mobile", "kiosk", "cdm", "vhq", "pmc")) {
            Component component = Components.componentFor(vm + ".exec").orElseThrow();
            assertFalse(component.tcp(), vm + " writes its exec file on exit, not over TCP");
        }
    }

    @Test
    void theTomcatsAreDumpedOverTcp() {
        assertTrue(Components.componentFor("commander-tomcat.exec").orElseThrow().tcp());
        assertTrue(Components.componentFor("pos-tomcat-topaz101.exec").orElseThrow().tcp());
    }

    @Test
    void theDistributionSubstitutesIntoViperJars() {
        Component pmc = Components.componentFor("pmc.exec").orElseThrow();

        assertTrue(pmc.patterns("buypass").contains("viper.custom.buypass.app-V1.jar"));
        assertFalse(pmc.patterns("buypass").stream().anyMatch(p -> p.contains("{dist}")));
    }

    @Test
    void mobileAndPmcEachNameTheirOwnViperCoreAndAreNotReadAcrossPackages() {
        assertEquals(List.of("mobile"), Components.componentFor("mobile.exec").orElseThrow().sources());
        assertEquals(List.of("pmc"), Components.componentFor("pmc.exec").orElseThrow().sources());
        assertEquals(List.of("common", "coresvcs"),
                Components.componentFor("commander-tomcat.exec").orElseThrow().sources());
    }

    @Test
    void aRegisterTomcatsExecFileIsNamedForItsRegister() {
        assertEquals("pos-tomcat-topaz101.exec", Components.posExecName("topaz101"));
    }

    @Test
    void theAgentPathsAreTheProductsOwn() {
        assertEquals("/home/common/lib/jacocoagent.jar", Components.AGENT_JAR);
        assertEquals("/media/v950sc-drive/v950sc/logs", Components.COMMANDER_EXEC_DIR);
        assertEquals(6300, Components.TOMCAT_PORT);
    }

    @Test
    void theFnmatchPatternsMatchAsPythonsDo() {
        assertTrue(Component.fnmatch("isdApps.psc.fcc.app-V1.jar", "isdApps.psc.fcc.app-V1.jar"));
        assertTrue(Component.fnmatch("cgi-bin.war", "*.war"));
        assertFalse(Component.fnmatch("cgi-bin.war.bak", "*.war"));
        assertTrue(Component.fnmatch("a?c", "a?c"));
    }
}
