package com.verifone.cat.web;

import java.util.Optional;

import com.verifone.cat.sims.SimEntry;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

/**
 * The Launch Simulators routes: the page, its polled panel, a simulator's Log, and the POSTs
 * that tick rows, start them and stop them.
 *
 * <p>Every POST requires the {@code HX-Request} header, as {@code POST /keys} does, so a page
 * in the operator's browser cannot start or stop simulators with a cross-site form post.
 * Unknown row names are refused, never echoed.
 */
final class SimulatorsRoutes {

    private SimulatorsRoutes() {}

    static void register(Javalin app, SimulatorsService sims) {
        app.get("/simulators", ctx -> {
            // What a run would start, each time the page opens (the Python's _default_ticks).
            sims.resetTicks();
            sims.probe();
            ctx.html(Layout.page(Section.SIMULATORS, "Launch Simulators", SimulatorsPage.body(sims)));
        });
        app.get("/fragments/simulators", ctx -> ctx.html(SimulatorsPage.panel(sims)));
        app.get("/fragments/simulators/log", ctx -> {
            if (SimulatorsService.LAUNCH_LOG.equals(ctx.queryParam("name"))) {
                ctx.html(SimulatorsPage.log(sims, SimulatorsService.LAUNCH_LOG));
                return;
            }
            Optional<SimEntry> entry = sims.row(ctx.queryParam("name"));
            if (entry.isEmpty()) {
                ctx.status(HttpStatus.NOT_FOUND).result("No simulator with that name.");
                return;
            }
            ctx.html(SimulatorsPage.log(sims, entry.get()));
        });
        // Every action answers with the panel as it is after it.
        Requests.Actions actions = Requests.actions(app, "Simulator buttons come", () -> SimulatorsPage.panel(sims));
        actions.on("/simulators/cancel", ctx -> sims.cancel());
        actions.on("/simulators/toggle", ctx -> sims.toggle(ctx.queryParam("name")));
        actions.on("/simulators/select", ctx -> sims.selectAll("all".equals(ctx.queryParam("mode"))));
        actions.on("/simulators/start", ctx -> sims.start());
        actions.on("/simulators/stop", ctx -> sims.stop());
    }
}
