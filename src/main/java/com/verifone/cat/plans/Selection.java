package com.verifone.cat.plans;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * What a partial run keeps, in plan order, and what it left out ahead of the last kept case.
 *
 * @param pairs the kept plan cases, in plan order
 * @param skippedBefore plan cases before the last kept one that the selection left out
 * @param order every runnable case of the plan, in plan order: what "before" and "after" are
 *     measured by. Empty when a selection is built by hand, which reads as one whose kept cases
 *     all come after.
 */
public record Selection(List<CaseRef> pairs, List<CaseRef> skippedBefore, List<CaseRef> order) {

    public Selection {
        pairs = List.copyOf(pairs);
        skippedBefore = List.copyOf(skippedBefore);
        order = List.copyOf(order);
    }

    public Selection(List<CaseRef> pairs, List<CaseRef> skippedBefore) {
        this(pairs, skippedBefore, List.of());
    }

    /** The pytest targets, each once, in plan order. */
    public List<String> nodes() {
        return new ArrayList<>(new LinkedHashSet<>(pairs.stream().map(CaseRef::node).toList()));
    }

    public List<String> keys() {
        return pairs.stream().map(CaseRef::key).toList();
    }
}
