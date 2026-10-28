package com.verifone.cat.sims;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The calls {@link Jab} makes into the Java Access Bridge ({@code WindowsAccessBridge-64.dll}), one
 * method per DLL entry point, so the click sequence is tested against a fake and the real bindings
 * ({@link JnaAccessBridge}) stay a thin class the tests never call.
 *
 * <p>Every method may block indefinitely: each is a synchronous call into the bridge, which talks to
 * the simulator's JVM by window messages and has no timeout of its own. {@link Jab} makes every call
 * on an abandonable daemon thread for that reason.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface AccessBridge {

    /**
     * One Java object the bridge handed out: the JVM it lives in and its 64-bit handle. The bridge
     * keeps each one alive in the JVM until {@link #release} is called on it.
     *
     * @param vmId the JVM's id ({@code long vmID}, 32 bits on Windows)
     * @param handle the object ({@code JOBJECT64}); 0 is no object
     */
    record JavaObject(int vmId, long handle) {

        /** True for the bridge's "no object" answer. */
        public boolean isNull() {
            return handle == 0;
        }
    }

    /**
     * The parts of {@code AccessibleContextInfo} the click sequence reads.
     *
     * @param name the accessible name, the button's label for a Swing button
     * @param role the localised role, {@code push button} for a button on an English controller
     * @param roleEnUs the role in US English
     * @param childrenCount how many children it has
     */
    record ContextInfo(String name, String role, String roleEnUs, int childrenCount) {

        public ContextInfo {
            name = name == null ? "" : name;
            role = role == null ? "" : role;
            roleEnUs = roleEnUs == null ? "" : roleEnUs;
        }
    }

    /**
     * {@code Windows_run}: starts the bridge on the calling thread. That thread must then pump window
     * messages ({@link #pumpMessages}) for the JVMs to register with it, as pyjab does after calling it.
     */
    void windowsRun();

    /**
     * Waits up to {@code max} for window messages on the calling thread and dispatches every one
     * waiting: pyjab's {@code setup_msg_pump} ({@code MsgWaitForMultipleObjects} then
     * {@code PumpWaitingMessages}). Returns within about {@code max}.
     */
    void pumpMessages(Duration max);

    /** {@code isJavaWindow}: whether {@code hwnd} is a window of a JVM the bridge knows. */
    boolean isJavaWindow(long hwnd);

    /** {@code getAccessibleContextFromHWND}: the window's root context, or empty when the call fails. */
    Optional<JavaObject> contextFromHwnd(long hwnd);

    /**
     * {@code getAccessibleContextInfo}.
     *
     * @throws IllegalStateException when the bridge reports failure
     */
    ContextInfo info(JavaObject context);

    /** {@code getAccessibleChildFromContext}: the {@code index}th child; a null object when there is none. */
    JavaObject child(JavaObject parent, int index);

    /**
     * {@code getAccessibleActions}: the action names the context supports, in the bridge's order.
     *
     * @throws IllegalStateException when the bridge reports failure
     */
    List<String> actions(JavaObject context);

    /** {@code doAccessibleActions} with one action: whether the bridge reports it done. */
    boolean doAction(JavaObject context, String action);

    /** {@code releaseJavaObject}: lets the JVM collect an object the bridge handed out. */
    void release(JavaObject object);
}
