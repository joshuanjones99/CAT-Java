package com.verifone.cat.commander;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.links.PaymentLinksCommand;
import com.verifone.cat.commander.registerapp.RegisterAppCommand;
import com.verifone.cat.rig.RigConfigException;

/**
 * Step 4's commands as command lines (the Python's {@code cli.py} parsers): their flags reach the body as
 * {@code argparse} gives them, usage errors and a rig that does not load exit 2, a body's verdict is the exit code. The
 * bodies are recorders; nothing reaches a rig.
 */
class StepFourCommandsTest {

    @TempDir
    java.nio.file.Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> seen = new ArrayList<>();

    private PrintStream out() {
        return new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    private PrintStream err() {
        return new PrintStream(err, true, StandardCharsets.UTF_8);
    }

    private static final RigCommand.Loader NO_RIG = (rig, env) -> null;

    @Test
    void theParserTakesBothFlagFormsSwitchesRepeatsAndAnAction() {
        RigCommand.Spec spec = new RigCommand.Spec("x", "usage", Map.of("--dir", "dflt"), Set.of(),
                Set.of("--force"), Set.of("--fep"), List.of("capture", "diff"));
        RigCommand.Args args = RigCommand.parse(spec, new String[] {"diff", "--rig=lab", "--fep", "a", "--fep=b",
            "--force"});
        assertEquals("lab", args.rig());
        assertEquals("diff", args.action());
        assertEquals("dflt", args.value("--dir"));
        assertEquals(List.of("a", "b"), args.all("--fep"));
        assertTrue(args.has("--force"));
        assertThrows(RigCommand.Usage.class, () -> RigCommand.parse(spec, new String[] {"diff"}));
        assertThrows(RigCommand.Usage.class, () -> RigCommand.parse(spec, new String[] {"--rig", "lab"}));
        assertThrows(RigCommand.Usage.class, () -> RigCommand.parse(spec, new String[] {"push", "--rig", "lab"}));
        assertThrows(RigCommand.Usage.class, () -> RigCommand.parse(spec, new String[] {"diff", "--rig"}));
        assertThrows(RigCommand.Usage.class, () -> RigCommand.parse(spec, new String[] {"diff", "--rig", "l", "-x"}));
    }

    @Test
    void commanderInitPassesItsSwitchesAndItsVerdict() {
        CommanderInitCommand command = new CommanderInitCommand(NO_RIG, (rig, args, env, say) -> {
            seen.add("forecourt=" + !args.has("--no-forecourt") + " snapshot=" + !args.has("--no-snapshot"));
            return RigCommand.exit(false);
        });
        assertEquals(RigCommand.EXIT_FAILED, command.run(new String[] {"--rig", "lab", "--no-forecourt"}, Map.of(),
                out(), err()));
        assertEquals(RigCommand.EXIT_FAILED, command.run(new String[] {"--rig", "lab", "--no-snapshot"}, Map.of(),
                out(), err()));
        assertEquals(List.of("forecourt=false snapshot=true", "forecourt=true snapshot=false"), seen);
        assertEquals(RigCommand.EXIT_USAGE, command.run(new String[] {"--no-forecourt"}, Map.of(), out(), err()));
    }

    @Test
    void aRigThatDoesNotLoadExitsTwo() {
        CommanderPrepCommand command = new CommanderPrepCommand((rig, env) -> {
            throw new RigConfigException("rig 'nope' not found");
        }, (rig, args, env, say) -> RigCommand.EXIT_OK);
        assertEquals(RigCommand.EXIT_USAGE, command.run(new String[] {"--rig", "nope"}, Map.of(), out(), err()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("rig 'nope' not found"));
    }

    @Test
    void paymentReadyTakesSecondsAndZeroIsTheDefault() {
        assertEquals(PaymentReady.READY_TIMEOUT, PaymentReadyCommand.timeout(""));
        assertEquals(PaymentReady.READY_TIMEOUT, PaymentReadyCommand.timeout("0"));
        assertEquals(Duration.ofMillis(1500), PaymentReadyCommand.timeout("1.5"));
        List<Duration> waited = new ArrayList<>();
        PaymentReadyCommand command = new PaymentReadyCommand((rig, env) -> CommanderFixtures.rig(dir), (access, timeout, say) -> {
            waited.add(timeout);
            return true;
        });
        assertEquals(RigCommand.EXIT_OK, command.run(new String[] {"--rig", "lab", "--timeout", "60"}, Map.of(),
                out(), err()));
        assertEquals(List.of(Duration.ofSeconds(60)), waited);
        assertEquals(RigCommand.EXIT_USAGE, command.run(new String[] {"--rig", "lab", "--timeout", "soon"}, Map.of(),
                out(), err()));
    }

    @Test
    void commanderTrustNeedsItsEndpointAndAlias() {
        CommanderTrustCommand command = new CommanderTrustCommand(NO_RIG, (rig, args, env, say) -> {
            seen.add(args.value("--endpoint") + " " + args.value("--alias") + " restart=" + !args.has("--no-restart"));
            return RigCommand.EXIT_OK;
        });
        assertEquals(RigCommand.EXIT_USAGE, command.run(new String[] {"--rig", "lab", "--alias", "a"}, Map.of(),
                out(), err()));
        assertEquals(RigCommand.EXIT_OK, command.run(new String[] {"--rig", "lab", "--endpoint",
            "controller.invalid:8443", "--alias", "cat-cullinan", "--no-restart"}, Map.of(), out(), err()));
        assertEquals(List.of("controller.invalid:8443 cat-cullinan restart=false"), seen);
    }

    @Test
    void secondaryFepNeedsAtLeastOneFepAndRepeatsIt() {
        SecondaryFepCli command = new SecondaryFepCli(NO_RIG, (rig, args, env, say) -> {
            seen.add(args.all("--fep") + " " + args.value("--service-id"));
            return RigCommand.EXIT_OK;
        });
        assertEquals(RigCommand.EXIT_USAGE, command.run(new String[] {"--rig", "lab"}, Map.of(), out(), err()));
        assertEquals(RigCommand.EXIT_OK, command.run(new String[] {"--rig", "lab", "--fep", "WEX-OTR", "--fep",
            "Comdata", "--service-id", "1181078"}, Map.of(), out(), err()));
        assertEquals(List.of("[WEX-OTR, Comdata] 1181078"), seen);
    }

    @Test
    void paymentLinksAndRegisterAppTakeTheRig() {
        PaymentLinksCommand links = new PaymentLinksCommand(NO_RIG, (rig, args, env, say) -> {
            seen.add("links " + args.value("--petroapps"));
            return RigCommand.EXIT_OK;
        });
        RegisterAppCommand app = new RegisterAppCommand(NO_RIG, (rig, args, env, say) -> {
            seen.add("register-app " + args.rig());
            return RigCommand.EXIT_FAILED;
        });
        assertEquals(RigCommand.EXIT_OK, links.run(new String[] {"--rig", "lab", "--petroapps", "P"}, Map.of(),
                out(), err()));
        assertEquals(RigCommand.EXIT_FAILED, app.run(new String[] {"--rig", "lab"}, Map.of(), out(), err()));
        assertEquals(List.of("links P", "register-app lab"), seen);
        assertEquals(java.nio.file.Path.of("P2"), PaymentLinksCommand.petroapps("", Map.of("PETROAPPS_ROOT", "P2")));
    }

    @Test
    void helpPrintsUsageAndExitsZero() {
        assertEquals(RigCommand.EXIT_OK, new CommanderPrepCommand(NO_RIG, (rig, args, env, say) -> 1)
                .run(new String[] {"--help"}, Map.of(), out(), err()));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("usage: cat-java commander-prep"));
    }
}
