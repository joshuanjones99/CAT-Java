package com.verifone.cat.pats.runner.handlers.configuration;

import com.verifone.cat.pats.runner.handlers.inits.InitsState;
import com.verifone.cat.pats.runner.handlers.inits.Waits;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the configuration family needs from its run. Each part is a seam where a family outside this slice is
 * bound, so the handlers are tested with in-memory fakes and wired by the orchestrator.
 *
 * @param petroapps     the petroapps checkout's files, read live
 * @param toolsRoot     the tools root, where the simulators' property files and HTTPSIM's folders live
 * @param register      the register's keys and reads ({@link RegisterPos#UNBOUND} until the POS family is bound)
 * @param cloud         the C-Site request ({@link CloudSender#UNBOUND} until it is ported)
 * @param closeRecovery the stuck-close recovery ({@link StuckCloseRecovery#UNBOUND} until it is bound)
 * @param buypassV2     FEPSIM v2 serving Buypass this run, if it is; empty otherwise
 * @param caseFiles     the case folders' payloads ({@link CaseFiles#NONE} when none are bound)
 * @param inits         the run's init state, shared with the inits family
 * @param waits         the clock and pauser the report actions wait with
 */
public record ConfigurationContext(
        PetroappsFiles petroapps,
        Path toolsRoot,
        RegisterPos register,
        CloudSender cloud,
        StuckCloseRecovery closeRecovery,
        Supplier<Optional<BuypassV2>> buypassV2,
        CaseFiles caseFiles,
        InitsState inits,
        Waits waits) {

    public ConfigurationContext {
        Objects.requireNonNull(petroapps, "petroapps");
        Objects.requireNonNull(toolsRoot, "toolsRoot");
        Objects.requireNonNull(register, "register");
        Objects.requireNonNull(cloud, "cloud");
        Objects.requireNonNull(closeRecovery, "closeRecovery");
        Objects.requireNonNull(buypassV2, "buypassV2");
        Objects.requireNonNull(caseFiles, "caseFiles");
        Objects.requireNonNull(inits, "inits");
        Objects.requireNonNull(waits, "waits");
    }
}
