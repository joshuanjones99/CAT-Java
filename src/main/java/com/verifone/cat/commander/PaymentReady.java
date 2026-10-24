package com.verifone.cat.commander;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * {@code payment-ready}: wait for the Commander's EPS to be ready for card sales. A port of
 * {@code petrotest.payment_ready}.
 *
 * <p>After a Commander reboot the lab's first card sales met "PINpad unavailable" and "Unable to login to
 * EPS Network" for about ten minutes, although step 4 had finished (HANDOFF.md section 1.159). The
 * Commander's {@code vpaymentdiagnostics} view names what those messages are about: the Payment
 * Controller's {@code status}, each FEP's {@code connectionStatus} (the primary is the network the EPS
 * logs in to), and each pinpad's {@code connectionStatus}. Ready is the controller up, every primary FEP
 * connected and every pinpad connected; a secondary FEP never holds the wait. Step 4 runs this last, and
 * a Commander still not ready after the budget is a WARN there, not a failure.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python read the view on a ready Commander only.
 */
public final class PaymentReady {

    /** How long step 4 waits for the EPS: the lab's errors lasted about ten minutes. */
    public static final Duration READY_TIMEOUT = Duration.ofSeconds(900);

    /** Between two reads of the view. */
    public static final Duration READY_POLL = Duration.ofSeconds(10);

    public static final String COMMAND = "vpaymentdiagnostics";

    private PaymentReady() {}

    /** One FEP's row: whether it is the primary, and whether it is connected. */
    public record Fep(boolean primary, boolean connected) {}

    /**
     * One read of {@code vpaymentdiagnostics}.
     *
     * @param controller the Payment Controller's status, or {@code null} when the view names none
     * @param feps       FEP name to its row, in the view's order
     * @param pinpads    POP id to connected, in the view's order
     */
    public record Readiness(Boolean controller, Map<String, Fep> feps, Map<String, Boolean> pinpads) {

        public Readiness {
            feps = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(feps));
            pinpads = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pinpads));
        }

        public boolean ready() {
            if (!Boolean.TRUE.equals(controller)) {
                return false;
            }
            for (Fep fep : feps.values()) {
                if (fep.primary() && !fep.connected()) {
                    return false;
                }
            }
            return pinpads.values().stream().allMatch(Boolean::booleanValue);
        }

        public String summary() {
            List<String> parts = new ArrayList<>();
            String state = controller == null ? "absent" : (controller ? "up" : "down");
            parts.add("payment controller " + state);
            feps.forEach((name, fep) -> {
                if (fep.primary()) {
                    parts.add(name + (fep.connected() ? " connected" : " not connected"));
                }
            });
            pinpads.forEach((pop, up) -> parts.add("POP " + pop + (up ? " connected" : " not connected")));
            return String.join(", ", parts);
        }
    }

    /** How a wait ended: whether ready, and the last read (or {@code null} when none answered). */
    public record Outcome(boolean ready, Readiness last) {}

    private static boolean isTrue(String text) {
        return text != null && text.strip().toLowerCase(Locale.ROOT).equals("true");
    }

    private static String local(Node node) {
        String name = node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /**
     * The view's reply as {@link Readiness}. Elements by local name, as the Python's.
     *
     * @throws IllegalArgumentException the reply is not XML
     */
    public static Readiness parse(String xml) {
        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
                    throw e; // raised to the caller, not printed to stderr
                }
            });
            root = builder.parse(new InputSource(new StringReader(xml.strip()))).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("vpaymentdiagnostics reply is not XML: " + e.getMessage(), e);
        }
        Boolean controller = null;
        Map<String, Fep> feps = new LinkedHashMap<>();
        Map<String, Boolean> pinpads = new LinkedHashMap<>();
        List<Element> all = new ArrayList<>();
        all.add(root);
        NodeList descendants = root.getElementsByTagName("*");
        for (int i = 0; i < descendants.getLength(); i++) {
            all.add((Element) descendants.item(i));
        }
        for (Element element : all) {
            String name = local(element);
            Map<String, String> status = new LinkedHashMap<>();
            for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    status.put(local(child), child.getTextContent());
                }
            }
            if (name.equals("deviceDetail") && "Payment Controller".equals(element.getAttribute("deviceName"))) {
                controller = isTrue(status.get("status"));
            } else if (name.equals("fepDetail")) {
                String fep = element.hasAttribute("fepName") ? element.getAttribute("fepName") : "?";
                feps.put(fep, new Fep(isTrue(element.getAttribute("isPrimary")), isTrue(status.get("connectionStatus"))));
            } else if (name.equals("pinpadDetail")) {
                String pop = element.hasAttribute("popID") ? element.getAttribute("popID") : "?";
                pinpads.put(pop, isTrue(status.get("connectionStatus")));
            }
        }
        return new Readiness(controller, feps, pinpads);
    }

    /**
     * Reads the view ({@code read} returns its XML) until it says ready, up to {@code timeout}. Each change of
     * state is said, so a slow EPS leaves a timeline. A read that fails (the Commander still coming up) counts
     * as not ready.
     */
    public static Outcome waitReady(Supplier<String> read, Duration timeout, Duration poll, Consumer<String> say,
            Pauser pauser) {
        long started = System.nanoTime();
        Readiness[] last = {null};
        String[] summary = {null};
        boolean ok = pauser.until(() -> {
            Readiness now;
            String text;
            try {
                now = parse(read.get());
                text = now.summary();
            } catch (RuntimeException e) { // not up yet: refused, LoginRequired, a fault
                now = null;
                text = "no answer (" + e.getClass().getSimpleName() + ")";
            }
            if (!text.equals(summary[0])) {
                long seconds = Math.round((System.nanoTime() - started) / 1_000_000_000.0);
                say.accept("payment-ready: " + seconds + "s: " + text);
                summary[0] = text;
            }
            last[0] = now;
            return now != null && now.ready();
        }, timeout, poll);
        return new Outcome(ok, last[0]);
    }

    /**
     * {@code payment-ready}: {@code true} once ready; {@code false}, with what was not, after {@code timeout}.
     * Each read is one client from {@code clients}, closed after it.
     */
    public static boolean run(Supplier<CommanderClient> clients, Duration timeout, Duration poll, Pauser pauser,
            Say say) {
        Supplier<String> read = () -> {
            CommanderClient client = clients.get();
            try {
                return client.sendCommand(COMMAND);
            } finally {
                client.close();
            }
        };
        Outcome outcome = waitReady(read, timeout, poll, say::out, pauser);
        if (outcome.ready()) {
            say.out("payment-ready: ready (" + (outcome.last() == null ? "" : outcome.last().summary()) + ")");
        } else {
            String detail = outcome.last() == null ? "no answer" : outcome.last().summary();
            say.err("payment-ready: NOT ready after " + timeout.toSeconds() + "s: " + detail);
        }
        return outcome.ready();
    }

    /** {@link #run} against the rig's Commander with the Python's budget. */
    public static boolean run(CommanderAccess access, Say say) {
        return run(access::client, READY_TIMEOUT, READY_POLL, Pauser.REAL, say);
    }
}
