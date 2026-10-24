package com.verifone.cat.validate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One test's view of its {@link SnapshotStore}: the Java counterpart of the Python CAT's
 * {@code snapshot} fixture object (DESIGN.md §5.1, §5.4).
 *
 * <p>Baselines are named for the case, not the test function, because that is PATS's
 * convention. So two assertions that want the same baseline in one module would overwrite each
 * other, and {@link #claim} and {@link #record} refuse that. The claim set is shared across the
 * tests of one module for that reason; pass the same {@link Set} to each session.
 *
 * <p>A parametrised test contributes its parameter first, so each parameter keeps its own
 * baseline. A test that asserts two receipts passes a {@code name} to the second.
 *
 * <p>UNVERIFIED AGAINST HARDWARE. This class is the naming and claim logic only; nothing here
 * runs a test.
 */
public final class SnapshotSession {

    private final SnapshotStore store;
    private final String testId;
    private final String param;
    private boolean update;
    private final List<Path> written = new ArrayList<>();
    private final List<Path> kept = new ArrayList<>();
    private final Set<String> used;

    private SnapshotSession(SnapshotStore store, String testId, String param, boolean update, Set<String> used) {
        this.store = store;
        this.testId = testId;
        this.param = param;
        this.update = update;
        this.used = used;
    }

    /** A session for one test, with its own claim set. */
    public static SnapshotSession forNodeId(Path testFile, String nodeId) {
        return forNodeId(testFile, nodeId, false, new HashSet<>());
    }

    /**
     * A session for one test. {@code used} is the module's shared claim set; a session given
     * the same set refuses a baseline another test already claimed.
     *
     * @param nodeId the test's id, as {@code file.py::test_name[param]}; only the part after the
     *               last {@code ::} is used
     */
    public static SnapshotSession forNodeId(Path testFile, String nodeId, boolean update, Set<String> used) {
        int cut = nodeId.lastIndexOf("::");
        String name = cut < 0 ? nodeId : nodeId.substring(cut + 2);
        String source = name.isEmpty() ? nodeId : name;
        return new SnapshotSession(SnapshotStore.forTestFile(testFile), SnapshotStore.safe(source),
                SnapshotStore.param(source), update, used);
    }

    /** Whether this run writes baselines instead of comparing against them. */
    public boolean update() {
        return update;
    }

    /** Switches between update mode and comparison mode for this session. */
    public void setUpdate(boolean value) {
        this.update = value;
    }

    /** The sanitised test name, e.g. {@code test_indoor_prepay}. */
    public String testId() {
        return testId;
    }

    /** Files written during this session, for the run summary. */
    public List<Path> written() {
        return List.copyOf(written);
    }

    /** Baselines an update deliberately left alone, for the run summary. */
    public List<Path> kept() {
        return List.copyOf(kept);
    }

    /** Forgets every baseline claimed so far. The claim set is shared, so this clears it for all. */
    void clearClaims() {
        used.clear();
    }

    /** The store this session reads and writes. */
    public SnapshotStore store() {
        return store;
    }

    /**
     * The variant for one assertion: the parameter first, then the caller's name, joined with
     * {@code -}, and {@code ""} when neither is set.
     */
    public String key(String name) {
        String sanitised = name == null || name.isEmpty() ? "" : SnapshotStore.safe(name);
        StringBuilder key = new StringBuilder();
        for (String part : new String[] {param, sanitised}) {
            if (part.isEmpty()) {
                continue;
            }
            if (key.length() > 0) {
                key.append('-');
            }
            key.append(part);
        }
        return key.toString();
    }

    /** The path of one baseline for this assertion. */
    public Path path(String suffix, String name) {
        return store.path(key(name), suffix);
    }

    /** The text of one baseline for this assertion, or {@code null} if there is none. */
    public String read(String suffix, String name) {
        return store.read(key(name), suffix);
    }

    /**
     * Writes a baseline and claims it. Refuses if another assertion already claimed the same
     * file: two claims would overwrite each other's baseline.
     */
    public Path record(String suffix, String content, String name) {
        String key = key(name);
        String slot = SnapshotStore.compose(store.base(), suffix, key);
        claimSlot(slot);
        Path written = store.write(key, suffix, content);
        this.written.add(written);
        return written;
    }

    /** Claims a baseline without writing it, for the comparison path. */
    public void claim(String suffix, String name) {
        claimSlot(SnapshotStore.compose(store.base(), suffix, key(name)));
    }

    /** Notes a baseline an update left alone, for the run summary. */
    public void keep(Path existing) {
        kept.add(existing);
    }

    /** The error for a baseline that does not exist yet. It says how to create it. */
    public SnapshotMissingException missing(String suffix, String name) {
        Path target = path(suffix, name);
        return new SnapshotMissingException("no snapshot baseline at " + target + ".\n"
                + "  Create it: run this test once in snapshot update mode.\n"
                + "  Then review the new file as a normal diff in the pull request "
                + "(DESIGN.md 5.4).");
    }

    private void claimSlot(String slot) {
        if (!used.add(slot)) {
            throw new ValidationFailedException("two assertions claim '" + slot + "'. Pass name= to "
                    + "the second (e.g. assertReceipt(..., snapshot, \"void\")), or they overwrite each "
                    + "other's baseline. Baselines are named for the case, not the test function, so "
                    + "this covers other tests in this module too (DESIGN.md 5.4).");
        }
    }
}
