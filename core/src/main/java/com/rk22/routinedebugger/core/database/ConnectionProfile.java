package com.rk22.routinedebugger.core.database;

/** Values shown by a frontend's connection dialog. */
public record ConnectionProfile(String host, int port,
                                String user, String password, String database) {
    public ConnectionProfile {
        host = value(host, "localhost");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
        user = value(user, "");
        password = value(password, "");
        database = value(database, "");
    }

    public static ConnectionProfile defaults() {
        return new ConnectionProfile("localhost", 3306, "", "", "");
    }

    private static String value(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
