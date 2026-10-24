package com.verifone.cat.pats;

import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.pats.runner.RunnableAction;

/**
 * One PATS action: its {@code name}, its {@code <field name= value=>} children in document order, and its position
 * in its case. The one action type: the XML case reader ({@link CaseParser}), the BDD reader ({@link ScenarioParser})
 * and the {@code .feature} reader ({@code pats.feature}) all produce it, and the runner executes it through
 * {@link RunnableAction}. Port of {@code petrotest.pats.case.Action}.
 *
 * <p>Field names are matched without regard to case, and a field's first non-empty value wins; {@link #get} and
 * {@link #raw} (from {@link RunnableAction}) keep the Python's two readings of an empty field: a field present with
 * {@code value=""} reads {@code ""} to {@code raw}, and absent reads {@code null}.
 *
 * @param name   the action's name, as written ({@code VALIDATION_BY_TEXT}, {@code SLEEP})
 * @param fields the fields in order; a field may repeat
 * @param index  the action's position in its case, from zero
 */
public record Action(String name, List<RunnableAction.ActionField> fields, int index) implements RunnableAction {

    public Action {
        fields = List.copyOf(fields);
    }

    /** An action at position 0 with the given name and {@code name, value, name, value, ...} fields. */
    public static Action of(String name, String... nameValuePairs) {
        List<RunnableAction.ActionField> fields = new ArrayList<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            fields.add(new RunnableAction.ActionField(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        return new Action(name, fields, 0);
    }

    /** The same action at another position. */
    public Action withIndex(int position) {
        return new Action(name, fields, position);
    }

    /** The action as the Python prints it: {@code #3 SLEEP(SLEEP='5000')}, without empty fields or the device. */
    @Override
    public String toString() {
        return describe();
    }
}
