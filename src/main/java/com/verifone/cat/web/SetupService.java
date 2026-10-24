package com.verifone.cat.web;

import java.util.List;
import java.util.Map;

import com.verifone.cat.preflight.Result;
import com.verifone.cat.setup.SetupAudit;
import com.verifone.cat.setup.SetupEnv;
import com.verifone.cat.setup.SetupItem;

/**
 * The Setup page's audit, held for the life of the web app. Nothing is probed until the operator
 * presses Check again: opening the page only shows what the last check found. That keeps a page
 * view (and every test that renders it) from reading the workstation.
 *
 * <p>Calls are serialised, so two presses cannot interleave two audits.
 */
final class SetupService {

    /** A consistent copy of the audit for one render: the items in order, their results, and whether a check has run. */
    record Snapshot(List<SetupItem> items, Map<String, Result> results, boolean checked) {}

    private final SetupAudit audit;
    private boolean checked;

    SetupService(SetupAudit audit) {
        this.audit = audit;
    }

    /** The service over the real workstation. Constructing it reads nothing. */
    static SetupService standard() {
        return new SetupService(SetupAudit.standard(SetupEnv.system()));
    }

    /** Detects every item now and keeps the results. */
    synchronized void check() {
        audit.run();
        checked = true;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(audit.items(), audit.results(), checked);
    }
}
