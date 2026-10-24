package com.verifone.cat.web;

import java.util.List;
import java.util.function.Supplier;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.posshim.PosShimClient;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;

/**
 * The web UI: one page showing the POS through pos-shim, and its key buttons.
 *
 * <p>Like the Python CAT's {@code catt web}, it serves localhost only: {@link #start}
 * binds 127.0.0.1, so nothing else on the bench network can press keys on the POS.
 */
public final class WebApp {

    /** The host the UI binds. Never 0.0.0.0: these buttons drive a live register. */
    public static final String BIND_HOST = "127.0.0.1";

    private WebApp() {}

    public static Javalin create(PosShimClient shim, String title) {
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.staticFiles.add(files -> {
                files.hostedPath = "/static";
                files.directory = "/public";
                files.location = Location.CLASSPATH;
            });
        });

        app.get("/", ctx -> ctx.html(Pages.page(title, shim.baseUri().toString())));
        app.get("/fragments/health", ctx -> fragment(ctx, () -> Pages.health(shim.health())));
        app.get("/fragments/screen", ctx -> fragment(ctx, () -> Pages.screen(shim.screen())));
        app.get("/fragments/keys", ctx -> fragment(ctx, () -> Pages.keys(shim.supportedKeys())));
        app.post("/keys", ctx -> {
            // htmx sends HX-Request on every call. A cross-site form post cannot set a
            // custom header, so requiring it stops another page in the operator's
            // browser from pressing keys through this server.
            if (!"true".equals(ctx.header("HX-Request"))) {
                ctx.status(HttpStatus.FORBIDDEN).result("Key presses come from the CAT-Java page only.");
                return;
            }
            String key = ctx.formParam("key");
            if (key == null || key.isBlank()) {
                ctx.status(HttpStatus.BAD_REQUEST).result("No key named.");
                return;
            }
            fragment(ctx, () -> Pages.keyResult(shim.pressKeys(List.of(key))));
        });
        return app;
    }

    public static Javalin start(PosShimClient shim, String title, int port) {
        return create(shim, title).start(BIND_HOST, port);
    }

    private static void fragment(Context ctx, Supplier<String> render) {
        String html;
        try {
            html = render.get();
        } catch (DeviceError e) {
            html = Pages.error(e);
        }
        ctx.html(html);
    }
}
