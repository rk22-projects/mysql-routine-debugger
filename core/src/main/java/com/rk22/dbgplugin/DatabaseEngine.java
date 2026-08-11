package com.rk22.dbgplugin;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

/** Supported database engines and their JDBC connection details. */
public enum DatabaseEngine {
    MYSQL("mysql", "MySQL", "jdbc:mysql://"),
    MARIADB("mariadb", "MariaDB", "jdbc:mariadb://");

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
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (DatabaseEngine engine : values()) {
                if (engine.id.equals(normalized) ||
                    engine.displayName.toLowerCase(Locale.ROOT).equals(normalized)) {
                    return engine;
                }
            }
        }
        return MYSQL;
    }

    public static DatabaseEngine detect(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        return product != null && product.toLowerCase(Locale.ROOT).contains("mariadb")
            ? MARIADB : MYSQL;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
