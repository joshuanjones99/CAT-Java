package com.verifone.cat.pats.runner.handlers.http;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.verifone.cat.pats.runner.handlers.http.LoopbackServer.Answer;
import com.verifone.cat.pats.runner.handlers.http.LoopbackServer.Handler;
import com.verifone.cat.pats.runner.handlers.http.LoopbackServer.Seen;

/**
 * The Commander's CGILink as the lab answers it (docs/commander-config-surface.md), for loopback tests. A command's
 * parameters are the URL's query, or the head of a body; {@code validate} mints a cookie; a command may fault
 * {@code LoginRequired} and {@code OTPRequired} once each before it succeeds, as the Python's {@code _cgilink} does.
 */
final class CgiFake {

    static final String OK_XML = "<?xml version=\"1.0\"?>\n<VFI:Response xmlns:VFI=\"urn:vfi-sapphire:np.domain.2001-07-01\">\n"
            + "</VFI:Response>\n";
    static final String XML = "text/xml; charset=UTF-8";
    static final String LOGIN_FAULT = "<VFI:Fault><faultCode>CGIPortal.LoginRequired</faultCode></VFI:Fault>";
    static final String OTP_FAULT = "<VFI:Fault><faultCode>CGIPortal.OTPRequired</faultCode></VFI:Fault>";
    static final String BUSY = "<error>Unable to process your request. Please try again later.</error>";

    private CgiFake() {
    }

    /** The parameters of a request: its query, or the head of its body when the query is empty. */
    static Map<String, String> params(Seen seen) {
        if (!seen.query().isEmpty() || seen.body().isEmpty()) {
            return seen.query();
        }
        return LoopbackServer.parseQuery(seen.body().split("\n\n", 2)[0]);
    }

    /** A CGILink that faults LoginRequired and OTPRequired once each (when asked), then answers OK. */
    static Handler standard(boolean loginOnce, boolean otpOnce) {
        AtomicInteger cookies = new AtomicInteger();
        boolean[] login = {loginOnce};
        boolean[] otp = {otpOnce};
        return seen -> {
            Map<String, String> params = params(seen);
            String cmd = params.getOrDefault("cmd", "");
            if (cmd.equals("validate")) {
                return new Answer(200, XML, String.format("<credential><cookie>c%030d</cookie></credential>",
                        cookies.incrementAndGet()));
            }
            if (cmd.equals("vsupportinfo")) {
                return new Answer(200, XML, "<support><storeServiceID> 1234567 </storeServiceID></support>");
            }
            if (login[0]) {
                login[0] = false;
                return new Answer(200, XML, LOGIN_FAULT);
            }
            if (otp[0] && !params.containsKey("otp")) {
                otp[0] = false;
                return new Answer(200, XML, OTP_FAULT);
            }
            return new Answer(200, XML, OK_XML);
        };
    }

    /** A CGILink that answers every command busy, and {@code validate} with a cookie. */
    static Handler alwaysBusy() {
        return seen -> params(seen).getOrDefault("cmd", "").equals("validate")
                ? new Answer(200, XML, "<cookie>abc</cookie>")
                : new Answer(200, XML, BUSY);
    }
}
