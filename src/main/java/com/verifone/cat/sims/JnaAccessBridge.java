package com.verifone.cat.sims;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

/**
 * {@link AccessBridge} on {@code WindowsAccessBridge-64.dll} through JNA, in place of pyjab
 * ({@code pyjab.jabdriver}, {@code pyjab.jabelement}, {@code pyjab.jabfixedfunc}), which wraps the
 * same DLL with ctypes. The signatures and structures are pyjab's (and the JDK's
 * {@code AccessBridgePackages.h}): {@code long vmID} is 32 bits on Windows, {@code JOBJECT64} is a
 * {@code jlong}, strings are {@code wchar_t} arrays of 1024 ({@code MAX_STRING_SIZE}) or 256
 * ({@code SHORT_STRING_SIZE}).
 *
 * <p>The DLL is the one beside the {@code javaw.exe} that launches the simulators
 * ({@link SimContext#bridgeDll}), never a {@code JAB_DLL} environment variable: that is how all four
 * {@code click:} rows failed on the Python CAT's first real launch (2026-09-10). It is loaded on the
 * first call, which {@link Jab} makes on its abandonable worker thread.
 *
 * <p>A thin binding: no test calls it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). pyjab over this DLL pressed the buttons of all four
 * simulators on 2026-09-10; this binding has not been run against them.
 */
public final class JnaAccessBridge implements AccessBridge {

    private static final int MAX_STRING_SIZE = 1024;
    private static final int SHORT_STRING_SIZE = 256;
    private static final int MAX_ACTION_INFO = 256;
    private static final int MAX_ACTIONS_TO_DO = 32;

    private final Path dll;
    private volatile Lib lib;

    public JnaAccessBridge(Path dll) {
        this.dll = Objects.requireNonNull(dll, "dll");
    }

    /** The DLL's entry points, as pyjab's {@code JABFixedFunc} declares them. */
    interface Lib extends Library {
        void Windows_run();

        int isJavaWindow(HWND hwnd);

        int getAccessibleContextFromHWND(HWND hwnd, IntByReference vmId, LongByReference context);

        int getAccessibleContextInfo(int vmId, long context, AccessibleContextInfo info);

        long getAccessibleChildFromContext(int vmId, long context, int index);

        int getAccessibleActions(int vmId, long context, AccessibleActions actions);

        int doAccessibleActions(int vmId, long context, AccessibleActionsToDo todo, IntByReference failure);

        void releaseJavaObject(int vmId, long object);
    }

    /** {@code AccessibleContextInfo}. */
    @Structure.FieldOrder({"name", "description", "role", "role_en_US", "states", "states_en_US",
            "indexInParent", "childrenCount", "x", "y", "width", "height", "accessibleComponent",
            "accessibleAction", "accessibleSelection", "accessibleText", "accessibleInterfaces"})
    public static final class AccessibleContextInfo extends Structure {
        public char[] name = new char[MAX_STRING_SIZE];
        public char[] description = new char[MAX_STRING_SIZE];
        public char[] role = new char[SHORT_STRING_SIZE];
        public char[] role_en_US = new char[SHORT_STRING_SIZE];
        public char[] states = new char[SHORT_STRING_SIZE];
        public char[] states_en_US = new char[SHORT_STRING_SIZE];
        public int indexInParent;
        public int childrenCount;
        public int x;
        public int y;
        public int width;
        public int height;
        public int accessibleComponent;
        public int accessibleAction;
        public int accessibleSelection;
        public int accessibleText;
        public int accessibleInterfaces;
    }

    /** {@code AccessibleActionInfo}. */
    @Structure.FieldOrder({"name"})
    public static class AccessibleActionInfo extends Structure {
        public char[] name = new char[SHORT_STRING_SIZE];
    }

    /** {@code AccessibleActions}. */
    @Structure.FieldOrder({"actionsCount", "actionInfo"})
    public static final class AccessibleActions extends Structure {
        public int actionsCount;
        public AccessibleActionInfo[] actionInfo = (AccessibleActionInfo[]) new AccessibleActionInfo()
                .toArray(MAX_ACTION_INFO);
    }

    /** {@code AccessibleActionsToDo}. */
    @Structure.FieldOrder({"actionsCount", "actions"})
    public static final class AccessibleActionsToDo extends Structure {
        public int actionsCount;
        public AccessibleActionInfo[] actions = (AccessibleActionInfo[]) new AccessibleActionInfo()
                .toArray(MAX_ACTIONS_TO_DO);
    }

    private Lib lib() {
        Lib loaded = lib;
        if (loaded == null) {
            synchronized (this) {
                if (lib == null) {
                    lib = Native.load(dll.toString(), Lib.class);
                }
                loaded = lib;
            }
        }
        return loaded;
    }

    @Override
    public void windowsRun() {
        lib().Windows_run();
    }

    @Override
    public void pumpMessages(Duration max) {
        User32Lib.pump((int) Math.min(Integer.MAX_VALUE, Math.max(0, max.toMillis())));
    }

    @Override
    public boolean isJavaWindow(long hwnd) {
        return lib().isJavaWindow(User32Lib.hwnd(hwnd)) != 0;
    }

    @Override
    public Optional<JavaObject> contextFromHwnd(long hwnd) {
        IntByReference vmId = new IntByReference();
        LongByReference context = new LongByReference();
        if (lib().getAccessibleContextFromHWND(User32Lib.hwnd(hwnd), vmId, context) == 0
                || context.getValue() == 0) {
            return Optional.empty();
        }
        return Optional.of(new JavaObject(vmId.getValue(), context.getValue()));
    }

    @Override
    public ContextInfo info(JavaObject context) {
        AccessibleContextInfo info = new AccessibleContextInfo();
        if (lib().getAccessibleContextInfo(context.vmId(), context.handle(), info) == 0) {
            throw new IllegalStateException("getAccessibleContextInfo failed");
        }
        return new ContextInfo(Native.toString(info.name), Native.toString(info.role),
                Native.toString(info.role_en_US), info.childrenCount);
    }

    @Override
    public JavaObject child(JavaObject parent, int index) {
        return new JavaObject(parent.vmId(),
                lib().getAccessibleChildFromContext(parent.vmId(), parent.handle(), index));
    }

    @Override
    public List<String> actions(JavaObject context) {
        AccessibleActions actions = new AccessibleActions();
        if (lib().getAccessibleActions(context.vmId(), context.handle(), actions) == 0) {
            throw new IllegalStateException("getAccessibleActions failed");
        }
        List<String> names = new ArrayList<>();
        int count = Math.max(0, Math.min(actions.actionsCount, MAX_ACTION_INFO));
        for (int i = 0; i < count; i++) {
            names.add(Native.toString(actions.actionInfo[i].name));
        }
        return names;
    }

    @Override
    public boolean doAction(JavaObject context, String action) {
        AccessibleActionsToDo todo = new AccessibleActionsToDo();
        todo.actionsCount = 1;
        char[] name = todo.actions[0].name;
        action.getChars(0, Math.min(action.length(), name.length - 1), name, 0);
        return lib().doAccessibleActions(context.vmId(), context.handle(), todo, new IntByReference()) != 0;
    }

    @Override
    public void release(JavaObject object) {
        if (!object.isNull()) {
            lib().releaseJavaObject(object.vmId(), object.handle());
        }
    }
}
