package com.verifone.cat.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The progress of the one action the Downloads and Install Upgrade pages can have in flight: a
 * bounded log, a state, the last message, and the files a download is fetching.
 *
 * <p>Written by the action's worker thread and read by the polled panels, so every access is
 * synchronized or volatile. Nothing here touches the network.
 */
final class JobLog {

    /** The most log lines kept; the oldest go first. */
    static final int MAX_LINES = 400;

    /** One file a download fetches: its name, where it lands, and Artifactory's size for it. */
    record Transfer(String name, Path target, long expected) {

        /** Bytes on disk so far: the whole file, or its {@code .part} while it is still arriving. */
        long received() {
            try {
                if (Files.isRegularFile(target)) {
                    return Files.size(target);
                }
                Path part = target.resolveSibling(target.getFileName() + ".part");
                return Files.isRegularFile(part) ? Files.size(part) : 0L;
            } catch (IOException e) {
                return 0L;
            }
        }
    }

    private final Deque<String> lines = new ArrayDeque<>();
    private List<Transfer> transfers = List.of();
    private String state = "";
    private String message = "";

    /** Clears the log for a new action, which is now running. */
    synchronized void start(String title) {
        lines.clear();
        transfers = List.of();
        state = "running";
        message = title + " running.";
    }

    /** Appends one line, dropping the oldest once the log is full. */
    synchronized void line(String text) {
        lines.addLast(text);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    synchronized void transfers(List<Transfer> files) {
        transfers = List.copyOf(files);
    }

    /** Ends the action: {@code error} is empty on success, else the one reason it stopped. */
    synchronized void finish(String error, String okMessage) {
        state = error.isEmpty() ? "done" : "failed";
        message = error.isEmpty() ? okMessage : error;
    }

    synchronized String state() {
        return state;
    }

    synchronized String message() {
        return message;
    }

    synchronized List<String> lines() {
        return new ArrayList<>(lines);
    }

    synchronized List<Transfer> transfers() {
        return transfers;
    }
}
