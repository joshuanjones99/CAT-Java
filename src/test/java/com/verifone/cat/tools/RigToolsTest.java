package com.verifone.cat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.launch.ToolStages;
import com.verifone.cat.rig.RigLoader;

/**
 * {@link RigTools.Standard}'s refusals, which come before any rig is loaded or device reached. The stages themselves are
 * step 4's and tested there. Evidence about this repository only.
 */
class RigToolsTest {

    @TempDir
    Path rigs;

    private RigTools tools() {
        Map<String, String> env = Map.of(RigLoader.RIGS_DIR_ENV, rigs.toString());
        return new RigTools.Standard(env, ToolStages.real(env), new RigReboot.Seams(spec -> {
            throw new AssertionError("no device may be reached");
        }, (spec, at) -> false, host -> false));
    }

    @Test
    void onboardingWithAWrongValueSendsNothing() {
        Say.Recorded say = new Say.Recorded();

        assertFalse(tools().onboard("lab", new CommanderConnect("Prod", "nobody", "12", ""), say));

        String error = say.errLines().get(say.errLines().size() - 1);
        assertTrue(error.contains("nothing was sent"), error);
        assertTrue(error.contains("service id must be 7 to 20 digits"), error);
    }

    @Test
    void aRigThatDoesNotLoadIsAFailedToolNotACrash() {
        Say.Recorded say = new Say.Recorded();

        assertFalse(tools().rebootCommander("missing-rig", say));
        assertFalse(tools().pair("missing-rig", say));

        assertTrue(say.errLines().stream().allMatch(l -> l.startsWith("cannot load rig missing-rig")),
                say.errLines().toString());
        assertEquals(List.of(), tools().registers("missing-rig"));
    }
}
