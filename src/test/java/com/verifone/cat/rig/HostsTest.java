package com.verifone.cat.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Host validation: literals and names only, never a scheme, port or path, and never a lookup. */
class HostsTest {

    @Test
    void acceptsIpv4AndNamesAndStripsSpace() {
        assertEquals("10.0.0.101", Hosts.validate("10.0.0.101"));
        assertEquals("10.0.0.101", Hosts.validate("  10.0.0.101 "));
        assertEquals("topaz101.rig.example", Hosts.validate("topaz101.rig.example"));
    }

    @Test
    void acceptsIpv6Literals() {
        assertEquals("::1", Hosts.validate("::1"));
        assertEquals("fe80::1", Hosts.validate("fe80::1"));
        assertEquals("2001:db8:0:0:0:0:0:1", Hosts.validate("2001:db8:0:0:0:0:0:1"));
        assertEquals("::ffff:10.0.0.1", Hosts.validate("::ffff:10.0.0.1"));
        assertEquals("::1.2.3.4", Hosts.validate("::1.2.3.4"));
        assertEquals("1:2:3:4:5:6:10.0.0.1", Hosts.validate("1:2:3:4:5:6:10.0.0.1"));
        assertEquals("1::2.3.4.5", Hosts.validate("1::2.3.4.5"));
    }

    @Test
    void rejectsMalformedIpv6() {
        assertFalse(Hosts.isIpv6("1:2:3"));
        assertFalse(Hosts.isIpv6(":1"));
        assertFalse(Hosts.isIpv6("1:::2"));
        assertFalse(Hosts.isIpv6("1:2:3:4:5:6:7:8:9"));
        assertFalse(Hosts.isIpv6("gggg::1"));
    }

    @Test
    void anIpv4LookingStringThatIsNotOneIsSaidSo() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Hosts.validate("192.168.31.999"));
        assertTrue(e.getMessage().contains("expected a valid IPv4 address"), e.getMessage());
    }

    @Test
    void aSchemeOrPortOrPathIsRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Hosts.validate("http://10.0.0.1"))
                .getMessage().contains("no scheme, port or path"));
        assertThrows(IllegalArgumentException.class, () -> Hosts.validate("10.0.0.1/x"));
        assertThrows(IllegalArgumentException.class, () -> Hosts.validate("host with space"));
    }

    @Test
    void aHostWithAPortIsNotAHostname() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Hosts.validate("topaz101:9222"));
        assertTrue(e.getMessage().contains("expected a hostname or IP address"), e.getMessage());
    }

    @Test
    void emptyAndNonStringAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Hosts.validate("   "));
        assertThrows(IllegalArgumentException.class, () -> Hosts.validate(10));
    }
}
