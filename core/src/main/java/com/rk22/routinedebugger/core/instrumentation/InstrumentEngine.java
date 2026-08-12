package com.rk22.routinedebugger.core.instrumentation;

import com.rk22.routinedebugger.core.DbgException;

import java.sql.*;
import java.util.*;
import java.util.regex.*;

/**
 * Ports instrument_auto() and helpers from debugger.py.
 * Stateless — all methods are static.
 */
public class InstrumentEngine {

    // ── Variable collection ───────────────────────────────────────────────────

    public static class InstrumentVars {
        public final List<String> params;   // parameter names only
        public final List<String> allVars;  // params + declared variables (deduped)
        public InstrumentVars(List<String> params, List<String> allVars) {
            this.params  = params;
            this.allVars = allVars;
        }
    }

    /**
     * Port of collect_variables().
     * Queries information_schema.PARAMETERS for param names, then regex-scans DDL for DECLARE.
     */
    public static InstrumentVars collectVariables(String ddl, Connection conn,
                                                  String schema, String routineName)
            throws DbgException {
        List<String> params = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT PARAMETER_NAME FROM information_schema.PARAMETERS " +
                "WHERE SPECIFIC_SCHEMA = ? AND SPECIFIC_NAME = ? " +
                "  AND ORDINAL_POSITION > 0 ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, routineName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String p = rs.getString(1);
                if (p != null && !p.isEmpty()) params.add(p);
            }
        } catch (SQLException e) {
            throw new DbgException("Failed to query parameters: " + e.getMessage(), e);
        }

        return collectVariables(ddl, params);
    }

    /** Collect variables using parameter metadata already loaded by the caller. */
    public static InstrumentVars collectVariables(String ddl, Collection<String> parameterNames) {
        List<String> params = new ArrayList<>(parameterNames);
        List<String> declared = new ArrayList<>();
        Pattern declPat  = Pattern.compile("(?i)\\bDECLARE\\b\\s+");
        Pattern handlerPat = Pattern.compile("(?i)\\b(CONTINUE|EXIT|UNDO)\\s+HANDLER\\b");
        Pattern cursorPat  = Pattern.compile("(?i)\\bCURSOR\\b");
        Pattern wordPat    = Pattern.compile("\\w+");

        Matcher m = declPat.matcher(ddl);
        while (m.find()) {
            int afterDeclare = m.end();
            String rest = ddl.substring(afterDeclare).stripLeading();
            if (handlerPat.matcher(rest).lookingAt()) continue;
            if (cursorPat.matcher(rest).find() && rest.indexOf('\n') < 0) continue;
            Matcher wm = wordPat.matcher(rest);
            if (wm.find()) {
                String varName = wm.group();
                if (!params.contains(varName) && !declared.contains(varName))
                    declared.add(varName);
            }
        }

        List<String> allVars = new ArrayList<>(params);
        for (String v : declared) if (!allVars.contains(v)) allVars.add(v);
        return new InstrumentVars(params, allVars);
    }

    // ── Line classification ───────────────────────────────────────────────────

    private static final Pattern END_BLOCK = Pattern.compile(
        "(?i)^END(\\s+(IF|WHILE|LOOP|REPEAT|CASE))?[\\s;]*$");

    /** Port of is_executable_line(). */
    public static boolean isExecutableLine(String stripped) {
        if (stripped.isEmpty()) return false;
        String up = stripped.toUpperCase();
        if (up.startsWith("--") || up.startsWith("#") ||
            up.startsWith("/*") || up.startsWith("*/")) return false;
        if (up.startsWith("CREATE ") || up.startsWith("DEFINER") ||
            up.startsWith("DECLARE ") || up.equals("BEGIN")) return false;
        if (END_BLOCK.matcher(stripped).matches()) return false;
        return true;
    }

    /** Port of _stmt_complete(). */
    public static boolean stmtComplete(String stripped) {
        if (stripped.endsWith(";")) return true;
        String up = stripped.toUpperCase().stripTrailing();
        // IF / ELSEIF / WHILE / FOR … THEN|DO  (must start with these keywords)
        if (Pattern.compile("^(IF|ELSEIF|WHILE|FOR)\\b").matcher(up).find() &&
            Pattern.compile("\\b(THEN|DO)\\s*$").matcher(up).find()) return true;
        // bare ELSE
        if (Pattern.compile("^ELSE\\s*$").matcher(up).matches()) return true;
        // LOOP or REPEAT, optionally preceded by a label  (e.g. "read_loop: LOOP")
        if (Pattern.compile("^(?:\\w+\\s*:\\s*)?(LOOP|REPEAT)\\s*$").matcher(up).matches()) return true;
        return false;
    }

    // ── Assignment detection ──────────────────────────────────────────────────

    private static final Pattern SET_ASSIGN = Pattern.compile("\\b(\\w+)\\s*:?=");
    private static final Pattern INTO_CLAUSE = Pattern.compile(
        "(?i)\\bINTO\\b([^;\\n]+?)(?:\\bFROM\\b|;|$)");

    /** Port of detect_assigned_vars(). */
    public static List<String> detectAssignedVars(String stripped, List<String> allVars) {
        List<String> result = new ArrayList<>();
        String up = stripped.toUpperCase();

        if (up.contains("SET ")) {
            Matcher m = SET_ASSIGN.matcher(stripped);
            while (m.find()) {
                String name = m.group(1);
                if (!name.equalsIgnoreCase("SET") && allVars.contains(name) && !result.contains(name))
                    result.add(name);
            }
        }
        if (up.contains("INTO")) {
            Matcher m = INTO_CLAUSE.matcher(stripped);
            if (m.find()) {
                String[] tokens = m.group(1).split("[\\s,`]+");
                for (String tok : tokens) {
                    String clean = tok.replaceAll("`", "").trim();
                    if (!clean.isEmpty() && allVars.contains(clean) && !result.contains(clean))
                        result.add(clean);
                }
            }
        }
        return result;
    }

    // ── DDL transforms ────────────────────────────────────────────────────────

    private static final Pattern DEFINER_PAT = Pattern.compile(
        "(?i)(CREATE)\\s+DEFINER\\s*=\\s*`[^`]*`\\s*@\\s*`[^`]*`\\s*");

    public static String stripDefiner(String ddl) {
        return DEFINER_PAT.matcher(ddl).replaceFirst("$1 ");
    }

    // ── Main transform ────────────────────────────────────────────────────────

    /**
     * Port of instrument_auto(). Injects CALL _dbg_checkpoint and CALL _dbg_log_var
     * at statement boundaries, respecting DECLARE blocks and CASE WHEN ... THEN.
     * Returns the instrumented DDL renamed to _dbg_<name>.
     */
    public static String instrumentAuto(String name, String ddl, String sessionId,
                                        Connection conn, String schema) throws DbgException {
        InstrumentVars vars = collectVariables(ddl, conn, schema, name);
        return instrumentAuto(name, ddl, sessionId, vars);
    }

    /** Instruments a routine without performing database access. */
    public static String instrumentAuto(String name, String ddl, String sessionId,
                                        Collection<String> parameterNames) {
        return instrumentAuto(name, ddl, sessionId, collectVariables(ddl, parameterNames));
    }

    private static String instrumentAuto(String name, String ddl, String sessionId,
                                         InstrumentVars vars) {
        String[]     lines  = ddl.split("\n", -1);
        List<String> out    = new ArrayList<>();

        boolean foundBegin  = false;
        boolean entryLogged = false;
        boolean inDeclare   = false;
        int     declDepth   = 0;

        // Multi-line statement accumulator.
        // Executable lines are concatenated (space-separated) until stmtComplete()
        // fires. The checkpoint label is pinned to the FIRST line of each statement
        // so the gutter highlights the right spot when paused.
        String accum        = "";
        int    accumStart   = -1;   // 1-based line number
        int    stmtOutStart = -1;   // index in `out` where the current statement began

        Pattern beginPat   = Pattern.compile("(?i)^BEGIN\\s*$");
        Pattern beginBlock = Pattern.compile("(?i)^BEGIN\\b");
        Pattern endBlock   = Pattern.compile("(?i)^END[\\s;]");

        for (int i = 0; i < lines.length; i++) {
            String line     = lines[i];
            String stripped = line.strip();
            String sUp      = stripped.toUpperCase();

            // ── Before the routine body BEGIN ─────────────────────────────
            if (!foundBegin) {
                if (beginPat.matcher(stripped).matches()) foundBegin = true;
                out.add(line);
                continue;
            }

            // ── Inside a DECLARE block ────────────────────────────────────
            if (inDeclare) {
                out.add(line);
                if (beginBlock.matcher(sUp).find()) declDepth++;
                else if (endBlock.matcher(sUp).find() && declDepth > 0) declDepth--;
                if (stripped.endsWith(";") && declDepth == 0) inDeclare = false;
                continue;
            }

            // ── DECLARE line ──────────────────────────────────────────────
            if (sUp.startsWith("DECLARE ")) {
                inDeclare = true;
                declDepth = 0;
                out.add(line);
                if (stripped.endsWith(";")) inDeclare = false;
                continue;
            }

            // ── Non-executable (END IF, END WHILE, BEGIN, …): reset accum ─
            if (!isExecutableLine(stripped)) {
                accum        = "";
                accumStart   = -1;
                stmtOutStart = -1;
                out.add(line);
                continue;
            }

            // ── First executable line: emit entry param log ───────────────
            if (!entryLogged) {
                entryLogged = true;
                for (String var : vars.params) {
                    out.add("    CALL _dbg_log_var('" + sessionId + "','" + name +
                            "','ENTRY','" + var + "',CAST(" + var + " AS CHAR(4096)));");
                }
            }

            // ── Accumulate ────────────────────────────────────────────────
            if (accum.isEmpty()) {
                accumStart   = i + 1;          // pin label to statement's first line
                stmtOutStart = out.size();     // capture before adding this line
                accum        = stripped;
            } else {
                accum = accum + " " + stripped;
            }

            out.add(line);

            // ── Checkpoint when the accumulated statement is complete ──────
            if (stmtComplete(accum)) {
                String label  = "L" + accumStart;
                String accumUp = accum.toUpperCase().stripLeading();
                // Control-flow (IF/ELSEIF/WHILE/FOR … THEN/DO) and CALL statements both
                // need the checkpoint BEFORE the statement.  For control-flow this ensures
                // the checkpoint fires even when the condition is false and the body is
                // skipped.  For CALL statements this is required so the parent pauses
                // *before* the callee runs, giving step-into (F7) a chance to intercept it.
                boolean isControlFlow =
                    Pattern.compile("^(IF|ELSEIF|WHILE|FOR)\\b").matcher(accumUp).find() &&
                    Pattern.compile("\\b(THEN|DO)\\s*$").matcher(accumUp).find();
                boolean isCallStmt = Pattern.compile("^CALL\\b").matcher(accumUp).find();

                String cp = "    CALL _dbg_checkpoint('" + sessionId + "','" + name + "','" + label + "');";
                if (isControlFlow || isCallStmt) {
                    out.add(stmtOutStart, cp);
                } else {
                    out.add(cp);
                    for (String var : detectAssignedVars(accum, vars.allVars)) {
                        out.add("    CALL _dbg_log_var('" + sessionId + "','" + name +
                                "','" + label + "','" + var + "',CAST(" + var + " AS CHAR(4096)));");
                    }
                }
                accum        = "";
                accumStart   = -1;
                stmtOutStart = -1;
            }
        }

        String result = stripDefiner(String.join("\n", out));
        Pattern namePat = Pattern.compile(
            "(?i)(CREATE\\s+(?:PROCEDURE|FUNCTION)\\s+)`?" + Pattern.quote(name) + "`?");
        result = namePat.matcher(result).replaceFirst("$1`_dbg_" + name + "`");
        return result;
    }

    // ── Callee detection ──────────────────────────────────────────────────────

    private static final Pattern CALL_PAT = Pattern.compile(
        "(?i)\\bCALL\\s+`?(\\w+)`?\\s*\\(");

    /**
     * Returns the set of routine names called by this DDL, excluding internal _dbg_/_orig_ routines.
     * Best-effort: covers static CALL statements; does not resolve dynamic SQL.
     */
    public static Set<String> findCallees(String ddl) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = CALL_PAT.matcher(ddl);
        while (m.find()) {
            String name = m.group(1);
            if (!name.startsWith("_dbg_") && !name.startsWith("_orig_"))
                names.add(name);
        }
        return names;
    }

    // ── Proxy builder ─────────────────────────────────────────────────────────

    /**
     * Builds the thin proxy DDL that replaces the original routine and forwards to _dbg_name.
     * Mirrors build_thin_proxy_ddl() from the Python.
     */
    public static String buildProxy(String name, String type,
                                    List<String> paramNames, List<String> paramTypes,
                                    List<String> paramModes,
                                    String returnType, boolean deterministic,
                                    String sessionId) {
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < paramNames.size(); i++) {
            if (i > 0) args.append(", ");
            args.append(paramNames.get(i));
        }
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < paramNames.size(); i++) {
            if (i > 0) params.append(", ");
            String mode = (paramModes != null && i < paramModes.size()) ? paramModes.get(i) : "IN";
            params.append(mode).append(" ").append(paramNames.get(i)).append(" ").append(paramTypes.get(i));
        }

        if ("FUNCTION".equalsIgnoreCase(type)) {
            return "CREATE FUNCTION `" + name + "`(" + params + ")\n" +
                   "RETURNS " + returnType + "\n" +
                   (deterministic ? "DETERMINISTIC\n" : "NOT DETERMINISTIC\n") +
                   "SQL SECURITY INVOKER\n" +
                   "BEGIN\n" +
                   "  DECLARE v_dbg_result " + returnType + ";\n" +
                   "  UPDATE _dbg_state SET status = IF(status = 'completed', 'running', status) WHERE session_id = '" + sessionId + "';\n" +
                   "  SET v_dbg_result = `_dbg_" + name + "`(" + args + ");\n" +
                   "  UPDATE _dbg_state SET status = 'completed' WHERE session_id = '" + sessionId + "';\n" +
                   "  RETURN v_dbg_result;\n" +
                   "END";
        } else {
            return "CREATE PROCEDURE `" + name + "`(" + params + ")\n" +
                   "SQL SECURITY INVOKER\n" +
                   "BEGIN\n" +
                   "  UPDATE _dbg_state SET status = IF(status = 'completed', 'running', status) WHERE session_id = '" + sessionId + "';\n" +
                   "  CALL `_dbg_" + name + "`(" + args + ");\n" +
                   "  UPDATE _dbg_state SET status = 'completed' WHERE session_id = '" + sessionId + "';\n" +
                   "END";
        }
    }

    /**
     * Build the _orig_name DDL by renaming the CREATE header in the original DDL.
     */
    public static String buildOrigCopy(String name, String originalDdl) {
        String stripped = stripDefiner(originalDdl);
        Pattern p = Pattern.compile(
            "(?i)(CREATE\\s+(?:PROCEDURE|FUNCTION)\\s+)`?" + Pattern.quote(name) + "`?");
        return p.matcher(stripped).replaceFirst("$1`_orig_" + name + "`");
    }
}
