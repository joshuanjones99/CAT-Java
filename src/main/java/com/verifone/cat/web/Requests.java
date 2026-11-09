package com.verifone.cat.web;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/** What every page's routes ask of a request: that it came from this site's own pages, and its parameters. */
final class Requests {

    private Requests() {}

    /** Whether htmx sent the request. A cross-site form post cannot set a custom header, so it never has this one. */
    static boolean isHtmx(Context ctx) {
        return "true".equals(ctx.header("HX-Request"));
    }

    /**
     * True if the request came from htmx on this site. Otherwise it is refused with {@code 403} and
     * "{@code what} from the CAT-Java page only.", and nothing runs: another page in the operator's browser
     * cannot press a button here with a cross-site form post.
     *
     * @param what the subject and verb of the refusal, such as {@code "Run actions come"}
     */
    static boolean fromPage(Context ctx, String what) {
        if (isHtmx(ctx)) {
            return true;
        }
        ctx.status(HttpStatus.FORBIDDEN).result(what + " from the CAT-Java page only.");
        return false;
    }

    /** A query parameter, or "" when the request has none. */
    static String query(Context ctx, String name) {
        String value = ctx.queryParam(name);
        return value == null ? "" : value;
    }

    /** A form field, or "" when the request has none. */
    static String form(Context ctx, String name) {
        String value = ctx.formParam(name);
        return value == null ? "" : value;
    }
}
