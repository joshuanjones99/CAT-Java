package com.verifone.petrotest.posshim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.verifone.isd.pcapps.automation.target.pos.topaz.ent.TokenInfoHolder;

/**
 * Tracks when each client's pushed state last changed.
 *
 * <h2>Why this exists</h2>
 *
 * Observed on the lab rig, 2026-08-03: with the POS idle, injecting a single
 * {@code EXIT} raised the transient overlay
 * {@code PROCESSING / PLEASE WAIT, auto_hide=true} — and that overlay was still
 * the shim's latest known state 48 seconds later. The POS had auto-hidden it on
 * screen without pushing a clearing event, so {@code /pos/idle} reported
 * {@code idle=false, basis=overlay-present} indefinitely against a POS that was
 * sitting on its idle screen.
 *
 * <p>That is the defining hazard of a push surface with no request path: the
 * shim's state is "the last thing we were told", and there is no way to ask
 * whether it is still true. Nothing in the retained agent offers a poll, so the
 * shim cannot fix this — but it can stop hiding it. Every snapshot carries the age
 * of the last observed change, so a caller can apply §6.3's bounded-wait
 * discipline ("no overlay change for N seconds and it was auto_hide" → treat as
 * gone) instead of trusting a boolean that may be minutes old.
 *
 * <h2>Why polling, and why that is not a fix</h2>
 *
 * {@code POSStatusMonitorImpl} writes into {@link TokenInfoHolder} directly and
 * this shim cannot hook those writes without forking the retained agent. So the
 * age here is measured by fingerprinting the holder on a timer: it is "when we
 * last <em>noticed</em> a change", not "when the POS last sent one", and two
 * pushes within one interval collapse into one observation. For deciding whether
 * state has gone quiet — the only thing it is used for — that distinction does not
 * matter, and overstating the precision would be worse than the bounded error.
 *
 * <p>Deliberately does not attempt to expire or blank stale state. A shim that
 * silently replaced a stale overlay with "no overlay" after a timeout it invented
 * would be fabricating a screen, which is the failure this whole surface has been
 * built to avoid. Reporting the age and letting the caller decide keeps the
 * judgement where the timeout policy lives.
 */
final class StateWatcher implements Runnable {

    /**
     * How often the holders are fingerprinted.
     *
     * <p>Fast enough that a keypress round-trip (~230ms on the rig) is usually
     * seen as a distinct change, slow enough to be invisible next to a POS that
     * pushes a handful of events per screen transition.
     */
    private static final long POLL_INTERVAL_MILLIS = 500L;

    private final ShimRmiHost host;
    private final Map<String, String> fingerprints = new ConcurrentHashMap<>();
    private final Map<String, Long> lastChangeMillis = new ConcurrentHashMap<>();
    private final Map<String, Long> firstSeenMillis = new ConcurrentHashMap<>();

    StateWatcher(ShimRmiHost host) {
        this.host = host;
    }

    void startDaemon() {
        Thread thread = new Thread(this, "petrotest-shim-state-watcher");
        // A daemon, so it never keeps the JVM alive after the HTTP server stops.
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                sweep();
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A transient failure reading one holder must not kill the
                // watcher: losing it would silently turn every age field into
                // "unknown" for the rest of the run, which reads as a shim bug
                // far from its cause.
                System.err.println("[pos-shim] state watcher sweep failed: " + e);
            }
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (String client : host.clients()) {
            TokenInfoHolder holder = host.holder(client);
            if (holder == null) {
                continue;
            }
            String fingerprint = Json.encode(new Snapshot(holder).toMap());
            String previous = fingerprints.put(client, fingerprint);
            if (previous == null) {
                firstSeenMillis.putIfAbsent(client, now);
                lastChangeMillis.put(client, now);
            } else if (!previous.equals(fingerprint)) {
                lastChangeMillis.put(client, now);
            }
        }
    }

    /**
     * Seconds since this client's state was last observed to change, or null.
     *
     * <p>Null means the watcher has not fingerprinted this client yet — within
     * the first poll interval of it appearing. Reported as null rather than 0
     * because "changed just now" and "never looked" are different, and a 0 would
     * read as the freshest possible state.
     */
    Long ageSeconds(String client) {
        Long changed = lastChangeMillis.get(client);
        if (changed == null) {
            return null;
        }
        return (System.currentTimeMillis() - changed) / 1000L;
    }

    /** Adds the age fields to a snapshot map, in place. */
    void annotate(Map<String, Object> snapshot, String client) {
        snapshot.put("last_change_age_s", ageSeconds(client));
        snapshot.put("state_age_note",
                "seconds since the shim last observed a change; the POS pushes state and "
                        + "cannot be polled, so a large age on a screen that should be moving "
                        + "means the last push is stale (auto-hide overlays are not always "
                        + "followed by a dismissal push)");
    }
}
