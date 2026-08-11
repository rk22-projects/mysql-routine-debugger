package com.rk22.dbgplugin;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DbgConnectionTest {

    @Test
    public void checkpointIdentifierComparisonsAreCollationIndependent() throws Exception {
        List<String> executed = new ArrayList<>();

        Statement statement = (Statement) Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[]{Statement.class},
            (proxy, method, args) -> {
                if ("execute".equals(method.getName())) {
                    executed.add((String) args[0]);
                    return false;
                }
                return defaultValue(method.getReturnType());
            });

        Connection connection = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if ("createStatement".equals(method.getName())) return statement;
                return defaultValue(method.getReturnType());
            });

        new DbgConnection(connection).setupInfrastructure();

        String setupSql = String.join("\n", executed);
        assertTrue(setupSql.contains(
            "CAST(session_id AS BINARY) = CAST(p_session AS BINARY)"));
        assertTrue(setupSql.contains(
            "CAST(routine_name AS BINARY) = CAST(p_routine AS BINARY)"));
        assertTrue(setupSql.contains(
            "CAST(label AS BINARY) = CAST(p_label AS BINARY)"));
        assertFalse(setupSql.contains("WHERE session_id = p_session"));
        assertFalse(setupSql.contains("WHERE routine_name = p_routine"));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
