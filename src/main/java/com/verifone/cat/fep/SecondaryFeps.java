package com.verifone.cat.fep;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.CommanderTrust;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.CgiReplies.CloudConnectStatus;
import com.verifone.cat.devices.commander.CgiReplies.FepDetail;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * The Commander's secondary FEPs, made exactly what a run chooses: a port of {@code petrotest.secondary_fep}.
 *
 * <p>What a run does, in CAT's step 4 after {@code commander-init}:
 * <ol>
 * <li>{@code vfepdetails}: which FEPs the Commander has enabled. A chosen FEP its table lacks fails the step here;</li>
 * <li>with a Commercial Fleet FEP chosen, {@code vcloudconnectstatus}: an un-onboarded Commander fails here too;</li>
 * <li>every other catalogue FEP found enabled is switched off ({@code vfepcfg}, {@code <enabled>false}, posted back);</li>
 * <li>each chosen FEP is switched on: its petroapps files imported, or its own config edited and posted back;</li>
 * <li>{@code crefreshcfg} when anything changed, then {@code vfepdetails} again: every chosen FEP must read enabled.</li>
 * </ol>
 *
 * <p>No EPS restart, deliberately: {@code ufepcfg} stages a FEP-table update that the EPS applies. Unverified: the
 * Python never ran this against a Commander either.
 *
 * <p>Step 4's caller is {@code launch.CommanderStages.Real#secondaryFep}, the Python's {@code secondary_fep_cli}: it
 * calls {@link #select} with {@code FepClient.of(access.pdkShim())}, the route read over the Commander's SSH and
 * {@code Trust.viaCommander}. Not ported: the Commander Connect cloud sender (the Python's {@code _cloud_sender}),
 * which needs a C-Site account; a chosen Commercial Fleet FEP is pointed at directly, with this class's warning.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class SecondaryFeps {

    /** The table, shipped as a classpath resource beside this class. */
    public static final String CATALOGUE_RESOURCE = "secondary_feps.yaml";

    /** {@code --fep none}: no secondary FEP; any left on is switched off. */
    public static final String NONE = "none";

    /** The XML declaration {@code edit_fep_config} writes, as the Python does. */
    static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    /** {@code fepConfigIPOptType}'s sequence: {@code <port>} comes after these, before the rest. */
    static final Set<String> BEFORE_PORT = Set.of("address", "secondaryAddress", "secondaryPort");

    /** Where PMC keeps the Commercial Fleet route the license gave it (read on the lab Commander, 2026-10-20). */
    public static final String FLEET_ROUTE_FILE = "/home/pmc/data/license/CommercialFleetRoute.prop";

    /** The placeholder the table uses for the rig's controller. */
    static final String CONTROLLER = "{controller_host}";

    /** The FEP keys the table may use. {@code import} and {@code cloud} are its own spellings. */
    static final Set<String> KNOWN_KEYS = Set.of("name", "label", "kind", "enabled_by", "local_only", "simulator",
            "points_at", "listens_on", "fepsim_instance", "trust_tls", "note", "import", "cloud");

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern FLEET_ROUTE = Pattern.compile("commercialFleetRoute\\s*=\\s*(\\w+)");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private SecondaryFeps() {}

    /** A cloud call's reply: the status and the body text. */
    public record Reply(int status, String body) {}

    /** Commander Connect's {@code commercialfleetproviders} call: {@code (method, endpoint, body)} to a reply. */
    @FunctionalInterface
    public interface CloudSend {
        Reply send(String method, String endpoint, String body);
    }

    /** Makes the Commander trust a simulator's TLS certificate under an alias; throws {@link DeviceError} on refusal. */
    @FunctionalInterface
    public interface Trust {

        void trust(String endpoint, String alias);

        /** The production trust: {@link CommanderTrust#trustCli} with PMC restarted when the store changed. */
        static Trust viaCommander(CommanderAccess access, Say say) {
            return (endpoint, alias) -> {
                if (!CommanderTrust.trustCli(access, endpoint, alias, true, say)) {
                    throw new DeviceError("the Commander does not trust " + endpoint);
                }
            };
        }
    }

    /**
     * What the selection reads from outside the Commander's FEP table.
     *
     * @param petroappsRoot the checkout whose {@code AutomationTests/configurations} holds the FEP files
     * @param controllerHost the rig's controller, filled into the {@code {controller_host}} placeholders
     * @param toolsRoot     the tools root, where a simulator's listen address is read
     * @param cloudSend     Commander Connect's provider call, or null when no C-Site account is set
     * @param serviceId     the Commander Connect service id ({@code ""} when none)
     * @param route         the Commander's Commercial Fleet route, read for a message; null for none
     * @param trust         makes a row's {@code trust_tls} certificate trusted; null when no Commander shell
     */
    public record Context(Path petroappsRoot, String controllerHost, Path toolsRoot, CloudSend cloudSend,
            String serviceId, Supplier<String> route, Trust trust) {

        /** The context with no cloud, no route and no trust: the Commander is pointed directly. */
        public static Context of(Path petroappsRoot, String controllerHost, Path toolsRoot) {
            return new Context(petroappsRoot, controllerHost, toolsRoot, null, "", null, null);
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // The table
    // ----------------------------------------------------------------------------------------------------

    /** The shipped table. */
    public static List<SecondaryFep> loadCatalogue() {
        try (InputStream in = SecondaryFeps.class.getResourceAsStream(CATALOGUE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(CATALOGUE_RESOURCE + " is not on the classpath");
            }
            return parseCatalogue(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + CATALOGUE_RESOURCE + ": " + e.getMessage(), e);
        }
    }

    /**
     * A table at {@code path}, for a test or a different checkout.
     *
     * @throws IOException the file cannot be read
     */
    public static List<SecondaryFep> loadCatalogue(Path path) throws IOException {
        return parseCatalogue(Files.readString(path, StandardCharsets.UTF_8));
    }

    /**
     * Parses the table. A typo'd key, an unknown {@code enabled_by}, a repeated name or a dangling simulator
     * setting raises.
     *
     * @throws IllegalArgumentException any of those, or YAML that does not parse
     */
    public static List<SecondaryFep> parseCatalogue(String yaml) {
        List<Map<String, Object>> raw;
        try {
            raw = YAML.readValue(yaml, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(CATALOGUE_RESOURCE + " does not parse: " + e.getOriginalMessage(), e);
        }
        if (raw == null) {
            return List.of();
        }
        List<SecondaryFep> rows = new ArrayList<>();
        for (Map<String, Object> entry : raw) {
            SecondaryFep row = row(entry);
            if (!row.enabledBy().equals(SecondaryFep.FEPCFG) && !row.enabledBy().equals(SecondaryFep.LICENSE)) {
                throw new IllegalArgumentException("secondary FEP '" + row.name()
                        + "': enabled_by must be one of [fepcfg, license]");
            }
            boolean simulatorKeys = !row.pointsAt().isEmpty() || !row.listensOn().isEmpty()
                    || !row.fepsimInstance().isEmpty() || !row.trustTls().isEmpty();
            if (simulatorKeys && row.simulator().isEmpty()) {
                throw new IllegalArgumentException("secondary FEP '" + row.name()
                        + "': points_at/listens_on/fepsim_instance/trust_tls with no simulator");
            }
            if (!row.cloudProvider().isEmpty() && !(row.licenseEnabled() && !row.pointsAt().isEmpty())) {
                throw new IllegalArgumentException("secondary FEP '" + row.name()
                        + "': cloud is for a license FEP pointed at a simulator");
            }
            if (!row.listensOn().isEmpty() && !row.listensOn().contains(":")) {
                throw new IllegalArgumentException("secondary FEP '" + row.name() + "': listens_on must be <file>:<key>");
            }
            for (SecondaryFep other : rows) {
                if (other.name().equalsIgnoreCase(row.name())) {
                    throw new IllegalArgumentException("secondary FEP '" + row.name() + "' is listed twice");
                }
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    /** One entry of the YAML list as a row; keys the table does not declare are refused. */
    private static SecondaryFep row(Map<String, Object> entry) {
        for (String key : entry.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                throw new IllegalArgumentException("secondary FEP " + entry.get("name") + ": unknown keys ["
                        + key + "]");
            }
        }
        String name = text(entry.get("name"));
        String label = text(entry.get("label"));
        if (name.isEmpty() || label.isEmpty()) {
            throw new IllegalArgumentException("secondary FEP " + (name.isEmpty() ? "entry" : name)
                    + " needs a name and a label");
        }
        List<String> files = new ArrayList<>();
        Object imports = entry.get("import");
        if (imports instanceof List<?> list) {
            for (Object item : list) {
                files.add(String.valueOf(item));
            }
        }
        String cloudProvider = "";
        String cloudPayload = "";
        Object cloud = entry.get("cloud");
        if (cloud != null) {
            if (!(cloud instanceof Map<?, ?> map) || !Set.of("provider", "payload").equals(map.keySet())
                    || !(map.get("payload") instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("secondary FEP '" + name
                        + "': cloud needs provider and a payload mapping");
            }
            cloudProvider = text(map.get("provider"));
            try {
                cloudPayload = JSON.writeValueAsString(map.get("payload"));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("secondary FEP '" + name + "': cloud payload: " + e.getMessage(), e);
            }
        }
        String enabledBy = text(entry.get("enabled_by"));
        return new SecondaryFep(name, label, text(entry.get("kind")), enabledBy.isEmpty() ? SecondaryFep.FEPCFG
                : enabledBy, List.copyOf(files), bool(entry.get("local_only")), text(entry.get("simulator")),
                text(entry.get("points_at")), text(entry.get("listens_on")), text(entry.get("fepsim_instance")),
                cloudProvider, cloudPayload, text(entry.get("trust_tls")), text(entry.get("note")));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(text(value));
    }

    /** The row named {@code name}, matched without regard to case, or null. */
    public static SecondaryFep find(String name, List<SecondaryFep> catalogue) {
        for (SecondaryFep row : catalogue) {
            if (row.name().equalsIgnoreCase(name.strip())) {
                return row;
            }
        }
        return null;
    }

    /** The table's FEPs a case's tags name, in table order: {@code @wex_otr} is WEX-OTR. */
    public static List<String> featuresForTags(List<String> tags, List<SecondaryFep> catalogue) {
        Set<String> wanted = tags.stream().map(tag -> tagKey(tag.replaceFirst("^@+", ""))).collect(Collectors.toSet());
        List<String> out = new ArrayList<>();
        for (SecondaryFep row : catalogue) {
            if (wanted.contains(tagKey(row.name()))) {
                out.add(row.name());
            }
        }
        return out;
    }

    private static String tagKey(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * {@code --fep} values as table rows, in the order given, each once. {@code none} alone is an empty list.
     *
     * @throws IllegalArgumentException a name the table lacks, or {@code none} given with other names
     */
    public static List<SecondaryFep> resolve(List<String> names, List<SecondaryFep> catalogue) {
        List<String> wanted = names.stream().map(String::strip).filter(n -> !n.isEmpty()).toList();
        if (wanted.stream().anyMatch(n -> n.equalsIgnoreCase(NONE))) {
            if (wanted.size() > 1) {
                throw new IllegalArgumentException("'none' cannot be given with other FEPs");
            }
            return List.of();
        }
        List<SecondaryFep> chosen = new ArrayList<>();
        for (String name : wanted) {
            SecondaryFep row = find(name, catalogue);
            if (row == null) {
                String offered = catalogue.stream().map(SecondaryFep::name).collect(Collectors.joining(", "));
                throw new IllegalArgumentException("no FEP '" + name + "'; one of: " + offered + ", or none");
            }
            if (!chosen.contains(row)) {
                chosen.add(row);
            }
        }
        return chosen;
    }

    // ----------------------------------------------------------------------------------------------------
    // Editing a fepConfig
    // ----------------------------------------------------------------------------------------------------

    /**
     * A {@code viper:fepConfig} document with {@code <enabled>} and/or its host changed. {@code pointsAt} is a URL
     * (which becomes the whole {@code <domainName>} and leaves {@code <port>} alone), or {@code host:port} (which
     * becomes {@code <ipV4>}, or {@code <domainName>} when {@code hostAsDomainName}, and {@code <port>}, with the
     * address's {@code sslEnabled} turned off: every simulator a socket FEP is pointed at is plain TCP).
     *
     * @param enabled     the new {@code <enabled>}, or null to leave it
     * @param pointsAt    the new host, or null/empty to leave it
     * @throws IllegalArgumentException no {@code fepConfig} element, no {@code <config>}, or an unusable target
     */
    public static String editFepConfig(String xml, Boolean enabled, String pointsAt, boolean hostAsDomainName) {
        Document doc = parse(xml);
        Element root = doc.getDocumentElement();
        Element document = localName(root).equals("fepConfig") ? root : firstDescendant(root, "fepConfig");
        if (document == null) {
            throw new IllegalArgumentException("not a fepConfig document");
        }
        Element config = childNamed(document, "config");
        if (config == null) {
            throw new IllegalArgumentException("fepConfig has no <config>");
        }
        if (enabled != null) {
            Element element = childNamed(config, "enabled");
            if (element == null) {
                element = doc.createElement("enabled");
                Element name = childNamed(config, "fepName");
                int index = name == null ? 0 : elementIndex(config, name) + 1;
                insertAt(config, index, element);
            }
            element.setTextContent(enabled ? "true" : "false");
        }
        if (pointsAt != null && !pointsAt.isEmpty()) {
            pointAt(config, pointsAt, hostAsDomainName);
        }
        return DECLARATION + serialize(document);
    }

    /** {@code _point_at}: the first comm option's address set to {@code target}. */
    private static void pointAt(Element config, String target, boolean hostAsDomainName) {
        Document doc = config.getOwnerDocument();
        Element commOptions = childNamed(config, "commOptions");
        if (commOptions == null) {
            commOptions = doc.createElement("commOptions");
            commOptions.setAttribute("maxRecords", "1");
            config.appendChild(commOptions);
        }
        Element option = childNamed(commOptions, "commOption");
        if (option == null) {
            option = doc.createElement("commOption");
            option.setAttribute("sysId", "1");
            option.setAttribute("primary", "true");
            option.setAttribute("enabled", "true");
            insertAt(commOptions, 0, option);
        }
        Element ip = childNamed(option, "ip");
        if (ip == null) {
            ip = doc.createElement("ip");
            option.appendChild(ip);
        }
        Element address = childNamed(ip, "address");
        if (address == null) {
            address = doc.createElement("address");
            insertAt(ip, 0, address);
        }
        for (Element child : children(address)) {
            address.removeChild(child);
        }

        if (target.contains("://")) {
            // A REST FEP reads its whole endpoint off <domainName> and never its port, but the schema needs a <port>.
            appendText(address, "domainName", target);
            if (childNamed(ip, "port") == null) {
                int port = URI.create(target).getPort();
                if (port < 0) {
                    port = "https".equals(URI.create(target).getScheme()) ? 443 : 80;
                }
                setPort(ip, String.valueOf(port));
            }
            return;
        }
        int colon = target.lastIndexOf(':');
        String host = colon < 0 ? "" : target.substring(0, colon);
        String port = colon < 0 ? "" : target.substring(colon + 1);
        if (host.isEmpty() || port.isEmpty() || !port.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("points_at '" + target + "' is neither a URL nor host:port");
        }
        String tag = IPV4.matcher(host).matches() && !hostAsDomainName ? "ipV4" : "domainName";
        appendText(address, tag, host);
        if (address.hasAttribute("sslEnabled")) {
            address.setAttribute("sslEnabled", "false");
        }
        setPort(ip, port);
    }

    private static void appendText(Element parent, String name, String text) {
        Element child = parent.getOwnerDocument().createElement(name);
        child.setTextContent(text);
        parent.appendChild(child);
    }

    /** {@code _set_port}: the {@code <port>} of {@code ip}, created where the schema's sequence puts it. */
    private static void setPort(Element ip, String port) {
        Element element = childNamed(ip, "port");
        if (element == null) {
            List<Element> children = children(ip);
            int after = -1;
            for (int i = 0; i < children.size(); i++) {
                if (BEFORE_PORT.contains(localName(children.get(i)))) {
                    after = i;
                }
            }
            element = ip.getOwnerDocument().createElement("port");
            insertAt(ip, after + 1, element);
        }
        element.setTextContent(port);
    }

    /** The address a {@code viper:fepConfig} document points its FEP at: {@code host:port}, a URL, or "". */
    public static String fepAddress(String xml) {
        Document doc;
        try {
            doc = parse(xml);
        } catch (IllegalArgumentException e) {
            return "";
        }
        Element commOptions = firstDescendant(doc.getDocumentElement(), "commOptions");
        Element option = commOptions == null ? null : childNamed(commOptions, "commOption");
        Element ip = option == null ? null : childNamed(option, "ip");
        if (ip == null) {
            return "";
        }
        String host = "";
        NodeList all = ip.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element candidate = (Element) all.item(i);
            String value = candidate.getTextContent().strip();
            String local = localName(candidate);
            if ((local.equals("ipV4") || local.equals("domainName")) && !value.isEmpty()) {
                host = value;
                break;
            }
        }
        if (host.contains("://")) {
            return host;
        }
        Element portElement = childNamed(ip, "port");
        String port = portElement == null ? "" : portElement.getTextContent().strip();
        return !host.isEmpty() && !port.isEmpty() ? host + ":" + port : host;
    }

    // ----------------------------------------------------------------------------------------------------
    // Which FEPs are switched off, pointed, or licensed
    // ----------------------------------------------------------------------------------------------------

    /**
     * The Commercial Fleet route PMC keeps: DIRECT, PRIMARY or NONE, or "" when unread. CFD (DIRECT) is what enables
     * WEX-OTR, Comdata and mudflap; on the lab on 2026-10-20 the route was NONE.
     */
    public static String fleetRoute(Transport transport, Duration timeout) {
        CommandResult read;
        try {
            read = transport.run("cat " + FLEET_ROUTE_FILE, timeout);
        } catch (RuntimeException e) {
            return "";
        }
        Matcher match = FLEET_ROUTE.matcher(read.stdout() == null ? "" : read.stdout());
        return match.find() ? match.group(1) : "";
    }

    /** {@code row}'s Commander Connect provider body, pointed at this rig's controller. */
    public static String cloudPayload(SecondaryFep row, String controllerHost) {
        return row.cloudPayload().replace(CONTROLLER, controllerHost);
    }

    /** {@code row}'s Commander Connect provider endpoint, with {@code {service_id}} still to fill. */
    public static String cloudEndpoint(SecondaryFep row) {
        return "pcms/v1/config/{service_id}/commercialfleetproviders/" + row.cloudProvider();
    }

    /**
     * Points each Commercial Fleet FEP at the controller in Commander Connect, as PATS does: the cloud owns these
     * FEPs' host. What the cloud held before is printed first. Returns the problems; none is fatal by itself,
     * because the Commander is also pointed directly.
     */
    public static List<String> pushCloudProviders(List<SecondaryFep> rows, String controllerHost, String serviceId,
            CloudSend send, Say say) {
        List<String> problems = new ArrayList<>();
        for (SecondaryFep row : rows) {
            if (row.cloudProvider().isEmpty()) {
                continue;
            }
            String endpoint = cloudEndpoint(row).replace("{service_id}", serviceId);
            Reply before = send.send("GET", endpoint, null);
            String held = before.status() == 200 ? before.body().strip() : "none (HTTP " + before.status() + ")";
            say.out(row.name() + ": Commander Connect held " + held.substring(0, Math.min(300, held.length())));
            Reply answer = send.send("PUT", endpoint, cloudPayload(row, controllerHost));
            if (answer.status() >= 200 && answer.status() < 300) {
                say.out(row.name() + ": Commander Connect now points it at the controller");
            } else {
                String body = answer.body() == null ? "" : answer.body();
                problems.add(row.name() + ": PUT " + endpoint + " answered HTTP " + answer.status() + ": "
                        + body.substring(0, Math.min(200, body.length())));
            }
        }
        return problems;
    }

    /**
     * Why the chosen license FEPs cannot be enabled on this Commander, or "" if they can. The Commercial Fleet license
     * reaches a Commander only through Commander Connect, so an un-onboarded Commander cannot enable them.
     */
    static String commanderConnectProblem(FepClient client, List<SecondaryFep> chosen, Say say) {
        List<String> licensed = chosen.stream().filter(SecondaryFep::licenseEnabled).map(SecondaryFep::name).toList();
        if (licensed.isEmpty()) {
            return "";
        }
        CloudConnectStatus status = client.cloudConnectStatus();
        if (status.registered()) {
            say.out("Commander Connect: onboarded (" + String.join(", ", licensed) + " need it)");
            return "";
        }
        String verb = licensed.size() == 1 ? "is" : "are";
        return "Commander Connect is not enabled on this Commander (" + status.detail() + "). "
                + String.join(", ", licensed) + " " + verb + " enabled only by the Commercial Fleet license it "
                + "delivers: turn on the Config tab's Commander Connect > Onboard";
    }

    /** Why {@code row}'s simulator cannot answer it from this controller, or "" if it can. */
    static String simulatorAddressProblem(SecondaryFep row, String controllerHost, Path toolsRoot) {
        if (row.listensOn().isEmpty()) {
            return "";
        }
        int colon = row.listensOn().lastIndexOf(':');
        String rel = row.listensOn().substring(0, colon);
        String key = row.listensOn().substring(colon + 1);
        Path path = toolsRoot.resolve(rel);
        String value;
        try {
            value = readProperty(path, key);
        } catch (IOException e) {
            return row.name() + ": cannot read " + path + ": " + e.getMessage();
        }
        if (value == null) {
            return row.name() + ": " + path + " sets no " + key;
        }
        if (!value.equals(controllerHost)) {
            return row.name() + ": " + row.simulator() + " listens on, and names in its certificate, " + value + " ("
                    + path.getFileName() + " " + key + "), not this rig's controller " + controllerHost + ". Set "
                    + key + "=" + controllerHost + " there and restart it";
        }
        if (row.pointsAt().startsWith("https://") && !IPV4.matcher(controllerHost).matches()) {
            return row.name() + ": " + row.simulator() + "'s certificate names only an IP address, so the rig's "
                    + "controller host must be one, not '" + controllerHost + "'";
        }
        return "";
    }

    /** {@code key}'s value in a Java {@code .properties} file, or null when it has none. */
    static String readProperty(Path path, String key) throws IOException {
        for (String raw : new String(Files.readAllBytes(path), StandardCharsets.UTF_8).split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || !line.contains("=")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (line.substring(0, eq).strip().equals(key)) {
                return line.substring(eq + 1).strip();
            }
        }
        return null;
    }

    // ----------------------------------------------------------------------------------------------------
    // The selection
    // ----------------------------------------------------------------------------------------------------

    /**
     * Makes the Commander's secondary FEPs exactly {@code chosen}. Returns false, having said why, when a chosen FEP
     * cannot be or was not enabled, or does not point where its row says. Nothing is written until every chosen FEP
     * is known to the Commander, each chosen simulator listens on the controller, and, for a license FEP, Commander
     * Connect reads onboarded. Faults from the Commander propagate.
     *
     * @throws DeviceError            a Commander refusal (a FEP's own config not applied)
     * @throws IllegalStateException  a chosen FEP's petroapps file is missing
     */
    public static boolean select(FepClient client, List<SecondaryFep> chosen, List<SecondaryFep> catalogue,
            Context ctx, Say say) {
        Map<String, FepDetail> details = client.fepDetails();
        List<String> enabled = details.entrySet().stream().filter(e -> e.getValue().enabled())
                .map(Map.Entry::getKey).toList();
        say.out("enabled on the Commander: " + (enabled.isEmpty() ? "none" : String.join(", ", enabled)));

        List<String> unknown = chosen.stream().map(SecondaryFep::name).filter(n -> !details.containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            say.err(String.join(", ", unknown) + ": not in this Commander's FEP table; its distribution does not "
                    + "offer it");
            return false;
        }
        List<String> problems = new ArrayList<>();
        for (SecondaryFep row : chosen) {
            problems.add(simulatorAddressProblem(row, ctx.controllerHost(), ctx.toolsRoot()));
        }
        problems.add(commanderConnectProblem(client, chosen, say));
        if (problems.stream().anyMatch(p -> !p.isEmpty())) {
            problems.stream().filter(p -> !p.isEmpty()).forEach(say::err);
            return false;
        }

        boolean changed = false;
        for (SecondaryFep row : catalogue) {
            FepDetail detail = details.get(row.name());
            if (chosen.contains(row) || detail == null || !detail.enabled()) {
                continue;
            }
            if (row.licenseEnabled()) {
                say.out(row.name() + ": left on - the Commercial Fleet license enables it, and CAT cannot switch that "
                        + "off");
                continue;
            }
            disable(client, row);
            changed = true;
            say.out(row.name() + ": switched off (an earlier run, or a person, left it on)");
        }

        for (SecondaryFep row : chosen) {
            if (row.trustTls().isEmpty()) {
                continue;
            }
            if (ctx.trust() == null) {
                say.err(row.name() + ": needs " + row.trustTls() + " trusted; no Commander shell");
                return false;
            }
            ctx.trust().trust(resolveHost(row.trustTls(), ctx.controllerHost()),
                    "cat-" + row.name().toLowerCase(Locale.ROOT));
        }

        List<SecondaryFep> clouded = chosen.stream().filter(r -> !r.cloudProvider().isEmpty()).toList();
        if (!clouded.isEmpty() && ctx.cloudSend() != null && !ctx.serviceId().isEmpty()) {
            for (String problem : pushCloudProviders(clouded, ctx.controllerHost(), ctx.serviceId(), ctx.cloudSend(),
                    say)) {
                say.out("WARNING " + problem + "; the Commander is pointed directly instead");
            }
        } else if (!clouded.isEmpty()) {
            say.out("WARNING " + clouded.stream().map(SecondaryFep::name).collect(Collectors.joining(", "))
                    + ": Commander Connect not updated (no C-Site account or service ID); a later cloud push could "
                    + "point it elsewhere");
        }

        for (SecondaryFep row : chosen) {
            boolean sent = enable(client, row, ctx.petroappsRoot(), ctx.controllerHost(), say);
            changed = sent || changed;
        }

        if (changed) {
            client.refreshConfiguration();
            say.out("crefreshcfg sent");
        }
        if (chosen.isEmpty()) {
            say.out("secondary FEPs: none");
            return true;
        }

        Map<String, FepDetail> after = client.fepDetails();
        boolean ok = true;
        for (SecondaryFep row : chosen) {
            if (!row.pointsAt().isEmpty()) {
                String target = resolveHost(row.pointsAt(), ctx.controllerHost());
                String now = fepAddress(client.viewConfiguration(row.fepcfg()));
                if (!stripSlash(now).equals(stripSlash(target))) {
                    say.err(row.name() + " points at " + (now.isEmpty() ? "nothing" : now) + ", not " + target);
                    ok = false;
                } else {
                    say.out(row.name() + " points at " + target);
                }
            }
            FepDetail detail = after.get(row.name());
            if (detail == null || !detail.enabled()) {
                say.err(row.name() + " is not enabled: " + notEnabledWhy(row, ctx));
                ok = false;
            } else if (!row.simulator().isEmpty()) {
                say.out(row.name() + " enabled; " + row.simulator() + " answers as its host");
            } else if (!row.localOnly()) {
                say.out("WARNING " + row.name() + " enabled, but no simulator answers as its host:");
                say.out("WARNING " + row.note());
            } else {
                say.out(row.name() + " enabled; it needs no host");
            }
        }
        return ok;
    }

    private static String notEnabledWhy(SecondaryFep row, Context ctx) {
        if (!row.licenseEnabled()) {
            return "the Commander did not keep <enabled>true</enabled>";
        }
        String found = ctx.route() == null ? "" : ctx.route().get();
        String route = found.isEmpty() ? "unknown" : found;
        return "only a Commercial Fleet license routed DIRECT (description CFD) from Commander Connect enables it; "
                + "this Commander's route is " + route
                + (found.equals("DIRECT") ? "" : " (the license is CFL, CFP or CFE, or absent)");
    }

    private static String stripSlash(String text) {
        return text.replaceAll("/+$", "");
    }

    private static String resolveHost(String pointsAt, String controllerHost) {
        return pointsAt.replace(CONTROLLER, controllerHost);
    }

    /**
     * Switches {@code row} on: its petroapps files, or its own config edited and posted back. Returns whether anything
     * was sent: a license FEP with no simulator has nothing to send.
     */
    static boolean enable(FepClient client, SecondaryFep row, Path petroappsRoot, String controllerHost, Say say) {
        String target = row.pointsAt().isEmpty() ? null : resolveHost(row.pointsAt(), controllerHost);
        if (row.files().isEmpty()) {
            if (row.licenseEnabled() && target == null) {
                say.out(row.name() + ": nothing to send; the Commercial Fleet license enables it");
                return false;
            }
            String text = client.viewConfiguration(row.fepcfg());
            client.updateConfiguration(row.fepcfg(), editFepConfig(text, row.licenseEnabled() ? null : true, target,
                    row.licenseEnabled()));
            say.out(row.name() + ": its own configuration posted back" + (target == null ? ""
                    : ", pointed at " + target));
            return true;
        }

        Path configurations = petroappsRoot.resolve("AutomationTests").resolve("configurations");
        List<Path> sources = row.files().stream().map(configurations::resolve).toList();
        List<String> missing = sources.stream().filter(p -> !Files.isRegularFile(p)).map(Path::toString).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(row.name() + ": not in petroapps: " + String.join(", ", missing));
        }
        String own = row.fepcfg() + ".xml";
        Path staging;
        try {
            staging = Files.createTempDirectory("secondary-fep-");
        } catch (IOException e) {
            throw new IllegalStateException(row.name() + ": cannot stage its files: " + e.getMessage(), e);
        }
        try {
            for (Path source : sources) {
                String fileName = source.getFileName().toString();
                if (fileName.equals(own)) {
                    String text = Files.readString(source, StandardCharsets.UTF_8);
                    text = editFepConfig(text, row.licenseEnabled() ? null : true, target, row.licenseEnabled());
                    Files.writeString(staging.resolve(fileName), text, StandardCharsets.UTF_8);
                } else {
                    Files.write(staging.resolve(fileName), Files.readAllBytes(source));
                }
            }
            List<String> names;
            try (Stream<Path> listed = Files.list(staging)) {
                names = listed.map(p -> p.getFileName().toString()).sorted().toList();
            }
            say.out(row.name() + ": importing " + String.join(", ", names));
            Map<String, String> applied = client.importConfigurationFolder(staging);
            List<String> skipped = names.stream()
                    .filter(name -> !applied.containsKey("u" + name.substring(0, Math.max(0, name.length() - 4))))
                    .toList();
            if (skipped.contains(own)) {
                throw new DeviceError(row.name() + ": " + own + " was not applied");
            }
            for (String name : skipped) {
                say.out("WARNING " + row.name() + ": " + name + " was not applied (skipped, as PATS skips it)");
            }
            return true;
        } catch (IOException e) {
            throw new IllegalStateException(row.name() + ": cannot stage its files: " + e.getMessage(), e);
        } finally {
            deleteQuietly(staging);
        }
    }

    /** Switches {@code row} off: its own config with {@code <enabled>false}, posted back. */
    static void disable(FepClient client, SecondaryFep row) {
        String text = client.viewConfiguration(row.fepcfg());
        client.updateConfiguration(row.fepcfg(), editFepConfig(text, false, null, false));
    }

    // ----------------------------------------------------------------------------------------------------
    // XML plumbing: namespace-aware DOM, children by local name, PATS-style element order
    // ----------------------------------------------------------------------------------------------------

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml.strip())));
        } catch (Exception e) {
            throw new IllegalArgumentException("not an XML document: " + e.getMessage(), e);
        }
    }

    private static String serialize(Element element) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(element), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("cannot write the fepConfig: " + e.getMessage(), e);
        }
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        return local == null ? node.getNodeName() : local;
    }

    /** The direct child elements of {@code parent}, in order. */
    static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                out.add(element);
            }
        }
        return out;
    }

    /** The first direct child element named {@code local}, in any namespace (as ElementTree's {@code find}). */
    static Element childNamed(Element parent, String local) {
        for (Element child : children(parent)) {
            if (localName(child).equals(local)) {
                return child;
            }
        }
        return null;
    }

    private static int elementIndex(Element parent, Element child) {
        return children(parent).indexOf(child);
    }

    /** The first descendant element named {@code local}, in document order, excluding {@code root} itself. */
    static Element firstDescendant(Element root, String local) {
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element candidate = (Element) all.item(i);
            if (localName(candidate).equals(local)) {
                return candidate;
            }
        }
        return null;
    }

    /** Inserts {@code child} so it becomes the element at {@code index}; at the end when there are not that many. */
    private static void insertAt(Element parent, int index, Element child) {
        List<Element> children = children(parent);
        Element before = index < children.size() ? children.get(index) : null;
        parent.insertBefore(child, before);
    }

    private static void deleteQuietly(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a staging copy that will not delete is left for the OS temp cleaner
                }
            });
        } catch (IOException ignored) {
            // already gone
        }
    }
}
