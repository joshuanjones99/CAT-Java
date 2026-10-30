package com.verifone.cat.shell;

import java.time.Duration;

/**
 * When to close CAT, from the clock, the open pages and what holds CAT: the Python CAT's
 * {@code cat.web.autoclose.PageWatch}. No I/O.
 *
 * <p>The CAT shortcut starts the server with no window, so closing the browser is how an operator quits. A reload,
 * or a move from Run to Results, closes one page and opens another, so CAT waits {@link #CLOSE_AFTER} with no page
 * open, and {@link #FIRST_PAGE_WAIT} for the first page while the browser starts. A run, a schedule, a continuous
 * run, a download or a recording keeps CAT serving with no page open.
 */
public final class PageWatch {

    /** No page open this long, with nothing holding CAT, and CAT closes. */
    public static final Duration CLOSE_AFTER = Duration.ofSeconds(30);

    /** The first page may take longer: the browser is starting and setup is checking. */
    public static final Duration FIRST_PAGE_WAIT = Duration.ofSeconds(120);

    /** How often the watch looks. */
    public static final Duration CHECK_EVERY = Duration.ofSeconds(5);

    private boolean seenPage;
    private long quietSince;

    /** @param nowNanos the watch's start, on {@link System#nanoTime()}'s clock */
    public PageWatch(long nowNanos) {
        this.quietSince = nowNanos;
    }

    public boolean seenPage() {
        return seenPage;
    }

    /** True once CAT has been unattended long enough to close. */
    public boolean shouldClose(long nowNanos, int openPages, String holding) {
        if (openPages > 0 || (holding != null && !holding.isEmpty())) {
            seenPage = seenPage || openPages > 0;
            quietSince = nowNanos;
            return false;
        }
        Duration wait = seenPage ? CLOSE_AFTER : FIRST_PAGE_WAIT;
        return nowNanos - quietSince >= wait.toNanos();
    }
}
