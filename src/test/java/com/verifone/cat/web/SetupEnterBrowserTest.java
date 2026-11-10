package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.verifone.cat.pos.driver.PlaywrightHome;
import com.verifone.cat.preflight.Result;
import com.verifone.cat.setup.ManualField;
import com.verifone.cat.setup.SetupAudit;
import com.verifone.cat.setup.SetupEnv;
import com.verifone.cat.setup.SetupItem;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

/**
 * Enter in a Setup card's field saves the card, as pressing its Save button does. Drives Playwright's Chromium
 * against the Setup routes with one synthetic sign-in item; skipped when Chromium is not installed. Nothing here
 * touches the workstation: the saver only records what it was given.
 */
class SetupEnterBrowserTest {

    @Test
    void enterInAFieldSavesTheCard() throws Exception {
        Map<String, String> saved = new ConcurrentHashMap<>();
        SetupItem item = new SetupItem("login", "A sign-in", true, false, true, "type it",
                env -> saved.isEmpty() ? Result.failed("login", "not saved") : Result.passed("login", "saved"))
                .withFields(List.of(new ManualField("username", "Username", false),
                        new ManualField("password", "Password", true)),
                        (env, values) -> {
                            saved.putAll(values);
                            return Result.passed("login", "saved");
                        }, env -> Map.of());
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        SetupEnv env = new SetupEnv(Map.of(), tmp, tmp, (argv, timeout) -> {
            throw new AssertionError("the Setup page must not run a command in this test");
        });
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.staticFiles.add(files -> {
                files.hostedPath = "/static";
                files.directory = "/public";
                files.location = Location.CLASSPATH;
            });
        });
        SetupRoutes.register(app, new SetupService(new SetupAudit(List.of(item), env)));
        app.start(WebApp.BIND_HOST, 0);
        try (Playwright playwright = PlaywrightHome.create()) {
            Browser browser;
            try {
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions());
            } catch (PlaywrightException missing) {
                Assumptions.abort("Chromium is not installed for Playwright; skipping.");
                return;
            }
            Page page = browser.newPage();
            page.navigate("http://" + WebApp.BIND_HOST + ":" + app.port() + "/setup");
            page.click("text=Check again");
            page.waitForSelector("#setup-login-username");

            page.fill("#setup-login-username", "jdoe");
            page.fill("#setup-login-password", "secret");
            page.press("#setup-login-password", "Enter");
            page.waitForSelector("#setup-panel p.ok");

            assertEquals("jdoe", saved.get("username"));
            assertEquals("secret", saved.get("password"));
            browser.close();
        } finally {
            app.stop();
        }
    }
}
