package com.verifone.cat.devices.commander;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * The build installed on the Commander, read from its {@code suiteinfo.dat}: a port of
 * {@code petrotest.devices.suiteinfo}.
 *
 * <p>The file is {@code /home/coresvcs/config/suiteinfo.dat} (the Solutions overlay's {@code suiteinfo.vpk} puts it
 * there; the register's packages carry none). Its keys are {@code Suite_Name}, {@code Suite_Version},
 * {@code Base_Level}, {@code Commit_Hash}, {@code Timestamp} and so on, one {@code Key     =value} per line. It does not
 * hold the Solutions build number: two builds on one base level differ only in {@code Commit_Hash} and
 * {@code Timestamp}, which is what a Commander snapshot is chosen by.
 *
 * <p><b>Read-only.</b> One {@code cat} over {@link Transport#run}; never {@link Transport#get}, whose SFTP the Python
 * opens with a {@code setenforce 0} that changes the box.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The path comes from the package manifest, not from a listing of the lab
 * Commander, and the Python reader has run only against an in-memory transport.
 */
public final class CommanderSuiteInfo {

    /** Where the Commander keeps it. */
    public static final String SUITEINFO_PATH = "/home/coresvcs/config/suiteinfo.dat";

    private CommanderSuiteInfo() {}

    /** {@code Key     =value} lines to a map, in file order. {@code #} lines and lines with no {@code =} are skipped. */
    public static Map<String, String> parse(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.split("\\r\\n|\\r|\\n|\\u000b|\\u000c|\\u001c|\\u001d|\\u001e|\\u0085|\\u2028|\\u2029")) {
            int eq = line.indexOf('=');
            if (eq >= 0 && !line.stripLeading().startsWith("#")) {
                fields.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return fields;
    }

    /**
     * The Commander's {@code suiteinfo.dat}, as a map that always has {@code Base_Level}.
     *
     * @param transports a transport to the Commander, connected or connecting on first use; closed here
     * @throws DeviceError the file is missing or has no {@code Base_Level}; a transport failure propagates as it is
     */
    public static Map<String, String> read(Supplier<? extends Transport> transports, Duration timeout) {
        Objects.requireNonNull(transports, "transports");
        Transport transport = transports.get();
        CommandResult result;
        try {
            result = transport.run("cat " + SUITEINFO_PATH, timeout);
        } finally {
            transport.close();
        }
        if (!result.ok()) {
            String stderr = result.stderr() == null ? "" : result.stderr().strip();
            throw new DeviceError("could not read " + SUITEINFO_PATH + " on " + transport.host() + ": exit "
                    + result.exitStatus() + ": " + stderr.substring(0, Math.min(300, stderr.length())));
        }
        Map<String, String> fields = parse(result.stdout() == null ? "" : result.stdout());
        String level = fields.get("Base_Level");
        if (level == null || level.isEmpty()) {
            throw new DeviceError(SUITEINFO_PATH + " on " + transport.host() + " has no Base_Level");
        }
        return fields;
    }
}
