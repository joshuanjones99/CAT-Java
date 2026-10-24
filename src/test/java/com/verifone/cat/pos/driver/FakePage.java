package com.verifone.cat.pos.driver;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;

/**
 * A recording stand-in for a Playwright {@link Page}, built on dynamic proxies. Not a Playwright
 * mock of behaviour: it records the calls the driver makes, so a test can assert the property
 * DESIGN.md 6.3 cares about (a timeout was passed, every time), and answers only what the driver
 * reads back. Evidence about this repository, never about a register.
 */
final class FakePage {

    /** One recorded call: the method, the selector it was made on, its timeout if any, and its argument. */
    record Call(String name, String selector, Double timeout, Object arg) {}

    final List<Call> calls = new ArrayList<>();
    /** Selectors that "exist". {@code null} means every selector does. */
    Set<String> present;
    /** The answer to {@code evaluate}; the default is "true", as the Python fake's. */
    BiFunction<String, Object, Object> evaluator = (script, arg) -> Boolean.TRUE;
    final List<Frame> frames = new ArrayList<>();
    String content = "<html><body>POS</body></html>";
    boolean failContent;
    boolean failScreenshot;
    final Page page;

    FakePage() {
        this.page = proxy(Page.class, this::pageCall);
    }

    /** The fake as a Playwright page. */
    Page page() {
        return page;
    }

    /** A recorded call with the given name, in order. */
    List<Call> named(String name) {
        return calls.stream().filter(c -> c.name().equals(name)).toList();
    }

    private Object pageCall(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "locator":
                return locator((String) args[0]);
            case "frameLocator":
                return frameLocator((String) args[0]);
            case "evaluate":
                String script = (String) args[0];
                Object arg = args.length > 1 ? args[1] : null;
                calls.add(new Call("evaluate", null, null, arg));
                return evaluator.apply(script, arg);
            case "mouse":
                return mouse();
            case "content":
                if (failContent) {
                    throw new IllegalStateException("CDP session gone");
                }
                return content;
            case "screenshot":
                return screenshot((com.microsoft.playwright.Page.ScreenshotOptions) args[0]);
            case "frames":
                return List.copyOf(frames);
            case "context":
                return null;
            default:
                return defaultFor(method.getReturnType());
        }
    }

    private Object screenshot(com.microsoft.playwright.Page.ScreenshotOptions options) {
        calls.add(new Call("screenshot", null, options.timeout, null));
        if (failScreenshot) {
            throw new IllegalStateException("CDP session gone");
        }
        byte[] png = "\u0089PNG fake".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        try {
            Files.write((Path) options.path, png);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return png;
    }

    private Locator locator(String selector) {
        return proxy(Locator.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "count":
                    return present == null || present.contains(selector) ? 1 : 0;
                case "waitFor":
                    calls.add(new Call("waitFor", selector, ((Locator.WaitForOptions) args[0]).timeout, null));
                    return null;
                case "boundingBox": {
                    calls.add(new Call("boundingBox", selector,
                            ((Locator.BoundingBoxOptions) args[0]).timeout, null));
                    BoundingBox box = new BoundingBox();
                    box.x = 10;
                    box.y = 20;
                    box.width = 30;
                    box.height = 40;
                    return box;
                }
                case "textContent":
                    calls.add(new Call("textContent", selector,
                            ((Locator.TextContentOptions) args[0]).timeout, null));
                    return "  CSR Func  ";
                default:
                    return defaultFor(method.getReturnType());
            }
        });
    }

    private FrameLocator frameLocator(String selector) {
        return proxy(FrameLocator.class, (proxy, method, args) -> {
            if (method.getName().equals("locator")) {
                return locator(selector + " >> " + args[0]);
            }
            return defaultFor(method.getReturnType());
        });
    }

    private Mouse mouse() {
        return proxy(Mouse.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "move":
                    calls.add(new Call("mouse.move", null, null, List.of(args[0], args[1])));
                    return null;
                case "down":
                case "up":
                    calls.add(new Call("mouse." + method.getName(), null, null, null));
                    return null;
                default:
                    return defaultFor(method.getReturnType());
            }
        });
    }

    /** A frame the page lists, answering its name, URL and content. */
    Frame frame(String name, String url, String frameContent) {
        Frame frame = proxy(Frame.class, (proxy, method, args) -> switch (method.getName()) {
            case "name" -> name;
            case "url" -> url;
            case "content" -> frameContent;
            default -> defaultFor(method.getReturnType());
        });
        frames.add(frame);
        return frame;
    }

    /** A browser context listing the given pages. */
    static BrowserContext context(List<Page> pages) {
        return proxy(BrowserContext.class, (proxy, method, args) -> method.getName().equals("pages")
                ? List.copyOf(pages) : defaultFor(method.getReturnType()));
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
        return (T) Proxy.newProxyInstance(FakePage.class.getClassLoader(), new Class<?>[] {type}, guarded);
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
