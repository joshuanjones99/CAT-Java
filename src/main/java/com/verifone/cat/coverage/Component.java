package com.verifone.cat.coverage;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One JVM's coverage: its exec file, and where its class files come from.
 *
 * <p>A port of {@code petrotest.coverage.components.Component}. UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param name       the JVM's name, which is also its exec file's stem (except a register's Tomcat)
 * @param side       {@code psc} (Commander) or {@code pos} (register): which side's packages hold its jars
 * @param classfiles jar or war file names; {@code {dist}} is replaced by the run's distribution
 * @param tcp        whether the exec file is pulled over TCP (Tomcat) rather than read off disk
 * @param packages   the overlay packages its class files come from, searched in order; empty means its own name
 */
public record Component(String name, String side, List<String> classfiles, boolean tcp, List<String> packages) {

    public Component {
        classfiles = List.copyOf(classfiles);
        packages = List.copyOf(packages);
    }

    /** The overlay packages to search: {@link #packages}, or the component's own name. */
    public List<String> sources() {
        return packages.isEmpty() ? List.of(name) : packages;
    }

    /** The jar and war names with the distribution substituted, as {@code fnmatch} patterns. */
    public List<String> patterns(String dist) {
        return classfiles.stream().map(pattern -> pattern.replace("{dist}", dist)).toList();
    }

    /** Whether {@code fileName} (for example {@code fcc.exec}) is this JVM's exec file. */
    public boolean execMatches(String fileName) {
        String stem = fileName.endsWith(".exec") ? fileName.substring(0, fileName.length() - ".exec".length())
                : fileName;
        if (name.equals(Components.POS_TOMCAT)) {
            return stem.equals(Components.POS_TOMCAT) || stem.startsWith(Components.POS_TOMCAT + "-");
        }
        return stem.equals(name);
    }

    /** {@code fnmatch}-style matching ({@code *} and {@code ?}), as the Python table's patterns use. */
    static boolean fnmatch(String name, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(name).matches();
    }
}
