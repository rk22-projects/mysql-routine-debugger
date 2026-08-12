package com.rk22.routinedebugger.core.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** The single JDBC engine used for MySQL and compatible MariaDB servers. */
public enum DatabaseEngine {
    MYSQL("mysql", "MySQL", "jdbc:mysql://");

    private final String id;
    private final String displayName;
    private final String jdbcPrefix;

    DatabaseEngine(String id, String displayName, String jdbcPrefix) {
        this.id = id;
        this.displayName = displayName;
        this.jdbcPrefix = jdbcPrefix;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String jdbcUrl(String host, int port, String database) {
        return jdbcPrefix + host + ":" + port + "/" + database;
    }

    public Connection connect(String host, int port, String database,
                              String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        return DriverManager.getConnection(jdbcUrl(host, port, database), properties);
    }

    public static DatabaseEngine fromId(String value) {
        return MYSQL;
    }

    public static DatabaseEngine detect(Connection connection) throws SQLException {
        return MYSQL;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
