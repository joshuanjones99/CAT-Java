package com.verifone.cat.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The componentDef graph and the source-commit note: the Python's {@code TestSources}, plus the note's wording. */
class SourceResolverTest {

    @TempDir
    Path tmp;

    private void component(String rel, String... deps) throws IOException {
        Path directory = tmp.resolve(rel);
        Files.createDirectories(directory.resolve("src"));
        StringBuilder body = new StringBuilder();
        for (String dep : deps) {
            body.append("<componentDef name=\"/").append(dep).append("\" version=\"V1\"/>");
        }
        Files.writeString(directory.resolve("componentDef.xml"),
                "<componentDef name=\"/" + rel + "\" version=\"V1\">" + body + "</componentDef>");
    }

    @Test
    void theComponentDefGraphIsFollowedAndCachedPerDirectory() throws IOException {
        component("isdApps/psc/fcc/app", "isdApps/psc/coresvcs/common", "isdApps/shared");
        component("isdApps/psc/coresvcs/common", "isdApps/shared");
        component("isdApps/shared", "isdApps/psc/fcc/app"); // a cycle back to the seed
        SourceResolver resolver = new SourceResolver(tmp);

        List<Path> roots = resolver.rootsFor(List.of("isdApps.psc.fcc.app-V1.jar"), "buypass");

        Path real = tmp.toRealPath();
        Set<String> relative = roots.stream().map(r -> real.relativize(r.getParent()).toString().replace('\\', '/'))
                .collect(Collectors.toSet());
        assertEquals(Set.of("isdApps/psc/fcc/app", "isdApps/psc/coresvcs/common", "isdApps/shared"), relative);
        // The same answer again, from the cache.
        assertEquals(roots, resolver.rootsFor(List.of("isdApps.psc.fcc.app-V1.jar"), "buypass"));
    }

    @Test
    void aDistSeedIsSubstituted() throws IOException {
        Files.createDirectories(tmp.resolve("viper/custom/buypass/app/src"));
        List<Path> roots = new SourceResolver(tmp).rootsFor(List.of("viper.custom.buypass.app-V1.jar"), "buypass");
        assertEquals(List.of(tmp.resolve("viper/custom/buypass/app/src").toRealPath()), roots);
    }

    @Test
    void aPatternWithNoSeedOrAMissingDirectoryHasNoRoots() {
        assertEquals(List.of(), new SourceResolver(tmp).rootsFor(List.of("log4j.jar", "isdApps.psc.fcc.app-V1.jar"),
                "buypass"));
    }

    @Test
    void anUnparseableComponentDefKeepsItsOwnSourceOnly() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("isdApps/psc/fcc/app/src")).getParent();
        Files.writeString(dir.resolve("componentDef.xml"), "<componentDef name=");
        assertEquals(1, new SourceResolver(tmp).rootsFor(List.of("isdApps.psc.fcc.app-V1.jar"), "buypass").size());
    }

    @Test
    void theCommitNoteSaysWhenTheSourcesAreNotTheBuilds() {
        Path repo = Path.of("petroapps");
        assertEquals("", SourceResolver.sourceCommitNote(repo, "ABCDEF0123456789", r -> "abcdef0123456789ffff"));
        String differs = SourceResolver.sourceCommitNote(repo, "1234567890abcdef", r -> "abcdef0123456789ffff");
        assertEquals("sources from petroapps at abcdef012345; the build is 1234567890ab: a highlighted line may sit "
                + "on the wrong source line where the two differ", differs);
        assertTrue(SourceResolver.sourceCommitNote(repo, "", r -> "abcdef").contains("of an unknown commit"));
        assertEquals("sources from petroapps (its commit could not be read)",
                SourceResolver.sourceCommitNote(repo, "abc", r -> ""));
    }

    @Test
    void aFolderThatIsNotAGitCheckoutHasNoHead() {
        assertEquals("", SourceResolver.head(tmp));
    }
}
