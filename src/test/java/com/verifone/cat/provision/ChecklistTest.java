package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;

class ChecklistTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String CONTROLLER = "controller.invalid";

    @Test
    void theDummyRootCertRunsItsThreeCommandsInOrder() {
        InMemoryTransport commander = new InMemoryTransport("commander.invalid");

        StepResult result = new Checklist.DummyRootCertOnCommander().apply(commander, CONTROLLER, TIMEOUT);

        assertEquals(StepStatus.DONE, result.status());
        assertEquals(Checklist.DummyRootCertOnCommander.COMMANDS, commander.ran());
        assertTrue(commander.ran().get(2).startsWith("chown coresvcs:vfi "));
    }

    @Test
    void aFailingDummyRootCertCommandFailsTheStepWithItsStatusAndStderr() {
        InMemoryTransport commander = new InMemoryTransport("commander.invalid").command(
                "mkdir -p /home/common/data/security/trustStoreDir", new CommandResult(1, "", "denied"));

        StepResult result = new Checklist.DummyRootCertOnCommander().apply(commander, CONTROLLER, TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertFalse(result.ok());
        assertTrue(result.detail().contains("exited 1: denied"), result.detail());
        assertEquals(1, commander.ran().size(), "a failed command stops the step");
    }

    @Test
    void theBuypassStubIsSkippedWithItsTargetNotSilentlyDone() {
        StepResult result = new Checklist.BuypassFepConfig().apply(new InMemoryTransport("commander.invalid"),
                CONTROLLER, TIMEOUT);

        assertEquals(StepStatus.SKIPPED, result.status());
        assertTrue(result.ok(), "a skipped step is reported, not a failure");
        assertTrue(result.detail().contains(CONTROLLER + ":" + Checklist.BUYPASS_UMF_PORT), result.detail());
        assertEquals(41107, Checklist.BUYPASS_UMF_PORT);
    }

    @Test
    void theTableDownloadStubIsSkipped() {
        StepResult result = new Checklist.TableDownload().apply(new InMemoryTransport("c"), CONTROLLER, TIMEOUT);

        assertEquals(StepStatus.SKIPPED, result.status());
        assertEquals("table_download", result.step());
    }

    @Test
    void theCommanderChecklistListsTheThreeItemsInOrder() {
        assertEquals(List.of("dummy_root_cert", "buypass_fep_umf", "table_download"),
                Checklist.CHECKLIST_STEPS.stream().map(ChecklistStep::name).toList());
    }

    @Test
    void aClaimedDirectoryIsChownedRecursivelyAndNamedFromItsPath() {
        InMemoryTransport register = new InMemoryTransport("topaz101.invalid");
        Checklist.ClaimPushedDirectory browser = new Checklist.ClaimPushedDirectory("/home/topaz/browser", "topaz:vfi");

        StepResult result = browser.apply(register, CONTROLLER, TIMEOUT);

        assertEquals(StepStatus.DONE, result.status());
        assertEquals("claim_browser", browser.name());
        assertEquals(List.of("chown -R topaz:vfi /home/topaz/browser"), register.ran());
        assertEquals("claim_automation",
                new Checklist.ClaimPushedDirectory("/home/topaz/automation", "topaz:vfi").name());
    }

    @Test
    void aFailingChownFailsTheStepNamingTheCommand() {
        String command = "chown -R topaz:vfi /home/topaz/automation";
        InMemoryTransport register = new InMemoryTransport("topaz101.invalid")
                .command(command, new CommandResult(1, "", "not permitted"));

        StepResult result = new Checklist.ClaimPushedDirectory("/home/topaz/automation", "topaz:vfi")
                .apply(register, CONTROLLER, TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("not permitted") && result.detail().contains(command), result.detail());
    }

    @Test
    void theDefaultRegisterChecklistClaimsTheBrowserDirectoryThenEnablesTheOverrideWithTheTopazRestart() {
        List<ChecklistStep> steps = ProvisionOptions.defaults().registerChecklist();

        assertEquals(List.of("claim_browser", "enable_browser_override"), steps.stream().map(ChecklistStep::name)
                .toList());
        BrowserOverride.EnableBrowserOverride enable = (BrowserOverride.EnableBrowserOverride) steps.get(1);
        assertTrue(enable.required());
        assertEquals(BrowserOverride.TOPAZ_RESTART, enable.restart());
        assertEquals(Integer.valueOf(BrowserOverride.DEFAULT_RELAY_PORT), enable.relayPort());
        assertEquals(BrowserOverride.DEFAULT_CDP_PORT, enable.cdpPort());
    }

    @Test
    void theCommanderStubsAreNotRequiredSoTheirSkipStaysAManualItem() {
        assertFalse(Checklist.CHECKLIST_STEPS.stream().anyMatch(ChecklistStep::required));
    }
}
