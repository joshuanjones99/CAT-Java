package com.verifone.cat.plans;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Rows plus where they came from, so an empty list can say <em>why</em>.
 *
 * <p>A silent empty picker is the mistake this record exists to prevent: every empty
 * result carries a non-blank {@code reason}. Ported from {@code cat.plans.Snapshot}.
 *
 * @param fetchedAt when the rows were read from the network or written to the cache;
 *     {@code null} for the shipped seed, whose capture date would otherwise read as
 *     "cached today" on a checkout pulled a year later
 */
public record Snapshot<T>(List<T> rows, Instant fetchedAt, Source source, String reason) {

    /** Where the rows came from. {@link #label()} is the word an operator reads. */
    public enum Source {
        NETWORK("network"),
        CACHE("cache"),
        SEED("seed"),
        EMPTY("empty");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public Snapshot {
        rows = List.copyOf(rows);
        reason = Text.orEmpty(reason);
    }

    /** How stale the rows are, or empty when they never came from anywhere. */
    public Optional<Duration> age(Instant now) {
        return fetchedAt == null ? Optional.empty() : Optional.of(Duration.between(fetchedAt, now));
    }

    Snapshot<T> withReason(String newReason) {
        return new Snapshot<>(rows, fetchedAt, source, newReason);
    }
}
