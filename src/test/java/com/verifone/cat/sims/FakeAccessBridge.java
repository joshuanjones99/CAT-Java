package com.verifone.cat.sims;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * An in-memory Access Bridge: one window whose accessible tree the test builds from {@link Node}s.
 * Every object it hands out is tracked, so a test can see that each was released. A call can be made
 * to hang (until {@link #unblock}) or to throw, to drive the caps and the error rows.
 */
final class FakeAccessBridge implements AccessBridge {

    /** One accessible element. */
    static final class Node {
        final String name;
        final String role;
        final List<Node> children = new ArrayList<>();
        List<String> actions = List.of("click");
        boolean actionWorks = true;
        /** Runs when the element is clicked, e.g. to change the tree. */
        Runnable onClick = () -> { };

        Node(String name, String role) {
            this.name = name;
            this.role = role;
        }

        static Node button(String name) {
            return new Node(name, Jab.PUSH_BUTTON);
        }

        static Node panel(Node... children) {
            Node panel = new Node("", "panel");
            panel.children.addAll(List.of(children));
            return panel;
        }
    }

    static final int VM = 7;

    final long hwnd;
    final Node root;

    /** How many {@link #isJavaWindow} calls answer false before it answers true. */
    int notJavaFor;
    /** When false, {@link #contextFromHwnd} fails. */
    boolean contextAvailable = true;
    /** Thrown from {@link #windowsRun}, once each call, when set. */
    volatile Error windowsRunError;
    /** When set, {@link #windowsRun} blocks until {@link #unblock}. */
    volatile boolean hangWindowsRun;
    /** When set, {@link #doAction} blocks until {@link #unblock}. */
    volatile boolean hangAction;

    final AtomicInteger windowsRuns = new AtomicInteger();
    final AtomicInteger pumps = new AtomicInteger();
    final List<String> clicked = new CopyOnWriteArrayList<>();
    final List<String> actionsDone = new CopyOnWriteArrayList<>();
    final Set<Long> live = ConcurrentHashMap.newKeySet();
    final List<Long> handedOut = new CopyOnWriteArrayList<>();
    final List<Long> released = new CopyOnWriteArrayList<>();
    final List<String> callThreads = new CopyOnWriteArrayList<>();

    private final CountDownLatch unblocked = new CountDownLatch(1);
    private final Map<Long, Node> objects = new ConcurrentHashMap<>();
    private final AtomicInteger isJavaCalls = new AtomicInteger();
    private long next = 100;

    FakeAccessBridge(long hwnd, Node root) {
        this.hwnd = hwnd;
        this.root = root;
    }

    /** Releases every hung call. */
    void unblock() {
        unblocked.countDown();
    }

    /** Objects handed out and never released. */
    Set<Long> leaked() {
        return Set.copyOf(live);
    }

    private synchronized JavaObject handOut(Node node) {
        long handle = next++;
        objects.put(handle, node);
        live.add(handle);
        handedOut.add(handle);
        return new JavaObject(VM, handle);
    }

    private Node node(JavaObject object) {
        Node node = objects.get(object.handle());
        if (node == null || !live.contains(object.handle())) {
            throw new IllegalStateException("stale or unknown java object " + object.handle());
        }
        return node;
    }

    private void hang() {
        try {
            unblocked.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void record() {
        callThreads.add(Thread.currentThread().getName());
    }

    @Override
    public void windowsRun() {
        record();
        windowsRuns.incrementAndGet();
        Error error = windowsRunError;
        if (error != null) {
            throw error;
        }
        if (hangWindowsRun) {
            hang();
        }
    }

    @Override
    public void pumpMessages(Duration max) {
        pumps.incrementAndGet();
        // A short real wait, so a holding pump loop does not spin.
        Park.park(Duration.ofMillis(Math.min(5, max.toMillis())));
    }

    @Override
    public boolean isJavaWindow(long window) {
        record();
        return window == hwnd && isJavaCalls.incrementAndGet() > notJavaFor;
    }

    @Override
    public Optional<JavaObject> contextFromHwnd(long window) {
        record();
        if (!contextAvailable || window != hwnd) {
            return Optional.empty();
        }
        return Optional.of(handOut(root));
    }

    @Override
    public ContextInfo info(JavaObject context) {
        record();
        Node node = node(context);
        return new ContextInfo(node.name, node.role, node.role, node.children.size());
    }

    @Override
    public JavaObject child(JavaObject parent, int index) {
        record();
        Node node = node(parent);
        if (index < 0 || index >= node.children.size()) {
            return new JavaObject(VM, 0);
        }
        return handOut(node.children.get(index));
    }

    @Override
    public List<String> actions(JavaObject context) {
        record();
        return node(context).actions;
    }

    @Override
    public boolean doAction(JavaObject context, String action) {
        record();
        Node node = node(context);
        if (hangAction) {
            hang();
        }
        actionsDone.add(action);
        if (!node.actionWorks) {
            return false;
        }
        clicked.add(node.name);
        node.onClick.run();
        return true;
    }

    @Override
    public void release(JavaObject object) {
        released.add(object.handle());
        live.remove(object.handle());
    }

    /** A pause that records and returns at once. */
    static Consumer<Duration> recording(List<Duration> into) {
        return into::add;
    }
}
