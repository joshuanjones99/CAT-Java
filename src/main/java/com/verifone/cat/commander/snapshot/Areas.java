package com.verifone.cat.commander.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.verifone.cat.devices.commander.CgiReplies.CgiFunction;

/**
 * Which Commander configuration areas exist, which the snapshot may restore, and how their names map to files.
 *
 * <p>A port of the policy half of {@code petrotest.commander_snapshot}: {@code areas}, {@code exclusion},
 * {@code EXCLUDED}, {@code RESTORABLE}, {@code site_areas}, the file-name encoding and {@code configuration_files}.
 * The tables are the Python's, decided from the names and the product's own FunctionDisplay text (2026-10-15).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Areas {

    /** Writing the snapshot's file for an area: a name that is not file-name safe is percent-encoded. */
    static final String XML = ".xml";

    /** A bare {@code fepcfg} and its kin answer only per FEP ({@code &fep=<name>}). */
    public static final Set<String> PER_FEP = Set.of("fepcfg", "fepcardcfg", "fepcardtypecfg");

    /** Views for one register that fault when the Commander has no such register. */
    public static final Set<String> PER_REGISTER = Set.of("scoregistercfg");

    /** NAXML maintenance datasets in the order one may refer to another: items first. */
    public static final List<String> MAINTENANCE_ORDER = List.of("Item", "ItemList", "Combo", "MixMatch");

    /** The reason {@link #exclusion} gives for an area nobody has reviewed. */
    public static final String UNREVIEWED = "unreviewed: not in EXCLUDED or RESTORABLE (commander_snapshot.py); "
            + "review it before it is ever restored";

    /**
     * Base commands that are never restored, and why. Keyed by the base command, so a parameterised form shares its
     * base's decision. Commander Connect and the cloud, the network, the clock and live state, users and security.
     */
    public static final Map<String, String> EXCLUDED = map(
            "cloudconnectprofile", "Commander Connect environment (PROD/CST/QAT/DEV); "
                    + "`petrotest commander-connect` owns it, and manager is refused it (HANDOFF.md §1.148)",
            "cloudagentprop", "commander console cloud agent: the cloud host URL and service id "
                    + "this site reports as (site identity; base re-imports its own copy every run)",
            "cdmagentprop", "CDM cloud agent properties: the site's cloud device-management identity",
            "supportinfo", "storeServiceID, the site's service id, which Commander Connect "
                    + "onboarding sets (site identity; base re-imports its own copy every run)",
            "vhqcfg", "VHQ (Verifone HQ) management server settings: site management identity",
            "dealercfg", "the EPS dealer record, set at install (site identity); "
                    + "no configurations/ folder imports it",
            "networkcfg", "the Commander's IP, routes and LAN settings (Update Network Settings)",
            "networkcardcfg", "the primary network card's configuration (LAN)",
            "ntpcfg", "network time servers (site infrastructure, not test configuration)",
            "securitylogservercfg", "the security log server's IP address (site infrastructure)",
            "mosecuritylogservercfg", "the Major Oil security log server's address (site infrastructure)",
            "datetime", "the Commander's clock and time zone: restoring a captured time would "
                    + "set the clock back",
            "mainttotalizers", "fuel totalizers (meter readings): live state, not configuration",
            "maintfprht", "fueling point running hose totals (its funcList display): live meter state, "
                    + "not configuration (reviewed 2026-10-15)",
            "reportstatus", "the manager's report review status: live state; its view faults (2026-10-15)",
            "mwscashmovementreport", "MWS cash movement info: a report, not configuration; "
                    + "its view faults (2026-10-15)",
            "useradmin", "users and their passwords",
            "challengequestions", "users' challenge answers (`petrotest.upgrade.psi_init`)",
            "securityctrlcfg", "password policy and lockout: a restore can expire or lock out "
                    + "manager (`CommanderPasswordExpired`)",
            "employeecfg", "employees and their passwords/PINs; no configurations/ folder imports it",
            "roleadmin", "roles and the functions they grant: a bad restore can strip manager's "
                    + "own rights; base re-imports its own copy every run",
            "ptpecfg", "point-to-point encryption settings (payment security); "
                    + "no configurations/ folder imports it");

    /**
     * Areas reviewed as test configuration, restored when they differ from the snapshot. Each is imported by some
     * petroapps {@code configurations/} folder or is plainly a store, POS, fuel or payment setting.
     */
    public static final Set<String> RESTORABLE = Set.copyOf(Arrays.asList(
            // Fuel and forecourt
            "fuelcfg", "fuelprices", "fueltaxex", "fueltaxexemptcfg", "fueltaxexemptreceiptcfg",
            "fuelflattaxcfg", "rcfcfg", "FPDcfg", "tlssite",
            // DCRs
            "pscdcrcfg", "pscdcrkeycfg", "dcrheadercfg", "dcrtrailercfg", "dcrmessagecfg",
            "dcridlescreencfg", "dcrattendedidlescreencfg", "dcrunattendedidlescreencfg",
            "dcrpositioncfg", "dcrsiteparameterscfg", "dcrunattendedcfg", "outdoortavecfg",
            // Items, departments, promotions
            "PLUs", "plucfg", "plupromocfg", "Maintenance", "departmentcfg", "categorycfg",
            "couponfamcfg", "couponfamilycfg", "couponsiteparameterscfg", "discountdenomcfg",
            "popcfg", "popcouponmsgcfg", "popdefinitioncfg", "popdiscountcfg",
            "popsiteparameterscfg", "smartsuggestioncfg", "smartsuggestiongeneralsettingscfg",
            "foodservicecfg", "foodserviceplumapcfg", "thirdpartyproductprovidercfg",
            // Sales, POS and its screens
            "salescfg", "salesconfigitemscfg", "salesloginlogoutmsgcfg", "poscfg", "possecurity",
            // "Update POS Payment Configuration" (reviewed 2026-10-15): holds forceAEOD (HANDOFF.md §1.130).
            "pospaymentconfig",
            "softkeycfg", "softkeytypesecuritycfg", "registercfg", "registercontrolscfg",
            "posscreencfg", "csrscreencfg", "screencfgv2", "panelcfg", "menucfg", "empprefcfg",
            "restrictionscfg", "agevalidationcfg", "ageservice", "bluelawcfg", "policycfg",
            "taxratecfg", "taxexemptcfg", "customertaxinfo", "fiscalreceiptcfg", "currencycfg",
            "feecfg", "tipcfg", "charitycfg", "closelanecfg", "cashiertrackingcfg",
            "sapphireprop",
            // Receipts and branding
            "bannercfg", "slogancfg", "logocfg", "imagecfg", "checklinecfg",
            // Payment, FEPs, loyalty, EMV
            "paymentcfg", "mopcfg", "netposcfg", "fepcfg", "fepcardcfg", "fepcardtypecfg",
            "vipercfg", "emvcfg", "emvinit", "quickchipcfg", "triggerpullcfg", "epsprepaidcfg",
            "pinpadmsgcfg", "inhouseacctcfg", "ifsfcfg", "loyaltyglobalcfg", "loyaltycardcfg",
            "loyaltycardtypecfg", "loyaltymobilevascfg", "mobilecfg",
            // Devices at the site
            "carwashcfg", "cwpaypointcfg", "vendingmachinecfg", "cashaccsite", "cashrecyclercfg",
            "aptglobalcfg", "aptterminalcfg", "scoglobalcfg", "scocategorycfg", "scoregistercfg",
            "scoscreencfg", "vistagroupcfg", "vistaitemsetcfg", "vistaitemsubsetcfg",
            "vistaterminalcfg", "vistaterminalpreviewcfg", "manageddevicecfg", "managedmodulecfg",
            // Reports, events, store details
            "reportcfg", "reportlist", "grouplist", "eventcfg", "mainttelephone", "maintpostal"));

    private Areas() {}

    /** A map literal in declaration order, unmodifiable. */
    static Map<String, String> map(String... keysAndValues) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }

    /** The order the base import sends files in: case-insensitive, as a Windows path sorts. */
    public static final Comparator<String> ORDER = Comparator
            .comparing((String a) -> a.toLowerCase(Locale.ROOT))
            .thenComparing(Comparator.naturalOrder());

    /** {@code fepcfg&fep=buypass} -> {@code fepcfg}: the command a parameterised area is a form of. */
    public static String baseArea(String area) {
        int amp = area.indexOf('&');
        return amp < 0 ? area : area.substring(0, amp);
    }

    /**
     * The snapshot's file name for {@code area}: every byte outside {@code A-Za-z0-9._-~} percent-encoded, so
     * {@code fepcfg&fep=buypass} is {@code fepcfg%26fep%3Dbuypass.xml}.
     */
    public static String areaFile(String area) {
        return pyQuote(area) + XML;
    }

    /** {@link #areaFile}'s inverse. */
    public static String areaOfFile(String name) {
        String stem = name.endsWith(XML) ? name.substring(0, name.length() - XML.length()) : name;
        return pyUnquote(stem);
    }

    static String pyQuote(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return out.toString();
    }

    static String pyUnquote(String text) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length() && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2))) {
                bytes.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] raw = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(raw, 0, raw.length);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /**
     * Area -> the petroapps {@code configurations/} folders whose import sends it. The area is the file name without
     * its last four characters, as the import builds the command. Only each folder's own files count.
     *
     * @return empty when {@code root} is not a directory
     */
    public static Map<String, List<String>> configurationFiles(Path root) {
        Map<String, List<String>> found = new LinkedHashMap<>();
        if (root == null || !Files.isDirectory(root)) {
            return found;
        }
        for (Path folder : sortedChildren(root, true)) {
            for (Path file : sortedChildren(folder, false)) {
                String name = file.getFileName().toString();
                found.computeIfAbsent(name.substring(0, Math.max(0, name.length() - 4)), k -> new ArrayList<>())
                        .add(folder.getFileName().toString());
            }
        }
        return found;
    }

    private static List<Path> sortedChildren(Path dir, boolean directories) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> directories ? Files.isDirectory(p) : Files.isRegularFile(p)).forEach(out::add);
        } catch (IOException e) {
            return List.of();
        }
        out.sort(Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                .thenComparing(p -> p.getFileName().toString()));
        return out;
    }

    /**
     * Every configuration area: each funcList {@code u<x>} with a {@code v<x>}, plus each parameterised variant of one
     * that a {@code configurations/} file is named after. Sorted in restore order.
     *
     * @param commands    the funcList's command names
     * @param configRoot  petroapps' {@code configurations/}, or null
     */
    public static List<String> areas(Collection<String> commands, Path configRoot) {
        Set<String> all = new TreeSet<>(commands);
        Set<String> pairs = new TreeSet<>();
        for (String c : all) {
            if (c.length() > 1 && c.charAt(0) == 'u' && all.contains("v" + c.substring(1))) {
                pairs.add(c.substring(1));
            }
        }
        Set<String> variants = new TreeSet<>();
        if (configRoot != null) {
            for (String name : configurationFiles(configRoot).keySet()) {
                if (name.contains("&") && pairs.contains(baseArea(name))) {
                    variants.add(name);
                }
            }
        }
        Set<String> perFepBare = new TreeSet<>(PER_FEP);
        perFepBare.retainAll(pairs);
        Set<String> out = new TreeSet<>(ORDER);
        for (String p : pairs) {
            if (!perFepBare.contains(p)) {
                out.add(p);
            }
        }
        out.addAll(variants);
        return List.copyOf(out);
    }

    /** {@link #areas} over the funcList's {@link CgiFunction}s. */
    public static List<String> areasOf(List<CgiFunction> functions, Path configRoot) {
        return areas(functions.stream().map(CgiFunction::cmd).toList(), configRoot);
    }

    /**
     * Why {@code area} must never be restored, or {@code null} when it may be.
     */
    public static String exclusion(String area) {
        if ("Maintenance".equals(area)) {
            return "requires a dataset parameter; restore its parameterised areas instead";
        }
        if ("PLUs".equals(area)) {
            return "the PLU database behind a paged PLUSelect query, not a configuration view; "
                    + "items come back with the Maintenance datasets";
        }
        String base = baseArea(area);
        if (EXCLUDED.containsKey(base)) {
            return EXCLUDED.get(base);
        }
        if (RESTORABLE.contains(base)) {
            return null;
        }
        return UNREVIEWED;
    }

    /** A FEP's allowed views and whether it is one the Commander has at all. */
    public record SiteAreas(List<String> kept, Map<String, String> dropped) {}

    /**
     * {@code names} as this Commander can answer them, and each name dropped with its reason. A bare per-FEP area is
     * dropped. With {@code feps} known, a {@code &fep=} area is kept only for a FEP the Commander has and a view it
     * allows, and every view a FEP allows is added.
     *
     * @param feps FEP name -> its {@code v}-views, or null when {@code vfepdetails} could not be read
     */
    public static SiteAreas siteAreas(Collection<String> names, Map<String, Set<String>> feps) {
        List<String> kept = new ArrayList<>();
        Map<String, String> dropped = new LinkedHashMap<>();
        for (String area : names) {
            int amp = area.indexOf('&');
            String base = amp < 0 ? area : area.substring(0, amp);
            String param = amp < 0 ? "" : area.substring(amp + 1);
            if (PER_FEP.contains(base) && param.isEmpty()) {
                dropped.put(area, "answers only per FEP (&fep=<name>)");
                continue;
            }
            if (PER_FEP.contains(base) && feps != null) {
                String fep = param.startsWith("fep=") ? param.substring(4) : param;
                if (!feps.containsKey(fep)) {
                    dropped.put(area, "this Commander has no FEP " + fep + " (vfepdetails)");
                    continue;
                }
                if (!feps.get(fep).contains("v" + base)) {
                    dropped.put(area, "FEP " + fep + " allows no v" + base + " (vfepdetails)");
                    continue;
                }
            }
            kept.add(area);
        }
        if (feps != null) {
            for (Map.Entry<String, Set<String>> entry : feps.entrySet()) {
                for (String view : entry.getValue()) {
                    String area = view.substring(1) + "&fep=" + entry.getKey();
                    if (PER_FEP.contains(view.substring(1)) && !kept.contains(area)) {
                        kept.add(area);
                    }
                }
            }
        }
        kept.sort(ORDER);
        return new SiteAreas(List.copyOf(new LinkedHashSet<>(kept)), dropped);
    }

    /**
     * Whether a snapshot entry names an area no capture can get: a bare per-FEP view, or a per-register view the
     * Commander lacks, recorded as failed.
     */
    static boolean neverAnswers(String area, Map<String, ?> failed) {
        int amp = area.indexOf('&');
        String base = amp < 0 ? area : area.substring(0, amp);
        String param = amp < 0 ? "" : area.substring(amp + 1);
        if (PER_FEP.contains(base) && param.isEmpty()) {
            return true;
        }
        return PER_REGISTER.contains(base) && !param.isEmpty() && failed.containsKey(area);
    }

}
