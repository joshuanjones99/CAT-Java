package com.verifone.cat.pos.pages;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;

/**
 * A recording stand-in for a Playwright {@link Page}, for the page objects' selector routing. A
 * copy of the driver tests' fake, scoped to this package. It records what the driver asks for, and
 * answers only what the driver reads back. Evidence about this repository, never about a register.
 *
 * <p>Snapshot reads (count, textContent) and waits are answered; assertions that retry through
 * Playwright's own matchers are not, so those are covered by {@link PagesBrowserTest}.
 */
final class PagesFakePage {

    /** One recorded call: the method name, the selector it was made on, its timeout if any. */
    record Call(String name, String selector, Double timeout) {}

    final List<Call> calls = new ArrayList<>();
    /** Selectors that "exist". {@code null} means every selector does. */
    Set<String> present;
    /** The text textContent returns for every element. */
    String text = "  CSR Func  ";
    final Page page;

    PagesFakePage() {
        this.page = proxy(Page.class, this::pageCall);
    }

    Page page() {
        return page;
    }

    /** The selectors waited on, in order: the element each tap or expectation targeted. */
    List<String> waitedSelectors() {
        List<String> out = new ArrayList<>();
        for (Call call : calls) {
            if (call.name().equals("waitFor")) {
                out.add(call.selector());
            }
        }
        return out;
    }

    private Object pageCall(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "locator":
                return locator((String) args[0]);
            case "frameLocator":
                return frameLocator((String) args[0]);
            case "evaluate":
                calls.add(new Call("evaluate", null, null));
                return Boolean.TRUE;
            case "mouse":
                return mouse();
            default:
                return defaultFor(method.getReturnType());
        }
    }

    private Locator locator(String selector) {
        return proxy(Locator.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "count":
                    return present == null || present.contains(selector) ? 1 : 0;
                case "waitFor":
                    calls.add(new Call("waitFor", selector,
                            ((Locator.WaitForOptions) args[0]).timeout));
                    return null;
                case "boundingBox": {
                    calls.add(new Call("boundingBox", selector,
                            ((Locator.BoundingBoxOptions) args[0]).timeout));
                    BoundingBox box = new BoundingBox();
                    box.x = 10;
                    box.y = 20;
                    box.width = 30;
                    box.height = 40;
                    return box;
                }
                case "textContent":
                    calls.add(new Call("textContent", selector,
                            ((Locator.TextContentOptions) args[0]).timeout));
                    return text;
                default:
                    return defaultFor(method.getReturnType());
            }
        });
    }

    /** A frame owner's locator: the selector is recorded as {@code owner >> selector}. */
    private FrameLocator frameLocator(String owner) {
        return proxy(FrameLocator.class, (proxy, method, args) -> {
            if (method.getName().equals("locator")) {
                return locator(owner + " >> " + args[0]);
            }
            return defaultFor(method.getReturnType());
        });
    }

    private Mouse mouse() {
        return proxy(Mouse.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "move", "down", "up" -> calls.add(new Call("mouse." + method.getName(), null, null));
                default -> {
                    return defaultFor(method.getReturnType());
                }
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        InvocationHandler guarded = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "fake " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            return handler.invoke(proxy, method, args);
        };
        return (T) Proxy.newProxyInstance(PagesFakePage.class.getClassLoader(), new Class<?>[] {type}, guarded);
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        return null;
    }
}
