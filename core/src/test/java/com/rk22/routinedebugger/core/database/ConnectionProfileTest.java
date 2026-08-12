package com.rk22.routinedebugger.core.database;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConnectionProfileTest {
    @Test
    public void nullTextValuesAreNormalized() {
        ConnectionProfile profile = new ConnectionProfile(null, 3306, null, null, null);
        assertEquals("localhost", profile.host());
        assertEquals("", profile.user());
        assertEquals("", profile.password());
        assertEquals("", profile.database());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidPortsAreRejected() {
        new ConnectionProfile("localhost", 70000, "user", "secret", "db");
    }
}
