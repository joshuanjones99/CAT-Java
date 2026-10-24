package com.verifone.cat.pos;

/**
 * The element this accessor needs has not been identified on a real build.
 *
 * <p>Raised instead of guessing (DESIGN.md section 5.1 names a control that no element ID in the
 * corpus matches; that is a Phase 0 rig-inspection item). Port of
 * {@code petrotest.pos.locator_types.UnresolvedLocatorError}.
 */
public class UnresolvedLocatorError extends LocatorError {

    private static final long serialVersionUID = 1L;

    public UnresolvedLocatorError(String message) {
        super(message);
    }
}
