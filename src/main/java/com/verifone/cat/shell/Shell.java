package com.verifone.cat.shell;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * What every page carries and the shell holds once per web process: the Python CAT's {@code CatService} parts that
 * are not the run. Toasts for every open page (a page that opens later does not replay old ones), the loading lines
 * ({@code setup}, {@code tools}), the setup status the sidebar's marker and the Run page's warning button show, the
 * one {@link RigBusy} flag, which pages are open (each page's poll is its heartbeat), and what keeps CAT serving with
 * no page open ({@link #holding()}).
 *
 * <p>A page is open while it has polled within {@link #PAGE_ALIVE}: longer than the poll, because a browser slows a
 * hidden tab's timers to one a minute. A page that closes says so ({@link #pageClosed}), so closing the last tab is
 * seen at once. Thread-safe; no I/O.
 */
public final class Shell {

    /** How long after its last poll a page still counts as open. */
    public static final Duration PAGE_ALIVE = Duration.ofSeconds(75);

    /** The most toasts kept, as the Python's {@code deque(maxlen=50)}. */
    static final int TOAST_LIMIT = 50;

    /** One message for every open page: {@code kind} is info, positive, warning or error. */
    public record Toast(long id, String message, String kind, double timeoutSeconds) {}

    private final RigBusy busy = new RigBusy();
    private final Deque<Toast> toasts = new ArrayDeque<>();
    private final Map<String, String> loading = new LinkedHashMap<>();
    private final Map<String, Long> pages = new LinkedHashMap<>();
    private final List<Supplier<String>> holders = new CopyOnWriteArrayList<>();
    private final List<Runnable> shutdownHooks = new CopyOnWriteArrayList<>();
    private long lastToast;
    private volatile String setupStatus = "";
    private volatile boolean toolsStaging;

    public RigBusy busy() {
        return busy;
    }

    // ------------------------------------------------------------------ toasts

    public void notify(String message) {
        notify(message, "info", 5);
    }

    /** A toast for every open page. */
    public synchronized void notify(String message, String kind, double timeoutSeconds) {
        lastToast++;
        toasts.addLast(new Toast(lastToast, message == null ? "" : message, kind == null ? "info" : kind,
                timeoutSeconds));
        while (toasts.size() > TOAST_LIMIT) {
            toasts.removeFirst();
        }
    }

    /** The id of the newest toast: a page that opens now starts after it. */
    public synchronized long lastToast() {
        return lastToast;
    }

    /** The toasts newer than {@code id}, oldest first. */
    public synchronized List<Toast> toastsSince(long id) {
        List<Toast> out = new ArrayList<>();
        for (Toast toast : toasts) {
            if (toast.id() > id) {
                out.add(toast);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the loading line and setup status

    /** Shows a loading line under {@code key} at the top of every page. */
    public synchronized void loading(String key, String text) {
        loading.put(key, text);
    }

    public synchronized void doneLoading(String key) {
        loading.remove(key);
    }

    public synchronized String loading(String key) {
        return loading.getOrDefault(key, "");
    }

    /** "Loading CAT — checking setup · ..." or "" when nothing is loading. */
    public synchronized String loadingLine() {
        return loading.isEmpty() ? "" : "Loading CAT — " + String.join(" · ", loading.values());
    }

    /** The header's setup line; "" when setup needs nothing. */
    public String setupStatus() {
        return setupStatus;
    }

    public void setupStatus(String text) {
        setupStatus = text == null ? "" : text;
    }

    public boolean toolsStaging() {
        return toolsStaging;
    }

    public void toolsStaging(boolean on) {
        toolsStaging = on;
    }

    /**
     * The header's setup line, in the browser's words: the Python's {@code setup_status_text}.
     *
     * @param remaining   how many items still need attention
     * @param credentials how many of them are sign-ins
     * @param rejected    how many saved logins were rejected
     */
    public static String setupStatusText(int remaining, int credentials, int rejected) {
        if (rejected > 0) {
            return rejected + " login(s) rejected: open Setup to fix";
        }
        if (remaining == 0) {
            return "";
        }
        if (credentials > 0) {
            return "Sign in on Setup · " + remaining + " setup item(s)";
        }
        return "Setup: " + remaining + " item(s) need attention";
    }

    // ------------------------------------------------------------------ open pages

    /** A page polled: it is open. */
    public synchronized void heartbeat(String page, long nowNanos) {
        if (page != null && !page.isBlank()) {
            pages.put(page, nowNanos);
        }
    }

    /** A page said it is closing (its {@code pagehide}). */
    public synchronized void pageClosed(String page) {
        pages.remove(page);
    }

    /** How many pages have polled within {@link #PAGE_ALIVE}. */
    public synchronized int openPages(long nowNanos) {
        pages.values().removeIf(seen -> nowNanos - seen > PAGE_ALIVE.toNanos());
        return pages.size();
    }

    // ------------------------------------------------------------------ what keeps CAT serving

    /** Adds a reader of something that keeps CAT serving ("a scheduled run", "a recording"), "" when it does not. */
    public void holdWhile(Supplier<String> holder) {
        holders.add(holder);
    }

    /**
     * What must keep CAT serving with no page open, or "": the Python's {@code CatService.holding}. Closing the
     * browser mid-run must not stop the run, nor drop a schedule, a continuous run, a download or a recording.
     */
    public String holding() {
        String held = busy.current();
        if (!held.isEmpty()) {
            return RigBusy.words(held);
        }
        for (Supplier<String> holder : holders) {
            String why;
            try {
                why = holder.get();
            } catch (RuntimeException e) {
                why = "";
            }
            if (why != null && !why.isEmpty()) {
                return why;
            }
        }
        if (toolsStaging) {
            return "a download";
        }
        return "";
    }

    // ------------------------------------------------------------------ shutting down

    /** Something to stop when CAT shuts down (a run in flight, a simulator launch). */
    public void onShutdown(Runnable hook) {
        shutdownHooks.add(hook);
    }

    /** Runs every shutdown hook, each on its own: one that throws does not stop the rest. */
    public void shutdown() {
        for (Runnable hook : shutdownHooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                // Shutting down regardless.
            }
        }
    }

    /** The Shut down CAT dialog's note: what shutting down stops. */
    public String shutdownNote() {
        String held = busy.current();
        return held.isEmpty() ? "Every open CAT page stops working until CAT is started again."
                : RigBusy.capitalise(RigBusy.words(held)) + " is in progress. Shutting down stops it.";
    }
}
