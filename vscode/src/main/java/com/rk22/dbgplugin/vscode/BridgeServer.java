package com.rk22.dbgplugin.vscode;

import com.rk22.dbgplugin.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Small JSON-lines bridge used by the VS Code extension. Keeping all database
 * and instrumentation work here lets every frontend use the same Java core.
 */
public final class BridgeServer implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private Connection connection;
    private DbgConnection db;
    private String schema;
    private DeployedRoutine deployedRoot;
    private final List<DeployedRoutine> deployedCallees = new ArrayList<>();

    private record DeployedRoutine(String name, String type, String sessionId) {}

    public static void main(String[] args) throws Exception {
        try (BridgeServer server = new BridgeServer();
             BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                ObjectNode response = server.handleSafely(line);
                output.write(server.json.writeValueAsString(response));
                output.newLine();
                output.flush();
                if (response.path("result").path("shutdown").asBoolean()) break;
            }
        }
    }

    private ObjectNode handleSafely(String line) {
        long id = -1;
        try {
            if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
            JsonNode request = json.readTree(line);
            id = request.path("id").asLong(-1);
            JsonNode result = handle(request.path("method").asText(), request.path("params"));
            ObjectNode response = json.createObjectNode();
            response.put("id", id);
            response.set("result", result == null ? json.nullNode() : result);
            return response;
        } catch (Exception ex) {
            ObjectNode response = json.createObjectNode();
            response.put("id", id);
            response.put("error", rootMessage(ex));
            return response;
        }
    }

    private JsonNode handle(String method, JsonNode p) throws Exception {
        return switch (method) {
            case "connect" -> connect(p);
            case "routines" -> json.valueToTree(requireDb().fetchRoutines(schema));
            case "load" -> load(text(p, "name"), text(p, "type"));
            case "deploy" -> deploy(text(p, "name"), text(p, "type"));
            case "stop" -> stop(text(p, "name"), text(p, "type"));
            case "poll" -> poll(p);
            case "continue" -> { requireDb().updateState(text(p, "sessionId"), "continue"); yield json.nullNode(); }
            case "step" -> { requireDb().updateState(text(p, "sessionId"), "step"); yield json.nullNode(); }
            case "setSessionStates" -> setSessionStates(p);
            case "saveBreakpoints" -> saveBreakpoints(p);
            case "clearLogs" -> clearLogs(p);
            case "reset" -> reset();
            case "disconnect" -> { close(); yield json.nullNode(); }
            case "shutdown" -> { close(); ObjectNode n = json.createObjectNode(); n.put("shutdown", true); yield n; }
            default -> throw new IllegalArgumentException("Unknown method: " + method);
        };
    }

    private JsonNode connect(JsonNode p) throws Exception {
        close();
        String host = p.path("host").asText("localhost");
        int port = p.path("port").asInt(3306);
        DatabaseEngine engine = DatabaseEngine.fromId(p.path("engine").asText("mysql"));
        schema = text(p, "database");
        connection = engine.connect(host, port, schema, text(p, "user"), p.path("password").asText());
        connection.setAutoCommit(true);
        db = new DbgConnection(connection);
        db.setupInfrastructure();
        ObjectNode result = json.createObjectNode();
        result.put("schema", schema);
        result.put("engine", engine.id());
        result.set("routines", json.valueToTree(db.fetchRoutines(schema)));
        return result;
    }

    private JsonNode load(String name, String type) throws Exception {
        DbgConnection dbc = requireDb();
        String ddl = dbc.loadOriginalDdl(name);
        boolean deployed = ddl != null;
        if (!deployed) ddl = dbc.fetchRoutineDdl(name, type);
        ObjectNode result = json.createObjectNode();
        result.put("ddl", ddl);
        result.put("deployed", deployed);
        result.put("sessionId", deployed ? dbc.loadSessionId(name) : null);
        result.set("breakpoints", json.valueToTree(dbc.loadBreakpoints(name)));
        return result;
    }

    private JsonNode deploy(String name, String type) throws Exception {
        DbgConnection dbc = requireDb();
        deployedCallees.clear();
        String sessionId = UUID.randomUUID().toString();
        String original = dbc.fetchRoutineDdl(name, type);
        deployRoutine(name, type, original, sessionId, "running");
        deployedRoot = new DeployedRoutine(name, type, sessionId);

        ArrayNode callees = json.createArrayNode();
        for (String calleeName : InstrumentEngine.findCallees(original)) {
            if (dbc.isDeployed(calleeName)) continue;
            String calleeType = findRoutineType(calleeName);
            if (calleeType == null) continue;
            String calleeSessionId = UUID.randomUUID().toString();
            String calleeDdl = dbc.fetchRoutineDdl(calleeName, calleeType);
            deployRoutine(calleeName, calleeType, calleeDdl, calleeSessionId, "running");
            deployedCallees.add(new DeployedRoutine(calleeName, calleeType, calleeSessionId));

            ObjectNode item = callees.addObject();
            item.put("name", calleeName);
            item.put("type", calleeType);
            item.put("sessionId", calleeSessionId);
            item.put("ddl", calleeDdl);
            item.set("breakpoints", json.valueToTree(dbc.loadBreakpoints(calleeName)));
        }

        ObjectNode result = json.createObjectNode();
        result.put("sessionId", sessionId);
        result.set("callees", callees);
        return result;
    }

    private void deployRoutine(String name, String type, String original, String sessionId,
                               String initialStatus) throws Exception {
        DbgConnection dbc = requireDb();
        String instrumented = InstrumentEngine.instrumentAuto(name, original, sessionId, connection, schema);
        String originalCopy = InstrumentEngine.buildOrigCopy(name, original);

        List<String> names = new ArrayList<>(), types = new ArrayList<>(), modes = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT PARAMETER_NAME, DTD_IDENTIFIER, PARAMETER_MODE FROM information_schema.PARAMETERS " +
                "WHERE SPECIFIC_SCHEMA=? AND SPECIFIC_NAME=? AND ORDINAL_POSITION>0 ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema); ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                    types.add(rs.getString(2));
                    modes.add(rs.getString(3) == null ? "IN" : rs.getString(3));
                }
            }
        }
        String returnType = null;
        boolean deterministic = false;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DTD_IDENTIFIER, IS_DETERMINISTIC FROM information_schema.ROUTINES " +
                "WHERE ROUTINE_SCHEMA=? AND ROUTINE_NAME=?")) {
            ps.setString(1, schema); ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    returnType = rs.getString(1);
                    deterministic = "YES".equals(rs.getString(2));
                }
            }
        }
        String proxy = InstrumentEngine.buildProxy(name, type, names, types, modes,
                returnType == null ? "VARCHAR(255)" : returnType, deterministic, sessionId);
        dbc.deployDebug(name, type, original, originalCopy, instrumented, proxy, sessionId);
        dbc.initSessionState(sessionId, name, initialStatus);
    }

    private JsonNode stop(String name, String type) throws Exception {
        DbgConnection dbc = requireDb();
        if (deployedRoot == null) deployedRoot = new DeployedRoutine(name, type, dbc.loadSessionId(name));
        restoreDeployment();
        ObjectNode result = json.createObjectNode();
        result.put("ddl", dbc.fetchRoutineDdl(name, type));
        return result;
    }

    private void restoreDeployment() throws DbgException {
        if (deployedRoot == null) return;
        DbgConnection dbc = requireDb();

        if (deployedRoot.sessionId() != null) dbc.updateState(deployedRoot.sessionId(), "continue");
        for (DeployedRoutine callee : deployedCallees) {
            if (callee.sessionId() != null) dbc.updateState(callee.sessionId(), "continue");
        }
        for (DeployedRoutine callee : deployedCallees) {
            String calleeOriginal = dbc.loadOriginalDdl(callee.name());
            if (calleeOriginal != null) dbc.restoreOriginal(callee.name(), callee.type(), calleeOriginal);
        }
        String original = dbc.loadOriginalDdl(deployedRoot.name());
        if (original == null) throw new DbgException("No saved original found for " + deployedRoot.name());
        dbc.restoreOriginal(deployedRoot.name(), deployedRoot.type(), original);

        deployedRoot = null;
        deployedCallees.clear();
    }

    private JsonNode poll(JsonNode p) throws Exception {
        ArrayNode results = json.createArrayNode();
        for (JsonNode session : p.path("sessions")) {
            String sessionId = text(session, "sessionId");
            PollResult polled = requireDb().pollLog(sessionId, session.path("sinceId").asLong());
            ObjectNode item = json.valueToTree(polled);
            item.put("sessionId", sessionId);
            item.put("name", session.path("name").asText());
            results.add(item);
        }
        ObjectNode result = json.createObjectNode();
        result.set("sessions", results);
        return result;
    }

    private JsonNode setSessionStates(JsonNode p) throws Exception {
        String status = text(p, "status");
        for (JsonNode session : p.path("sessions")) {
            requireDb().initSessionState(text(session, "sessionId"), session.path("name").asText(), status);
        }
        return json.nullNode();
    }

    private JsonNode clearLogs(JsonNode p) throws Exception {
        for (JsonNode session : p.path("sessions")) {
            requireDb().clearLog(text(session, "sessionId"));
        }
        return json.nullNode();
    }

    private String findRoutineType(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ROUTINE_TYPE FROM information_schema.ROUTINES " +
                "WHERE ROUTINE_SCHEMA=? AND ROUTINE_NAME=?")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private JsonNode saveBreakpoints(JsonNode p) throws Exception {
        List<String> labels = new ArrayList<>();
        p.path("labels").forEach(node -> labels.add(node.asText()));
        requireDb().saveBreakpoints(text(p, "name"), labels);
        return json.nullNode();
    }

    private JsonNode reset() throws Exception {
        DbgConnection dbc = requireDb();
        dbc.restoreAll(schema);
        dbc.setupInfrastructure();
        deployedRoot = null;
        deployedCallees.clear();
        ObjectNode result = json.createObjectNode();
        result.set("routines", json.valueToTree(dbc.fetchRoutines(schema)));
        return result;
    }

    private DbgConnection requireDb() {
        if (db == null) throw new IllegalStateException("Not connected to a database");
        return db;
    }

    private static String text(JsonNode node, String name) {
        String value = node.path(name).asText();
        if (value.isBlank()) throw new IllegalArgumentException("Missing parameter: " + name);
        return value;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    @Override public void close() throws SQLException {
        if (db != null && deployedRoot != null) {
            try { restoreDeployment(); }
            catch (Exception ignored) {}
        }
        db = null;
        schema = null;
        deployedRoot = null;
        deployedCallees.clear();
        if (connection != null) {
            try { connection.close(); } finally { connection = null; }
        }
    }
}
