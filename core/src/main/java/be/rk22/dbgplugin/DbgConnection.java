package be.rk22.dbgplugin;

import java.sql.*;
import java.util.*;

/**
 * Wraps a live JDBC connection and exposes all debugger DB operations.
 * Every public method that talks to the DB throws DbgException on failure.
 * The caller owns the lifecycle of the underlying Connection.
 */
public class DbgConnection {

    // ── Schema DDL ────────────────────────────────────────────────────────────

    private static final String[] SETUP_TABLES = {
        "DROP TABLE IF EXISTS _dbg_log",
        "CREATE TABLE _dbg_log (" +
        "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
        "  ts DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)," +
        "  session_id VARCHAR(64)," +
        "  routine_name VARCHAR(255)," +
        "  label VARCHAR(255)," +
        "  var_name VARCHAR(255)," +
        "  var_value MEDIUMTEXT," +
        "  INDEX idx_sess_id (session_id, id)" +
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        "DROP TABLE IF EXISTS _dbg_breakpoints",
        "CREATE TABLE _dbg_breakpoints (" +
        "  routine_name VARCHAR(255)," +
        "  label VARCHAR(255)," +
        "  PRIMARY KEY (routine_name, label)" +
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        "DROP TABLE IF EXISTS _dbg_state",
        "CREATE TABLE _dbg_state (" +
        "  session_id VARCHAR(64) PRIMARY KEY," +
        "  routine_name VARCHAR(255)," +
        "  status VARCHAR(50) DEFAULT 'running'" +
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        "CREATE TABLE IF NOT EXISTS _dbg_originals (" +
        "  routine_name VARCHAR(255) PRIMARY KEY," +
        "  routine_type VARCHAR(20)," +
        "  original_ddl MEDIUMTEXT," +
        "  session_id VARCHAR(64)," +
        "  saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    };

    private static final String[] SETUP_PROCS = {
        "DROP PROCEDURE IF EXISTS _dbg_log_var",

        "CREATE PROCEDURE _dbg_log_var(" +
        "  IN p_session  VARCHAR(64)," +
        "  IN p_routine  VARCHAR(255)," +
        "  IN p_label    VARCHAR(255)," +
        "  IN p_var      VARCHAR(255)," +
        "  IN p_val      MEDIUMTEXT" +
        ")\n" +
        "BEGIN\n" +
        "  INSERT INTO _dbg_log (session_id, routine_name, label, var_name, var_value)\n" +
        "  VALUES (p_session, p_routine, p_label, p_var, p_val);\n" +
        "END",

        "DROP PROCEDURE IF EXISTS _dbg_checkpoint",

        "CREATE PROCEDURE _dbg_checkpoint(" +
        "  IN p_session VARCHAR(64)," +
        "  IN p_routine VARCHAR(255)," +
        "  IN p_label   VARCHAR(255)" +
        ")\n" +
        "BEGIN\n" +
        "  DECLARE v_is_bp  INT DEFAULT 0;\n" +
        "  DECLARE v_status VARCHAR(50) DEFAULT 'running';\n" +
        "\n" +
        "  SELECT status INTO v_status FROM _dbg_state WHERE session_id = p_session;\n" +
        "\n" +
        "  SELECT COUNT(*) INTO v_is_bp\n" +
        "  FROM _dbg_breakpoints\n" +
        "  WHERE routine_name = p_routine AND label = p_label;\n" +
        "\n" +
        "  IF v_is_bp > 0 OR v_status = 'step' THEN\n" +
        "    INSERT INTO _dbg_state (session_id, routine_name, status)\n" +
        "    VALUES (p_session, p_routine, 'paused')\n" +
        "    ON DUPLICATE KEY UPDATE routine_name = p_routine, status = 'paused';\n" +
        "\n" +
        "    INSERT INTO _dbg_log (session_id, routine_name, label, var_name, var_value)\n" +
        "    VALUES (p_session, p_routine, p_label, '__BREAKPOINT__', p_label);\n" +
        "\n" +
        "    -- Commit so the plugin's polling connection can see 'paused' and the log entry.\n" +
        "    -- InnoDB REPEATABLE READ means uncommitted writes are invisible to other sessions.\n" +
        "    COMMIT;\n" +
        "\n" +
        "    SET v_status = 'paused';\n" +
        "    WHILE v_status = 'paused' DO\n" +
        "      DO SLEEP(0.2);\n" +
        "      -- COMMIT ends the current MVCC snapshot so the next SELECT reads the\n" +
        "      -- latest committed row written by the plugin (e.g. 'continue'/'step').\n" +
        "      COMMIT;\n" +
        "      SELECT status INTO v_status FROM _dbg_state WHERE session_id = p_session;\n" +
        "    END WHILE;\n" +
        "\n" +
        "    IF v_status = 'continue' THEN\n" +
        "      UPDATE _dbg_state SET status = 'running' WHERE session_id = p_session;\n" +
        "    END IF;\n" +
        "  END IF;\n" +
        "END"
    };

    // ── Instance ──────────────────────────────────────────────────────────────

    private final Connection conn;

    public DbgConnection(Connection conn) {
        this.conn = conn;
    }

    public Connection getConnection() { return conn; }

    // ── Infrastructure setup ──────────────────────────────────────────────────

    public synchronized void setupInfrastructure() throws DbgException {
        try (Statement st = conn.createStatement()) {
            for (String sql : SETUP_TABLES) st.execute(sql);
            for (String sql : SETUP_PROCS)  st.execute(sql);
        } catch (SQLException e) {
            throw new DbgException("Failed to set up debug infrastructure: " + e.getMessage(), e);
        }
    }

    // ── Routine metadata ──────────────────────────────────────────────────────

    public List<RoutineInfo> fetchRoutines(String schema) throws DbgException {
        String sql =
            "SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES " +
            "WHERE ROUTINE_SCHEMA = ? " +
            "  AND ROUTINE_NAME NOT LIKE '\\_dbg\\_%' ESCAPE '\\\\' " +
            "  AND ROUTINE_NAME NOT LIKE '\\_orig\\_%' ESCAPE '\\\\' " +
            "ORDER BY ROUTINE_TYPE, ROUTINE_NAME";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ResultSet rs = ps.executeQuery();
            List<RoutineInfo> result = new ArrayList<>();
            while (rs.next()) result.add(new RoutineInfo(rs.getString(1), rs.getString(2)));
            return result;
        } catch (SQLException e) {
            throw new DbgException("Failed to fetch routines: " + e.getMessage(), e);
        }
    }

    /** Returns the raw DDL from SHOW CREATE PROCEDURE/FUNCTION. */
    public String fetchRoutineDdl(String name, String type) throws DbgException {
        String sql = "SHOW CREATE " + ("FUNCTION".equalsIgnoreCase(type) ? "FUNCTION" : "PROCEDURE")
                   + " `" + name + "`";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                // Column 3 for PROCEDURE ("Create Procedure"), col 3 for FUNCTION ("Create Function")
                return rs.getString(3);
            }
            throw new DbgException("Routine not found: " + name);
        } catch (SQLException e) {
            throw new DbgException("Failed to fetch DDL for " + name + ": " + e.getMessage(), e);
        }
    }

    public String fetchCurrentSchema() throws DbgException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
            if (rs.next()) return rs.getString(1);
            throw new DbgException("Could not determine current schema");
        } catch (SQLException e) {
            throw new DbgException("Failed to get schema: " + e.getMessage(), e);
        }
    }

    // ── Originals store ───────────────────────────────────────────────────────

    public void saveOriginal(String name, String type, String ddl, String sessionId)
            throws DbgException {
        String sql =
            "INSERT INTO _dbg_originals (routine_name, routine_type, original_ddl, session_id) " +
            "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE " +
            "routine_type=VALUES(routine_type), original_ddl=VALUES(original_ddl), " +
            "session_id=VALUES(session_id)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name); ps.setString(2, type);
            ps.setString(3, ddl);  ps.setString(4, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbgException("Failed to save original: " + e.getMessage(), e);
        }
    }

    /** Returns original DDL, or null if not deployed. */
    public String loadOriginalDdl(String name) throws DbgException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT original_ddl FROM _dbg_originals WHERE routine_name = ?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new DbgException("Failed to load original: " + e.getMessage(), e);
        }
    }

    public String loadSessionId(String name) throws DbgException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT session_id FROM _dbg_originals WHERE routine_name = ?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new DbgException("Failed to load session_id: " + e.getMessage(), e);
        }
    }

    public boolean isDeployed(String name) throws DbgException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM _dbg_originals WHERE routine_name = ?")) {
            ps.setString(1, name);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            throw new DbgException("Failed to check deploy status: " + e.getMessage(), e);
        }
    }

    // ── Deploy / restore ──────────────────────────────────────────────────────

    /**
     * Execute the three-routine swap:
     *   1. Save DDL to _dbg_originals
     *   2. Create _orig_name (backup copy)
     *   3. Create _dbg_name (instrumented copy)
     *   4. Replace name with thin proxy
     */
    public void deployDebug(String name, String type,
                            String originalDdl,
                            String origCopyDdl,
                            String dbgDdl,
                            String proxyDdl,
                            String sessionId) throws DbgException {
        try (Statement st = conn.createStatement()) {
            saveOriginal(name, type, originalDdl, sessionId);
            st.execute("DROP PROCEDURE IF EXISTS `_orig_" + name + "`");
            st.execute("DROP FUNCTION  IF EXISTS `_orig_" + name + "`");
            st.execute(origCopyDdl);
            st.execute("DROP PROCEDURE IF EXISTS `_dbg_" + name + "`");
            st.execute("DROP FUNCTION  IF EXISTS `_dbg_" + name + "`");
            st.execute(dbgDdl);
            st.execute("DROP PROCEDURE IF EXISTS `" + name + "`");
            st.execute("DROP FUNCTION  IF EXISTS `" + name + "`");
            st.execute(proxyDdl);
        } catch (SQLException e) {
            throw new DbgException("Deploy failed: " + e.getMessage(), e);
        }
    }

    public void restoreOriginal(String name, String type, String originalDdl) throws DbgException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP PROCEDURE IF EXISTS `" + name + "`");
            st.execute("DROP FUNCTION  IF EXISTS `" + name + "`");
            st.execute("DROP PROCEDURE IF EXISTS `_dbg_" + name + "`");
            st.execute("DROP FUNCTION  IF EXISTS `_dbg_" + name + "`");
            st.execute("DROP PROCEDURE IF EXISTS `_orig_" + name + "`");
            st.execute("DROP FUNCTION  IF EXISTS `_orig_" + name + "`");
            st.execute(originalDdl);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM _dbg_originals WHERE routine_name = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new DbgException("Restore failed: " + e.getMessage(), e);
        }
    }

    // ── Execution control ─────────────────────────────────────────────────────

    public synchronized void initSessionState(String sessionId, String routineName) throws DbgException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO _dbg_state (session_id, routine_name, status) VALUES (?, ?, 'running') " +
                "ON DUPLICATE KEY UPDATE status = 'running'")) {
            ps.setString(1, sessionId);
            ps.setString(2, routineName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbgException("Failed to initialize session state: " + e.getMessage(), e);
        }
    }

    public synchronized void updateState(String sessionId, String status) throws DbgException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO _dbg_state (session_id, routine_name, status) VALUES (?, '', ?) " +
                "ON DUPLICATE KEY UPDATE status = VALUES(status)")) {
            ps.setString(1, sessionId);
            ps.setString(2, status);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbgException("Failed to update state: " + e.getMessage(), e);
        }
    }

    // ── Poll ──────────────────────────────────────────────────────────────────

    public synchronized PollResult pollLog(String sessionId, long sinceId) throws DbgException {
        List<LogEntry> entries = new ArrayList<>();
        try {
            String logSql =
                "SELECT id, ts, session_id, routine_name, label, var_name, var_value " +
                "FROM _dbg_log WHERE session_id = ? AND id > ? ORDER BY id LIMIT 200";
            try (PreparedStatement ps = conn.prepareStatement(logSql)) {
                ps.setString(1, sessionId);
                ps.setLong(2, sinceId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    entries.add(new LogEntry(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)
                    ));
                }
            }
            boolean paused   = false;
            String  pausedAt = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, routine_name FROM _dbg_state WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) paused = "paused".equals(rs.getString(1));
            }
            if (paused) {
                String bpSql =
                    "SELECT var_value FROM _dbg_log " +
                    "WHERE session_id = ? AND var_name = '__BREAKPOINT__' " +
                    "ORDER BY id DESC LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(bpSql)) {
                    ps.setString(1, sessionId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) pausedAt = rs.getString(1);
                }
            }
            return new PollResult(entries, paused, pausedAt);
        } catch (SQLException e) {
            throw new DbgException("Poll failed: " + e.getMessage(), e);
        }
    }

    // ── Breakpoints ───────────────────────────────────────────────────────────

    public List<String> loadBreakpoints(String routineName) throws DbgException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT label FROM _dbg_breakpoints WHERE routine_name = ?")) {
            ps.setString(1, routineName);
            ResultSet rs = ps.executeQuery();
            List<String> labels = new ArrayList<>();
            while (rs.next()) labels.add(rs.getString(1));
            return labels;
        } catch (SQLException e) {
            throw new DbgException("Failed to load breakpoints: " + e.getMessage(), e);
        }
    }

    public void saveBreakpoints(String routineName, Collection<String> labels) throws DbgException {
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM _dbg_breakpoints WHERE routine_name = ?")) {
            del.setString(1, routineName);
            del.executeUpdate();
        } catch (SQLException e) {
            throw new DbgException("Failed to clear breakpoints: " + e.getMessage(), e);
        }
        if (labels.isEmpty()) return;
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO _dbg_breakpoints (routine_name, label) VALUES (?,?)")) {
            for (String lbl : labels) {
                ins.setString(1, routineName);
                ins.setString(2, lbl);
                ins.addBatch();
            }
            ins.executeBatch();
        } catch (SQLException e) {
            throw new DbgException("Failed to save breakpoints: " + e.getMessage(), e);
        }
    }

    // ── Full reset ────────────────────────────────────────────────────────────

    /**
     * Reverts every deployed routine to its original DDL, drops all _dbg_* and
     * _orig_* routines, and tears down the entire debug infrastructure.
     * Safe to call even if infrastructure tables don't exist yet.
     */
    public synchronized void restoreAll(String schema) throws DbgException {
        // Unblock any paused/stepping DB session so the caller's WHILE loop exits
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE _dbg_state SET status = 'continue' WHERE status IN ('paused', 'step')");
        } catch (SQLException ignored) {
            // table may not exist yet — that is fine
        }

        // 1. Restore every backed-up routine from _dbg_originals
        List<String[]> originals = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT routine_name, routine_type, original_ddl FROM _dbg_originals")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                originals.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
        } catch (SQLException ignored) {
            // table may not exist — that's fine
        }
        try (Statement st = conn.createStatement();
             PreparedStatement del = conn.prepareStatement(
                     "DELETE FROM _dbg_originals WHERE routine_name = ?")) {
            for (String[] row : originals) {
                String name = row[0], type = row[1], ddl = row[2];
                st.execute("DROP PROCEDURE IF EXISTS `" + name + "`");
                st.execute("DROP FUNCTION  IF EXISTS `" + name + "`");
                st.execute("DROP PROCEDURE IF EXISTS `_dbg_" + name + "`");
                st.execute("DROP FUNCTION  IF EXISTS `_dbg_" + name + "`");
                st.execute("DROP PROCEDURE IF EXISTS `_orig_" + name + "`");
                st.execute("DROP FUNCTION  IF EXISTS `_orig_" + name + "`");
                st.execute(ddl);
                del.setString(1, name);
                del.executeUpdate();
            }
        } catch (SQLException e) {
            throw new DbgException("Failed to restore originals: " + e.getMessage(), e);
        }

        // 2. Drop any remaining _dbg_* / _orig_* routines not in originals
        List<String[]> leftover = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES " +
                "WHERE ROUTINE_SCHEMA = ? " +
                "  AND (ROUTINE_NAME LIKE '\\_dbg\\_%' ESCAPE '\\\\' " +
                "    OR ROUTINE_NAME LIKE '\\_orig\\_%' ESCAPE '\\\\')")) {
            ps.setString(1, schema);
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                leftover.add(new String[]{rs.getString(1), rs.getString(2)});
        } catch (SQLException e) {
            throw new DbgException("Failed to query leftover routines: " + e.getMessage(), e);
        }
        try (Statement st = conn.createStatement()) {
            for (String[] row : leftover) {
                String drop = "FUNCTION".equalsIgnoreCase(row[1])
                    ? "DROP FUNCTION  IF EXISTS" : "DROP PROCEDURE IF EXISTS";
                st.execute(drop + " `" + row[0] + "`");
            }
        } catch (SQLException e) {
            throw new DbgException("Failed to drop leftover routines: " + e.getMessage(), e);
        }

        // 3. Tear down transient debug infrastructure — _dbg_originals is intentionally kept
        //    so unrestored routines can be recovered after a crash or manual stop.
        try (Statement st = conn.createStatement()) {
            st.execute("DROP PROCEDURE IF EXISTS `_dbg_checkpoint`");
            st.execute("DROP PROCEDURE IF EXISTS `_dbg_log_var`");
            st.execute("DROP TABLE IF EXISTS `_dbg_log`");
            st.execute("DROP TABLE IF EXISTS `_dbg_breakpoints`");
            st.execute("DROP TABLE IF EXISTS `_dbg_state`");
        } catch (SQLException e) {
            throw new DbgException("Failed to drop debug infrastructure: " + e.getMessage(), e);
        }
    }

    // ── Log management ────────────────────────────────────────────────────────

    public synchronized void clearLog(String sessionId) throws DbgException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM _dbg_log WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbgException("Failed to clear log: " + e.getMessage(), e);
        }
    }

    public String latestVarValue(String sessionId, String varName) throws DbgException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT var_value FROM _dbg_log " +
                "WHERE session_id = ? AND var_name = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, sessionId);
            ps.setString(2, varName);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new DbgException("Failed to fetch var value: " + e.getMessage(), e);
        }
    }
}
