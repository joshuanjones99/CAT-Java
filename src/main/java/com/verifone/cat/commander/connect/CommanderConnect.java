package com.verifone.cat.commander.connect;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The five values {@code petrotest commander-connect} takes: the lab, the sign-in email, the Commander's service id,
 * and the sign-in password. A port of the Python {@code petrotest.commander_connect.CommanderConnect}.
 *
 * <p>The password is never printed: {@link #toString} hides it, as the Python's {@code repr} does.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Every step is read from petroapps' source, none has run against a
 * Commander.
 *
 * @param lab       one of {@link #LABS}
 * @param email     the Commander Connect sign-in; must contain {@code @}
 * @param serviceId the Commander's service id, 7 to 20 digits
 * @param password  the sign-in password, from {@link #PASSWORD_ENV} on the command line
 */
public record CommanderConnect(String lab, String email, String serviceId, String password) {

    /** The environments the Config tab offers. The Python's {@code cat.launch.config.COMMANDER_CONNECT_LABS}. */
    public static final List<String> LABS = List.of("Prod", "CST", "QAT", "DEV");

    /** Where the password is read from; never argv. */
    public static final String PASSWORD_ENV = "COMMANDER_CONNECT_PASSWORD";

    /** {@code RegistrationHandlerImpl}: "a string of only digits from 7 to 20 characters long". */
    private static final Pattern SERVICE_ID = Pattern.compile("\\d{7,20}");

    public CommanderConnect {
        lab = lab == null ? "" : lab;
        email = email == null ? "" : email;
        serviceId = serviceId == null ? "" : serviceId;
        password = password == null ? "" : password;
    }

    /** What is wrong with these values, named; empty when they are usable. */
    public List<String> problems() {
        java.util.ArrayList<String> found = new java.util.ArrayList<>();
        if (!LABS.contains(lab)) {
            found.add("lab '" + lab + "' is not one of " + String.join(", ", LABS));
        }
        if (!email.contains("@")) {
            found.add("email is empty or not an address");
        }
        if (password.isEmpty()) {
            found.add("password is empty (set it on the Config tab or $" + PASSWORD_ENV + ")");
        }
        if (!SERVICE_ID.matcher(serviceId).matches()) {
            found.add("service id must be 7 to 20 digits");
        }
        return List.copyOf(found);
    }

    @Override
    public String toString() {
        return "CommanderConnect[lab=" + lab + ", email=" + email + ", serviceId=" + serviceId + ", password=***]";
    }
}
