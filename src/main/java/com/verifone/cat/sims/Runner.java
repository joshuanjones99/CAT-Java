package com.verifone.cat.sims;

/**
 * How a registry row's process is launched.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python CAT's {@code runner:} field.
 */
public enum Runner {
    /** {@code <java_home>/jdk*}{@code /bin/javaw.exe}, the highest version under the Java 17 set. */
    JAVAW,
    /** The row's own executable, given as a path. */
    EXE
}
