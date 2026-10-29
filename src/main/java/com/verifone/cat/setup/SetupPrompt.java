package com.verifone.cat.setup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.verifone.cat.preflight.Outcome;
import com.verifone.cat.preflight.Result;

/**
 * {@code cat-java setup}'s reporter: the Python CAT's {@code cat.setup.prompt.SetupPrompt}. It runs the audit's
 * repair pass ({@link SetupAudit#runAndRepair()}, no confirmation) and prints what was fixed and what is left for a
 * person. There is no interactive menu: credentials are entered on the Setup page, not here.
 *
 * <p>Deviation: the Python's marks ({@code ✓}, {@code ⚠} and a key emoji) are plain words here, because the
 * Windows console's code page garbles them.
 */
public final class SetupPrompt {

    private final SetupAudit audit;
    private final Consumer<String> print;

    public SetupPrompt(SetupAudit audit, Consumer<String> print) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.print = Objects.requireNonNull(print, "print");
    }

    /** Fixes what can be fixed; reports what still needs a person. True when every required item passes. */
    public boolean run() {
        Map<String, Result> applied = audit.runAndRepair();
        applied.forEach((name, result) -> {
            if (result.wasRepair() && result.outcome() == Outcome.PASS) {
                print.accept("fixed  " + name + ": " + result.detail());
            }
        });

        Map<String, Result> remaining = audit.stillNeedsAttention();
        if (remaining.isEmpty()) {
            return true;
        }
        Map<String, Result> credentials = audit.missingCredentials();
        Map<String, Result> others = new LinkedHashMap<>();
        remaining.forEach((name, result) -> {
            if (!credentials.containsKey(name)) {
                others.put(name, result);
            }
        });
        if (!others.isEmpty()) {
            print.accept("");
            print.accept("! " + others.size() + " item(s) need your attention:");
            others.forEach((name, result) -> print.accept("  - " + name + ": " + result.detail()));
        }
        if (!credentials.isEmpty()) {
            print.accept("");
            print.accept("Sign in before your first run: start CAT-Java (run.cmd) and open the Setup page.");
            credentials.forEach((name, result) -> print.accept("  - " + name + ": " + result.detail()));
        }
        return audit.missingRequired().isEmpty();
    }
}
