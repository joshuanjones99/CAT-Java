package com.verifone.cat.devices.suspended;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.preflight.Surfaces.UnsettledSuspends;
import com.verifone.cat.rig.Rig;

/**
 * Parked tickets, as the Commander's Day summary counts them: {@code no_parked_transaction}'s source
 * (DESIGN.md 6.2). Ported from the Python CAT's {@code petrotest.devices.suspended}.
 *
 * <p><b>Where this comes from (the Python's HANDOFF.md 1.128).</b> Nothing on the register lists its parked
 * tickets read-only: the pos-shim answers 501, the sales screen shows no count, and pressing SUSPEND at the
 * idle screen recalls a ticket, so a check cannot press it to look. The Commander's period summary
 * ({@code vrubyrept}, {@code reptname=summary}) carries {@code <suspendUnsettled><count>N</count>
 * <amount>X</amount></suspendUnsettled>}. On the lab rig on 2026-10-11 one ticket parked on Reg101 took the
 * Day summary from 0 to 1 unsettled ($18.25), and recalling and voiding it took it back to 0.
 *
 * <p>The count is per period. This reads the Day period ({@link #DAY_PERIOD}), so it covers everything
 * since the last day close. It is one CGILink read, the same {@code vrubyrept} the report cases make.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Run against parsed fixtures and {@code FakeCommander} only.
 */
public final class CommanderSuspends implements Supplier<UnsettledSuspends> {

    /** {@code vtlogpdlist}'s sysid for "Current Day". */
    public static final String DAY_PERIOD = "2";

    private final Supplier<Element> fetch;

    /** @param fetch returns the Commander's Day summary report */
    public CommanderSuspends(Supplier<Element> fetch) {
        this.fetch = Objects.requireNonNull(fetch, "fetch");
    }

    /** The Day summary from {@code commander.host}, one CGILink client per read, bounded by the rig's default wait. */
    public static CommanderSuspends fromRig(Rig rig) {
        String host = rig.commander().host();
        Duration timeout = rig.timeouts().defaultWait();
        return new CommanderSuspends(() -> {
            try (PdkShim shim = new PdkShim(host, timeout, null)) {
                return dayReport(shim);
            }
        });
    }

    /** The report request itself: {@code vrubyrept}, {@code summary}, the Day period, the current file. */
    public static Element dayReport(PdkShim shim) {
        return shim.fetchReport("vrubyrept", "summary", DAY_PERIOD, "current", null, null);
    }

    @Override
    public UnsettledSuspends get() {
        return parseUnsettledSuspends(fetch.get());
    }

    /**
     * {@code suspendUnsettled}'s count and amount out of a {@code vrubyrept} summary, namespaces ignored.
     * Raises {@link DeviceError} when the element is missing or has no count: an answer with no count in it
     * is not zero.
     */
    public static UnsettledSuspends parseUnsettledSuspends(Element summary) {
        Element found = find(summary);
        if (found == null) {
            throw new DeviceError("the Commander's Day summary has no suspendUnsettled element");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        NodeList children = found.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                fields.put(localName(element), element.getTextContent() == null ? "" : element.getTextContent().strip());
            }
        }
        int count;
        try {
            count = Integer.parseInt(Objects.requireNonNull(fields.get("count")));
        } catch (NullPointerException | NumberFormatException e) {
            throw new DeviceError("the Commander's summary has a suspendUnsettled with no count: " + fields, e);
        }
        return new UnsettledSuspends(count, fields.getOrDefault("amount", ""));
    }

    /** The first {@code suspendUnsettled} in document order, the root included. */
    private static Element find(Element element) {
        if ("suspendUnsettled".equals(localName(element))) {
            return element;
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                Element hit = find(child);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** The tag without its prefix or namespace, as the Python's {@code tag.rsplit("}", 1)[-1]}. */
    private static String localName(Element element) {
        String local = element.getLocalName();
        String name = local != null ? local : element.getTagName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}
