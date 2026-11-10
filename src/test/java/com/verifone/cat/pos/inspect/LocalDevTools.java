package com.verifone.cat.pos.inspect;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.verifone.cat.pos.driver.PlaywrightHome;

/**
 * The register's browser, played by Playwright's local headless Chromium with a DevTools port on loopback: what
 * the screencast tests attach to. Never a rig. {@link #openOrNull} is null when Chromium is not installed for
 * Playwright 1.49, so the test skips (as {@code FakePosBrowserTest} does) and never downloads one.
 */
public final class LocalDevTools implements AutoCloseable {

    public static final String INSTALL_COMMAND =
            "java -cp \"target\\lib\\*\" com.microsoft.playwright.CLI install chromium";

    /** A page with a button that counts presses and a box that takes keys. */
    public static final String TEST_PAGE = """
            <!doctype html><html><head><title>screen test</title>
            <style>body{margin:0} button{position:absolute;left:100px;top:80px;width:200px;height:60px}
            input{position:absolute;left:100px;top:200px;width:200px;height:30px}</style></head>
            <body><button id="press" onclick="this.dataset.n=(+(this.dataset.n||0)+1)">press</button>
            <input id="typed"></body></html>
            """;

    private final Playwright playwright;
    private final Browser browser;
    private final Page page;
    private final int port;

    private LocalDevTools(Playwright playwright, Browser browser, Page page, int port) {
        this.playwright = playwright;
        this.browser = browser;
        this.page = page;
        this.port = port;
    }

    public static LocalDevTools openOrNull() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        Playwright playwright = PlaywrightHome.create();
        Browser browser;
        try {
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setArgs(List.of("--remote-debugging-port=" + port)));
        } catch (PlaywrightException missing) {
            playwright.close();
            return null;
        }
        Page page = browser.newPage();
        page.setContent(TEST_PAGE);
        return new LocalDevTools(playwright, browser, page, port);
    }

    /** The DevTools endpoint, as a rig's register gives it. */
    public String cdpUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port;
    }

    /** The page, through Playwright, to check what the screencast's input did. */
    public Page page() {
        return page;
    }

    @Override
    public void close() {
        try {
            browser.close();
        } finally {
            playwright.close();
        }
    }
}
