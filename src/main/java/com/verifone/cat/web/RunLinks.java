package com.verifone.cat.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Where a drawn run's pieces lead: the live run on the Run page, or a past run opened from Results. The board,
 * the dialogs, the Validations view and the Log are the same HTML for both (the Python CAT's components take a
 * {@code RunState} and never the service); only these addresses differ, and whether the pieces poll.
 *
 * <p>Every address returned is URL-encoded but not HTML-escaped: callers escape it into an attribute.
 */
interface RunLinks {

    /** Whether the run is live: its pieces poll for changes while it goes. */
    boolean live();

    /** The step dialog, opened on step {@code index} (0-based). */
    String step(int index);

    /** A case's dialog. */
    String caseDetail(String key);

    /** The Receipt Printer's roll. */
    String receipts();

    /** The Validations block on tab {@code tab}. */
    String validations(String tab);

    /** The Validations dialog on tab {@code tab}. */
    default String validationsDialog(String tab) {
        return "/fragments/run/validations?tab=" + enc(tab);
    }

    /** One validation's detail (the {@code index}th of the run's validations). */
    String validation(int index);

    /** The Log page filtered to one simulator's lines. */
    String simLog(String name);

    /** The Log page filtered to one step's lines. */
    String stepLog(String step);

    /** The Log's body fragment (the filters are added as a query). */
    String logBody();

    /** The Log's newest-lines counter fragment, while it is not following. */
    String logCount();

    /** Every line the Log's filters keep, as text (Copy). */
    String logText();

    /** The live run's addresses. */
    static RunLinks live(boolean polling) {
        return new RunLinks() {
            @Override
            public boolean live() {
                return polling;
            }

            @Override
            public String step(int index) {
                return "/fragments/run/step?i=" + index;
            }

            @Override
            public String caseDetail(String key) {
                return "/fragments/run/case?key=" + enc(key);
            }

            @Override
            public String receipts() {
                return "/fragments/run/receipts";
            }

            @Override
            public String validations(String tab) {
                return "/fragments/run/validations/view?tab=" + enc(tab);
            }

            @Override
            public String validation(int index) {
                return "/fragments/run/validation?i=" + index;
            }

            @Override
            public String simLog(String name) {
                return "/run/log?source=" + enc("sim:" + name);
            }

            @Override
            public String stepLog(String step) {
                return "/run/log?step=" + enc(step);
            }

            @Override
            public String logBody() {
                return "/fragments/run/log/body";
            }

            @Override
            public String logCount() {
                return "/fragments/run/log/count";
            }

            @Override
            public String logText() {
                return "/run/log.txt";
            }
        };
    }

    /** A past run's addresses, under {@code /results/run/<id>}. Nothing polls. */
    static RunLinks result(String runId) {
        String base = "/results/run/" + enc(runId);
        return new RunLinks() {
            @Override
            public boolean live() {
                return false;
            }

            @Override
            public String step(int index) {
                return base + "/step?i=" + index;
            }

            @Override
            public String caseDetail(String key) {
                return base + "/case?key=" + enc(key);
            }

            @Override
            public String receipts() {
                return base + "/receipts";
            }

            @Override
            public String validations(String tab) {
                return base + "/validations?tab=" + enc(tab);
            }

            @Override
            public String validation(int index) {
                return base + "/validation?i=" + index;
            }

            @Override
            public String simLog(String name) {
                return base + "?tab=log&source=" + enc("sim:" + name);
            }

            @Override
            public String stepLog(String step) {
                return base + "?tab=log&step=" + enc(step);
            }

            @Override
            public String logBody() {
                return base + "/log/body";
            }

            @Override
            public String logCount() {
                return base + "/log/count";
            }

            @Override
            public String logText() {
                return base + "/log.txt";
            }
        };
    }

    /** A query value, URL-encoded. */
    static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
