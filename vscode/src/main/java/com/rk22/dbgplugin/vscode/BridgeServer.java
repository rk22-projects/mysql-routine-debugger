package com.rk22.dbgplugin.vscode;

import com.rk22.dbgplugin.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Thin JSON-lines adapter between the VS Code extension and DebuggerService.
 */
public final class BridgeServer implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private Connection connection;
    private DebuggerService debugger;
    private String schema;
    private DebugDeployment deployment;

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
            case "routines" -> json.valueToTree(requireDebugger().listRoutines());
            case "load" -> load(text(p, "name"), text(p, "type"));
            case "deploy" -> deploy(text(p, "name"), text(p, "type"));
            case "stop" -> stop(text(p, "name"), text(p, "type"));
            case "poll" -> poll(p);
            case "continue" -> { requireDebugger().updateSessionState(text(p, "sessionId"), "continue"); yield json.nullNode(); }
            case "step" -> { requireDebugger().updateSessionState(text(p, "sessionId"), "step"); yield json.nullNode(); }
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
        debugger = new DebuggerService(connection, schema);
        ObjectNode result = json.createObjectNode();
        result.put("schema", schema);
        result.put("engine", engine.id());
        result.set("routines", json.valueToTree(debugger.initialize()));
        return result;
    }

    private JsonNode load(String name, String type) throws Exception {
        return json.valueToTree(requireDebugger().loadRoutine(name, type));
    }

    private JsonNode deploy(String name, String type) throws Exception {
        deployment = requireDebugger().deploy(name, type);
        ArrayNode callees = json.createArrayNode();
        for (DeployedRoutine callee : deployment.callees) {
            ObjectNode item = callees.addObject();
            item.put("name", callee.name);
            item.put("type", callee.type);
            item.put("sessionId", callee.sessionId);
            item.put("ddl", callee.ddl);
            item.set("breakpoints", json.valueToTree(callee.breakpoints));
        }

        ObjectNode result = json.createObjectNode();
        result.put("sessionId", deployment.root.sessionId);
        result.set("callees", callees);
        return result;
    }

    private JsonNode stop(String name, String type) throws Exception {
        if (deployment == null) {
            RoutineDetails loaded = requireDebugger().loadRoutine(name, type);
            if (!loaded.deployed || loaded.sessionId == null)
                throw new DbgException("No active debug deployment for " + name);
            deployment = new DebugDeployment(
                new DeployedRoutine(name, type, loaded.sessionId, loaded.ddl, loaded.breakpoints), List.of());
        }
        String ddl = requireDebugger().stop(deployment);
        deployment = null;
        ObjectNode result = json.createObjectNode();
        result.put("ddl", ddl);
        return result;
    }

    private JsonNode poll(JsonNode p) throws Exception {
        ArrayNode results = json.createArrayNode();
        for (JsonNode session : p.path("sessions")) {
            String sessionId = text(session, "sessionId");
            PollResult polled = requireDebugger().poll(sessionId, session.path("sinceId").asLong());
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
            requireDebugger().initializeSessionState(
                text(session, "sessionId"), session.path("name").asText(), status);
        }
        return json.nullNode();
    }

    private JsonNode clearLogs(JsonNode p) throws Exception {
        for (JsonNode session : p.path("sessions")) {
            requireDebugger().clearLog(text(session, "sessionId"));
        }
        return json.nullNode();
    }

    private JsonNode saveBreakpoints(JsonNode p) throws Exception {
        List<String> labels = new ArrayList<>();
        p.path("labels").forEach(node -> labels.add(node.asText()));
        requireDebugger().saveBreakpoints(text(p, "name"), labels);
        return json.nullNode();
    }

    private JsonNode reset() throws Exception {
        List<RoutineInfo> routines = requireDebugger().reset();
        deployment = null;
        ObjectNode result = json.createObjectNode();
        result.set("routines", json.valueToTree(routines));
        return result;
    }

    private DebuggerService requireDebugger() {
        if (debugger == null) throw new IllegalStateException("Not connected to a database");
        return debugger;
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
        if (debugger != null && deployment != null) {
            try { debugger.stop(deployment); }
            catch (Exception ignored) {}
        }
        debugger = null;
        schema = null;
        deployment = null;
        if (connection != null) {
            try { connection.close(); } finally { connection = null; }
        }
    }
}
