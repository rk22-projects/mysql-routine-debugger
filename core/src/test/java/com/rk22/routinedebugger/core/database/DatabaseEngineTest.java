package com.rk22.routinedebugger.core.database;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DatabaseEngineTest {
    @Test
    public void mysqlIsTheDefaultEngine() {
        assertEquals(DatabaseEngine.MYSQL, DatabaseEngine.fromId(null));
        assertEquals(DatabaseEngine.MYSQL, DatabaseEngine.fromId("unknown"));
    }

    @Test
    public void legacyEngineIdsAlwaysSelectMysqlConnector() {
        assertEquals(DatabaseEngine.MYSQL, DatabaseEngine.fromId("MySQL"));
        assertEquals(DatabaseEngine.MYSQL, DatabaseEngine.fromId("MARIADB"));
    }

    @Test
    public void jdbcUrlsUseTheMatchingScheme() {
        assertEquals("jdbc:mysql://db.example:3306/app",
                     DatabaseEngine.MYSQL.jdbcUrl("db.example", 3306, "app"));
    }
}
