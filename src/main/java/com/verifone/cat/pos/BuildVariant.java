package com.verifone.cat.pos;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * A locator whose spelling depends on the POS build (DESIGN.md section 5.2). Port of
 * {@code petrotest.pos.locator_types.BuildVariant}.
 *
 * <p>Resolved against the rig's {@code build.expected_version}. {@code defaultLocator} is the
 * documented, current spelling; {@code legacy} applies to builds below {@code since}. Java's name
 * for the Python field {@code default}, which is a keyword here.
 */
public record BuildVariant(Locator defaultLocator, Locator legacy, String since, String name) {

    /**
     * The spelling for {@code buildVersion}. An unknown (null) version takes the documented
     * spelling, so a rig file with no build block still runs. An unparseable version throws.
     */
    public Locator resolve(String buildVersion) {
        if (buildVersion == null) {
            return defaultLocator;
        }
        List<BigInteger> current = LocatorTypes.parseVersion(buildVersion);
        List<BigInteger> floor = LocatorTypes.parseVersion(since);
        return LocatorTypes.isBefore(current, floor) ? legacy : defaultLocator;
    }

    /** Template both spellings at once; resolve afterwards. */
    public BuildVariant at(Object... indices) {
        return atNamed(Map.of(), indices);
    }

    public BuildVariant atNamed(Map<String, ?> named, Object... indices) {
        return new BuildVariant(
                defaultLocator.atNamed(named, indices),
                legacy.atNamed(named, indices),
                since,
                name);
    }
}
