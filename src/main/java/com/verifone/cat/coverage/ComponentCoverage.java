package com.verifone.cat.coverage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One JVM's numbers in a coverage report: the Python's {@code petrotest.coverage.summary.ComponentCoverage}.
 *
 * @param name      the JVM's report name (a register's Tomcat is {@code pos-tomcat-<register>})
 * @param counters  counter type ({@code LINE}, {@code BRANCH}, ...) to {@code [covered, missed]}
 * @param execFiles the exec files it was reported from
 */
public record ComponentCoverage(String name, Map<String, long[]> counters, List<String> execFiles) {

    public ComponentCoverage {
        counters = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(counters == null ? Map.of() : counters));
        execFiles = List.copyOf(execFiles == null ? List.of() : execFiles);
    }

    public ComponentCoverage(String name, Map<String, long[]> counters) {
        this(name, counters, List.of());
    }

    /** {@code [covered, missed]} for a counter type; {@code [0, 0]} when the report has none. */
    public long[] counter(String kind) {
        long[] value = counters.get(kind);
        return value == null || value.length < 2 ? new long[] {0, 0} : value;
    }

    /** The percentage covered for {@code kind}, or null when it counts nothing. */
    public Double percent(String kind) {
        long[] value = counter(kind);
        long total = value[0] + value[1];
        return total == 0 ? null : 100.0 * value[0] / total;
    }

    /** The line coverage: {@code percent("LINE")}. */
    public Double percent() {
        return percent("LINE");
    }
}
