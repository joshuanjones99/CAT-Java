package com.verifone.cat.pats.runner.handlers.sale;

import java.util.Optional;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.handlers.validation.ValidationFamily;

/**
 * The case's last TLog, as REFUND_WITH_TRANS_MAP reads a STAN from it when the stored receipt has none. Ported from
 * the Python {@code run.last_tlog} in {@code petrotest/pats/runner/sale.py::_refund_with_trans_map}.
 *
 * <p>The Python's {@code run.last_tlog} is the TLog the case's last {@code VALIDATE_TRANSACTION} read
 * ({@code validation.py}: {@code run.last_tlog = actual}), not a fresh read of the Commander: a case that validated no
 * TLog has none, and the refund goes on to the stored TRAN#, else VOID_TICKET. {@link #VALIDATED} is that: it reads
 * the TLog the validation family left on the case ({@link ValidationFamily#LAST_TLOG}), and its STAN is the first
 * {@code .//trPaylines/trPayline/trpCardInfo}'s {@code trpcBatchNr} and {@code trpcSeqNr}, joined, as the Python's
 * {@code (findtext("trpcBatchNr") or "") + (findtext("trpcSeqNr") or "") or None}.
 *
 * <p>Run f6b5c0596966 (2026-10-28): PETROCORE-36853's #31 failed on {@link #UNAVAILABLE}, then the production source,
 * which threw where the Python finds no TLog and carries on with the TRAN#. {@link #UNAVAILABLE} stays for a context
 * that must not read one.
 */
@FunctionalInterface
public interface LastTlog {

    /**
     * The STAN the case's last TLog's card info gives, or empty when the case has read no TLog or the TLog has no card
     * info.
     */
    Optional<String> stan(CaseRun run);

    /** The TLog the case's last {@code VALIDATE_TRANSACTION} read, as the Python's {@code run.last_tlog}. */
    LastTlog VALIDATED = run -> run.scratch(ValidationFamily.LAST_TLOG).flatMap(LastTlog::stanOf);

    /** A source that is not wired: every read throws {@link DeviceUnavailable}. */
    LastTlog UNAVAILABLE = run -> {
        throw new DeviceUnavailable("the last TLog", "no TLog source is wired to this sale context");
    };

    /** The STAN in a TLog: the first {@code .//trPaylines/trPayline/trpCardInfo}'s batch and sequence numbers. */
    static Optional<String> stanOf(Element tlog) {
        Element info = first(tlog, "trPaylines", "trPayline", "trpCardInfo");
        if (info == null) {
            return Optional.empty();
        }
        String stan = childText(info, "trpcBatchNr") + childText(info, "trpcSeqNr");
        return stan.isEmpty() ? Optional.empty() : Optional.of(stan);
    }

    /**
     * The first element at {@code .//top/path...} in document order, as ElementTree's {@code find}: a {@code top} at
     * any depth under {@code root}, then direct children down the path.
     */
    private static Element first(Element root, String top, String... path) {
        NodeList tops = root.getElementsByTagName(top);
        for (int i = 0; i < tops.getLength(); i++) {
            Element found = down((Element) tops.item(i), path, 0);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Element down(Element at, String[] path, int depth) {
        if (depth == path.length) {
            return at;
        }
        for (Node child = at.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && path[depth].equals(element.getTagName())) {
                Element found = down(element, path, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** {@code findtext(name) or ""}: the first direct child's text, or "". */
    private static String childText(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getTagName())) {
                return element.getTextContent();
            }
        }
        return "";
    }
}
