package com.verifone.cat.rig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One rig, as a rig file ({@code rigs/<name>.yaml}) describes it. A port of the schema in
 * {@code petrotest/rig/models.py} (DESIGN.md §8).
 *
 * <p>The schema is strict, as the Python one is. Every key a rig file may carry is a component
 * of a record here; a key nothing declares is refused at load time, and a missing required key
 * is refused naming the key. A typo such as {@code registars:} must fail when the rig loads, not
 * later as a connection to the wrong port.
 *
 * <p>Construct it through {@link RigLoader}, which reads the YAML and reports every failure with
 * the file and the field path. The compact constructors carry the same checks as the Python
 * validators, with the same wording where the wording carries meaning.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The schema is pure data. The lab rig's values were
 * read from the Python CAT's {@code rigs/lab.yaml}, the copy that has been used on the rig.
 */
public record Rig(
        @JsonProperty(value = "name", required = true) String name,
        @JsonProperty(value = "commander", required = true) Commander commander,
        @JsonProperty(value = "registers", required = true) List<Register> registers,
        @JsonProperty(value = "controller", required = true) Controller controller,
        @JsonProperty(value = "pinpad", required = true) Pinpad pinpad,
        @JsonProperty("rci") Rci rci,
        @JsonProperty("forecourt") Forecourt forecourt,
        @JsonProperty("pos_shim") Boolean posShimDeclared,
        @JsonProperty("wiremock") Wiremock wiremock,
        @JsonProperty("vats") Vats vats,
        @JsonProperty("devices") Map<String, Device> devices,
        @JsonProperty("pumps") List<Integer> pumps,
        @JsonProperty("cashier") Cashier cashier,
        @JsonProperty(value = "build", required = true) Build build,
        @JsonProperty("timeouts") Timeouts timeouts) {

    /** Rig names double as file stems and appear in artifact paths, so they stay plain. */
    static final Pattern RIG_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");

    /**
     * Applies the defaults the Python schema applies, then the Rig-level validators. Field-level
     * checks have already run in the blocks' own constructors.
     */
    public Rig {
        if (name == null || !RIG_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("expected a lowercase rig name matching '" + RIG_NAME.pattern()
                    + "' (it is also the file stem, and appears in artifact paths), got '" + name + "'");
        }
        registers = registers == null ? List.of() : List.copyOf(registers);
        if (registers.isEmpty()) {
            throw new IllegalArgumentException("List should have at least 1 item");
        }
        devices = devices == null ? Map.of() : Map.copyOf(devices);
        pumps = pumps == null ? List.of() : List.copyOf(pumps);
        timeouts = timeouts == null ? Timeouts.defaults() : timeouts;
        posShimDeclared = posShimDeclared != null && posShimDeclared;
        checkPumps(pumps);
        if (pinpad == Pinpad.VATS && vats == null) {
            throw new IllegalArgumentException("pinpad: vats requires a vats: block naming the terminal (vats.host) "
                    + "— see rigs/example.yaml");
        }
        checkRegisterIds(registers);
        checkTlsPort(controller, registers);
        checkPortClaims(claimsOf(commander, registers, controller, wiremock, vats, devices));
    }

    /** Whether the rig declares {@code pos_shim: true}. Absent means false, as in the Python schema. */
    public boolean posShim() {
        return posShimDeclared;
    }

    /** The register called {@code id}, or the first register when {@code id} is null. */
    public Register register(String id) {
        if (id == null) {
            return primaryRegister();
        }
        return registers.stream()
                .filter(r -> id.equals(r.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Rig '" + name + "' has no register '" + id
                        + "'; it has " + registers.stream().map(Register::id).toList() + "."));
    }

    /** The first register. Indoor suites that name no register use this one. */
    public Register primaryRegister() {
        return registers.get(0);
    }

    /**
     * Every port this rig declares, as {@code (host, port, field path)}. Keyed by host, because a
     * collision is real only on one machine: RCI answers on the Commander and WireMock on the
     * controller, so the shared default 8080 is no conflict on a two-box rig.
     */
    public List<PortClaim> portClaims() {
        return claimsOf(commander, registers, controller, wiremock, vats, devices);
    }

    /** One declared port: where it is, and which field declared it. */
    public record PortClaim(String host, int port, String field) {}

    // -- Rig-level validators -----------------------------------------------------------------

    private static void checkPumps(List<Integer> pumps) {
        List<Integer> bad = pumps.stream().filter(n -> n < 1).toList();
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException("pumps must be positive fuelling-point numbers, got " + bad);
        }
        List<Integer> duplicates = duplicatesOf(pumps);
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("pumps must be unique — duplicated: " + duplicates
                    + ". A repeated pump would make preflight probe it twice and report it twice (§6.2 pumps_idle).");
        }
    }

    private static void checkRegisterIds(List<Register> registers) {
        List<String> duplicates = duplicatesOf(registers.stream().map(Register::id).toList());
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("registers[].id must be unique — duplicated: "
                    + String.join(", ", duplicates));
        }
    }

    private static void checkTlsPort(Controller controller, List<Register> registers) {
        String tls = upper(controller.tlsPort());
        if (tls.isEmpty()) {
            return;
        }
        for (Register r : registers) {
            if (upper(r.printerPort()).equals(tls)) {
                throw new IllegalArgumentException("controller.tls_port and registers[" + r.id()
                        + "].printer_port are both " + controller.tlsPort()
                        + "; the tank simulator would take the receipt printer's serial port");
            }
        }
    }

    private static void checkPortClaims(List<PortClaim> claims) {
        Map<String, String> claimed = new LinkedHashMap<>();
        for (PortClaim c : claims) {
            String key = c.host() + ":" + c.port();
            String holder = claimed.get(key);
            if (holder != null) {
                throw new IllegalArgumentException(holder + " and " + c.field() + " are both " + c.port() + " on "
                        + c.host() + "; two services on one host cannot share a port (§8) — one of them would fail "
                        + "to bind, or worse, " + c.field() + "'s traffic would reach " + holder + "'s service");
            }
            claimed.put(key, c.field());
        }
    }

    private static List<PortClaim> claimsOf(Commander commander, List<Register> registers, Controller controller,
            Wiremock wiremock, Vats vats, Map<String, Device> devices) {
        List<PortClaim> claims = new ArrayList<>();
        claims.add(new PortClaim(controller.host(), commander.epsPort(), "commander.eps_port"));
        claims.add(new PortClaim(commander.host(), commander.rciPort(), "commander.rci_port"));
        for (int i = 0; i < registers.size(); i++) {
            Register r = registers.get(i);
            claims.add(new PortClaim(r.host(), r.cdpPort(), "registers[" + i + "].cdp_port"));
        }
        if (wiremock != null) {
            // WireMock runs on the rig's controller PC (§8, §7.3).
            claims.add(new PortClaim(controller.host(), wiremock.port(), "wiremock.port"));
        }
        if (vats != null) {
            // Not vats.port: that is the terminal's own, which nothing here binds. The other three
            // CAT starts on the controller.
            claims.add(new PortClaim(controller.host(), vats.km4Port(), "vats.km4_port"));
            claims.add(new PortClaim(controller.host(), vats.cardServerPort(), "vats.card_server_port"));
            claims.add(new PortClaim(controller.host(), vats.automationPort(), "vats.automation_port"));
        }
        devices.keySet().stream().sorted().forEach(name -> {
            Device d = devices.get(name);
            claims.add(new PortClaim(d.host(), d.port(), "devices." + name + ".port"));
        });
        return List.copyOf(claims);
    }

    /** The values that occur more than once, each once, sorted: what the Python messages list. */
    private static <T extends Comparable<T>> List<T> duplicatesOf(List<T> values) {
        return values.stream()
                .filter(v -> values.stream().filter(v::equals).count() > 1)
                .distinct()
                .sorted()
                .toList();
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    /** A port must be 1 to 65535 (pydantic's {@code Port}). */
    static void checkPort(int value, String field) {
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(field + ": Input should be between 1 and 65535, got " + value);
        }
    }

    // -- the blocks ---------------------------------------------------------------------------

    /** The Commander under test (§8, §4.1). */
    public record Commander(
            @JsonProperty(value = "host", required = true) String host,
            @JsonProperty(value = "ssh_user", required = true) String sshUser,
            @JsonProperty(value = "eps_port", required = true) int epsPort,
            @JsonProperty("rci_port") Integer rciPort,
            @JsonProperty("ssh_key_path_env") String sshKeyPathEnv) {

        /**
         * {@code eps_port} is the payment-host (FEP) port the Commander's EPS dials on the
         * controller. Nothing on the Commander listens on it. It keeps its place under
         * {@code commander:} because every rig file already carries it there.
         */
        public Commander {
            host = Hosts.validate(host);
            if (sshUser == null || sshUser.isEmpty()) {
                throw new IllegalArgumentException("String should have at least 1 character");
            }
            checkPort(epsPort, "eps_port");
            rciPort = rciPort == null ? 8080 : rciPort;
            checkPort(rciPort, "rci_port");
            sshKeyPathEnv = sshKeyPathEnv == null ? "" : sshKeyPathEnv;
        }

        /**
         * The converted SSH key's path, from the environment variable named by
         * {@code ssh_key_path_env}. Null when that is blank or unset: the SSH layer then falls back
         * to its own defaults, which fails later with a better message than one raised here.
         */
        public String resolveKeyFilename(Map<String, String> environ) {
            if (sshKeyPathEnv.isEmpty()) {
                return null;
            }
            String value = environ.get(sshKeyPathEnv);
            return value == null || value.isEmpty() ? null : value;
        }
    }

    /** One Topaz register (§8). Several per rig is the §9 lever. */
    public record Register(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "host", required = true) String host,
            @JsonProperty("cdp_port") Integer cdpPort,
            @JsonProperty("browser_override") BrowserOverride browserOverride,
            @JsonProperty("printer_port") String printerPort,
            @JsonProperty("cash_drawer") Boolean cashDrawer) {

        /** {@code cdp_port} defaults to 9222, the port Playwright attaches to (§5.2). */
        public Register {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("String should have at least 1 character");
            }
            host = Hosts.validate(host);
            cdpPort = cdpPort == null ? 9222 : cdpPort;
            checkPort(cdpPort, "cdp_port");
        }
    }

    /** The PC that hosts the harness, the simulators and WireMock for this rig (§8, §9). */
    public record Controller(
            @JsonProperty(value = "host", required = true) String host,
            @JsonProperty("tls_port") String tlsPort) {

        public Controller {
            host = Hosts.validate(host);
        }
    }

    /** A device whose address CAT may dial, as declared under {@code devices:}. */
    public record Device(
            @JsonProperty(value = "host", required = true) String host,
            @JsonProperty(value = "port", required = true) int port) {

        public Device {
            host = Hosts.validate(host);
            checkPort(port, "port");
        }
    }

    /**
     * A physical pinpad driven over VATS, with BTT simulating its cards (§7.4). The defaults are the
     * AQA page's ports, except {@code card_server_port}, which is 9050 (rig owner, 2026-10-06).
     */
    public record Vats(
            @JsonProperty(value = "host", required = true) String host,
            @JsonProperty("port") Integer port,
            @JsonProperty("km4_port") Integer km4Port,
            @JsonProperty("card_server_port") Integer cardServerPort,
            @JsonProperty("automation_port") Integer automationPort,
            @JsonProperty("interface") String interfaceKind,
            @JsonProperty("btt_version") String bttVersion,
            @JsonProperty("card_images") Map<String, String> cardImages) {

        public Vats {
            host = Hosts.validate(host);
            port = port == null ? 22001 : port;
            km4Port = km4Port == null ? 11000 : km4Port;
            cardServerPort = cardServerPort == null ? 9050 : cardServerPort;
            automationPort = automationPort == null ? 1983 : automationPort;
            checkPort(port, "port");
            checkPort(km4Port, "km4_port");
            checkPort(cardServerPort, "card_server_port");
            checkPort(automationPort, "automation_port");
            interfaceKind = interfaceKind == null ? "contact" : interfaceKind;
            if (!interfaceKind.equals("contact") && !interfaceKind.equals("contactless")) {
                throw new IllegalArgumentException("Input should be 'contact' or 'contactless', got '"
                        + interfaceKind + "'");
            }
            cardImages = cardImages == null ? Map.of() : Map.copyOf(cardImages);
        }
    }

    /** WireMock standalone (§7.3). {@code mappings} is relative to the rig file; see {@link LoadedRig}. */
    public record Wiremock(
            @JsonProperty("port") Integer port,
            @JsonProperty(value = "mappings", required = true) String mappings) {

        public Wiremock {
            port = port == null ? 8080 : port;
            checkPort(port, "port");
            if (mappings == null || mappings.isEmpty()) {
                throw new IllegalArgumentException("String should have at least 1 character");
            }
        }
    }

    /**
     * The expected platform build (§8), read as a string. A quoted {@code "057.00"} stays that
     * string; unquoted, YAML would make it the float {@code 57.0}, and the build would never match.
     */
    public record Build(
            @JsonProperty(value = "expected_version", required = true) String expectedVersion) {

        public Build {
            if (expectedVersion == null || expectedVersion.isEmpty()) {
                throw new IllegalArgumentException("String should have at least 1 character");
            }
        }
    }

    /**
     * The till account {@code preflight} signs on with (§8). {@code password_env} names an
     * environment variable and is the form to use: the rig file is committed. An inline
     * {@code password} is still accepted, as the Python CAT accepts it. {@link #secretSource()}
     * says which was used, and never shows the value.
     */
    public record Cashier(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty("password") String password,
            @JsonProperty("password_env") String passwordEnv) {

        public Cashier {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("String should have at least 1 character");
            }
            password = password == null ? "" : password;
            passwordEnv = passwordEnv == null ? "" : passwordEnv;
            if (!password.isEmpty() && !passwordEnv.isEmpty()) {
                throw new IllegalArgumentException("set either cashier.password or cashier.password_env, not both "
                        + "— two sources for one secret is a question about which one is live");
            }
        }

        /** {@code env:NAME}, {@code inline} or {@code none}. Safe to log: never the value. */
        public String secretSource() {
            if (!passwordEnv.isEmpty()) {
                return "env:" + passwordEnv;
            }
            return password.isEmpty() ? "none" : "inline";
        }

        /** The password, or empty when none is configured or the variable is unset. */
        public String resolvePassword(Map<String, String> environ) {
            if (!passwordEnv.isEmpty()) {
                String value = environ.get(passwordEnv);
                return value == null ? "" : value;
            }
            return password;
        }
    }

    /**
     * Bounded-wait defaults (§8), the mechanism behind §6.3: no unbounded blocking call exists in
     * the harness. A rig may omit the block or any key of it, and gets §8's own values.
     */
    public record Timeouts(
            @JsonProperty("default_wait") Duration defaultWait,
            @JsonProperty("prompt_wait") Duration promptWait,
            @JsonProperty("fuel_complete") Duration fuelComplete) {

        public Timeouts {
            defaultWait = defaultWait == null ? Duration.ofSeconds(15) : defaultWait;
            promptWait = promptWait == null ? Duration.ofSeconds(30) : promptWait;
            fuelComplete = fuelComplete == null ? Duration.ofSeconds(90) : fuelComplete;
        }

        /** §8's own values. */
        public static Timeouts defaults() {
            return new Timeouts(null, null, null);
        }

        /** The block as §8 wrote it, {@code {"default_wait": "15s", ...}}, for logs. */
        public Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("default_wait", Durations.format(defaultWait));
            m.put("prompt_wait", Durations.format(promptWait));
            m.put("fuel_complete", Durations.format(fuelComplete));
            return m;
        }
    }

    /** Which payment terminal the rig drives (DESIGN.md §7.4). Required: a stated fact about the bench. */
    public enum Pinpad {
        /** A physical Viper pinpad over VATS, with BTT simulating the cards. Needs a {@code vats:} block. */
        @JsonProperty("vats") VATS,
        /** Petro Simulator Suite's ViperPay pinpad simulator on the controller. */
        @JsonProperty("viperpay") VIPERPAY
    }

    /** What drives the rig's pumps and DCRs. Unset, the fixtures refuse rather than guess. */
    public enum Forecourt {
        /** Petro Simulator Suite's fuel and DCR simulators on the controller. */
        @JsonProperty("petro") PETRO
    }

    /** Which RCI endpoint the rig drives. Optional: a rig that runs no RCI tests has nothing to say. */
    public enum Rci {
        /** The live SOAP endpoint on the Commander. */
        @JsonProperty("commander") COMMANDER
    }

    /**
     * Pushes a throwaway browser onto a register's C18 for a test session (BROWSER_STRATEGY.md §5).
     * UNVERIFIED AGAINST HARDWARE (Java port): the Python CAT marks the feature unverified, its
     * glibc and kernel spike not yet run. It is per register, never rig-wide.
     */
    public enum BrowserOverride {
        /** A modern, portable Chromium build, pushed and toggled on for the session. */
        @JsonProperty("chromium") CHROMIUM
    }
}
