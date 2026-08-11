package com.rk22.dbgplugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DatabaseEngineTest {
    @Test
    public void mysqlIsTheDefaultEngine() {
        assertEquals(DatabaseEngine.MYSQL, DatabaseEngine.fromId(null));
        assertEquals(DatabaseEngine.MYSQL, DatabaseEngine.fromId("unknown"));
    }

    @Test
    public void engineIdsAreCaseInsensitive() {
        assertEquals(DatabaseEngine.MYSQL, DatabaseEngine.fromId("MySQL"));
        assertEquals(DatabaseEngine.MARIADB, DatabaseEngine.fromId("MARIADB"));
    }

    @Test
    public void jdbcUrlsUseTheMatchingScheme() {
        assertEquals("jdbc:mysql://db.example:3306/app",
                     DatabaseEngine.MYSQL.jdbcUrl("db.example", 3306, "app"));
        assertEquals("jdbc:mariadb://localhost:3307/test",
                     DatabaseEngine.MARIADB.jdbcUrl("localhost", 3307, "test"));
    }
}
