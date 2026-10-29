package com.verifone.cat.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * The Authoring routes: the page, the Feature editor's save, and one POST per action. Each POST first saves the
 * draft the editor sent (when it is the selected case's), then does its action, then returns the whole workbench
 * (or, for the editor, its diagnostics). A refusal is the page's message, never an error status.
 *
 * <p>Every POST requires the {@code HX-Request} header, as {@code POST /keys} and the Launch Simulators routes do, so
 * a page in the operator's browser cannot change a session with a cross-site form post. Unknown values are refused
 * or ignored, never echoed.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class AuthoringRoutes {

    private AuthoringRoutes() {}

    static void register(Javalin app, AuthoringService service) {
        app.get("/authoring", ctx -> ctx.html(Layout.page(Section.AUTHORING, "Authoring",
                service.view(AuthoringPage::workbench))));

        app.post("/authoring/feature", ctx -> {
            if (!fromThisPage(ctx)) {
                return;
            }
            service.act(() -> service.saveDraft(ctx.formParam("case"), ctx.formParam("text")));
            ctx.html(service.view(AuthoringPage::checkFragment));
        });
        app.post("/authoring/ticket", ctx -> reply(ctx, service,
                () -> service.setTicket(ctx.formParam("ticket"))));
        app.post("/authoring/ingest", ctx -> reply(ctx, service,
                () -> service.ingest(ctx.formParam("spec"))));
        app.post("/authoring/case/new", ctx -> reply(ctx, service,
                () -> service.newCase(ctx.formParam("name"))));
        app.post("/authoring/case/rename", ctx -> reply(ctx, service,
                () -> service.renameCase(ctx.formParam("name"))));
        app.post("/authoring/case/move", ctx -> reply(ctx, service,
                () -> service.moveCase(number(ctx.queryParam("delta"), 0))));
        app.post("/authoring/case/remove", ctx -> reply(ctx, service, service::removeCase));
        app.post("/authoring/session/new", ctx -> reply(ctx, service, service::newSession));
        app.post("/authoring/select", ctx -> reply(ctx, service,
                () -> service.select(number(ctx.queryParam("index"), -1))));
        app.post("/authoring/tab", ctx -> reply(ctx, service,
                () -> service.selectTab(ctx.queryParam("name"))));
        app.post("/authoring/insert", ctx -> reply(ctx, service,
                () -> service.insertStep(ctx.queryParam("example"))));
        app.post("/authoring/approve", ctx -> reply(ctx, service, service::approve));
        app.post("/authoring/publish", ctx -> reply(ctx, service, service::publish));
        app.post("/authoring/steps", ctx -> {
            if (!fromThisPage(ctx)) {
                return;
            }
            service.act(() -> service.filterSteps(ctx.formParam("filter")));
            ctx.html(service.view(AuthoringPage::steps));
        });
        app.post("/authoring/draft", ctx -> reply(ctx, service, service::draft));
        app.post("/authoring/capture", ctx -> reply(ctx, service, service::capture));
        app.post("/authoring/replay", ctx -> reply(ctx, service, service::replay));
        // While a draft or a replay is under way the page asks here; when it has ended, the page reloads.
        app.get("/authoring/poll", ctx -> {
            if (!fromThisPage(ctx)) {
                return;
            }
            boolean waiting = service.view(AuthoringService::waiting);
            if (!waiting) {
                ctx.header("HX-Refresh", "true");
            }
            ctx.result("");
        });
    }

    /** Saves the draft the editor sent, runs {@code action}, and returns the workbench. */
    private static void reply(Context ctx, AuthoringService service, AuthoringService.Action action) {
        if (!fromThisPage(ctx)) {
            return;
        }
        service.act(() -> {
            service.saveDraft(ctx.formParam("case"), ctx.formParam("text"));
            action.run();
        });
        ctx.html(service.view(AuthoringPage::workbench));
    }

    /** An integer from a query value, or {@code fallback} when it is missing or not a number. */
    private static int number(String value, int fallback) {
        try {
            return Integer.parseInt(value.strip());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** True if the request came from htmx on this page. Otherwise it is refused and nothing runs. */
    private static boolean fromThisPage(Context ctx) {
        if ("true".equals(ctx.header("HX-Request"))) {
            return true;
        }
        ctx.status(HttpStatus.FORBIDDEN).result("Authoring buttons come from the CAT-Java page only.");
        return false;
    }
}
