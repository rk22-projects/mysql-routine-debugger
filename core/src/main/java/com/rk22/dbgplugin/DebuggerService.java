package com.rk22.dbgplugin;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Frontend-facing debugger backend.  It owns every database operation and
 * multi-routine workflow after a frontend supplies a control connection.
 */
public class DebuggerService {
    private final DbgConnection db;
    private final String schema;

    public DebuggerService(Connection connection, String schema) {
        this(new DbgConnection(Objects.requireNonNull(connection, "connection")), schema);
    }

    DebuggerService(DbgConnection db, String schema) {
        this.db = Objects.requireNonNull(db, "db");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    public List<RoutineInfo> initialize() throws DbgException {
        db.setupInfrastructure();
        return db.fetchRoutines(schema);
    }

    public List<RoutineInfo> listRoutines() throws DbgException {
        return db.fetchRoutines(schema);
    }

    public RoutineDetails loadRoutine(String name, String type) throws DbgException {
        String ddl = db.loadOriginalDdl(name);
        boolean deployed = ddl != null;
        if (!deployed) ddl = db.fetchRoutineDdl(name, type);
        String sessionId = deployed ? db.loadSessionId(name) : null;
        if (deployed && sessionId == null)
            throw new DbgException("Deployed routine has no saved session: " + name);
        RoutineMetadata metadata = db.fetchRoutineMetadata(schema, name, type);
        return new RoutineDetails(metadata.routine, ddl, metadata.parameters,
            metadata.returnType, metadata.deterministic, deployed,
            sessionId, db.loadBreakpoints(name));
    }

    public DebugDeployment deploy(String name, String type) throws DbgException {
        String rootDdl = db.fetchRoutineDdl(name, type);
        DeployedRoutine root = deployRoutine(name, type, rootDdl, "running");
        List<DeployedRoutine> callees = new ArrayList<>();
        try {
            for (String calleeName : InstrumentEngine.findCallees(rootDdl)) {
                if (db.isDeployed(calleeName)) continue;
                String calleeType = db.findRoutineType(schema, calleeName);
                if (calleeType == null) continue;
                String calleeDdl = db.fetchRoutineDdl(calleeName, calleeType);
                callees.add(deployRoutine(calleeName, calleeType, calleeDdl, "running"));
            }
            return new DebugDeployment(root, callees);
        } catch (DbgException failure) {
            restoreQuietly(root);
            for (DeployedRoutine callee : callees) restoreQuietly(callee);
            throw failure;
        }
    }

    private DeployedRoutine deployRoutine(String name, String type, String originalDdl,
                                           String initialStatus) throws DbgException {
        String sessionId = UUID.randomUUID().toString();
        RoutineMetadata metadata = db.fetchRoutineMetadata(schema, name, type);
        List<String> parameterNames = metadata.parameters.stream().map(p -> p.name).toList();
        List<String> parameterTypes = metadata.parameters.stream().map(p -> p.type).toList();
        List<String> parameterModes = metadata.parameters.stream().map(p -> p.mode).toList();
        String instrumented = InstrumentEngine.instrumentAuto(
            name, originalDdl, sessionId, parameterNames);
        String originalCopy = InstrumentEngine.buildOrigCopy(name, originalDdl);
        String returnType = metadata.returnType == null ? "VARCHAR(255)" : metadata.returnType;
        String proxy = InstrumentEngine.buildProxy(name, type, parameterNames,
            parameterTypes, parameterModes, returnType, metadata.deterministic, sessionId);
        db.deployDebug(name, type, originalDdl, originalCopy, instrumented, proxy, sessionId);
        db.initSessionState(sessionId, name, initialStatus);
        return new DeployedRoutine(name, type, sessionId, originalDdl, db.loadBreakpoints(name));
    }

    public String stop(DebugDeployment deployment) throws DbgException {
        if (deployment == null) throw new DbgException("No active debug deployment.");
        unblock(deployment);
        String originalDdl = db.loadOriginalDdl(deployment.root.name);
        if (originalDdl == null) throw new DbgException("No saved original found.");
        db.restoreOriginal(deployment.root.name, deployment.root.type, originalDdl);
        for (DeployedRoutine callee : deployment.callees) restoreQuietly(callee);
        return db.fetchRoutineDdl(deployment.root.name, deployment.root.type);
    }

    private void restoreQuietly(DeployedRoutine routine) {
        try {
            db.updateState(routine.sessionId, "continue");
            String ddl = db.loadOriginalDdl(routine.name);
            if (ddl != null) db.restoreOriginal(routine.name, routine.type, ddl);
        } catch (DbgException ignored) {}
    }

    public List<RoutineInfo> reset() throws DbgException {
        db.restoreAll(schema);
        db.setupInfrastructure();
        return db.fetchRoutines(schema);
    }

    public void saveBreakpoints(String routineName, Collection<String> labels) throws DbgException {
        db.saveBreakpoints(routineName, labels);
    }

    public PollResult poll(String sessionId, long sinceId) throws DbgException {
        return db.pollLog(sessionId, sinceId);
    }

    public void updateSessionState(String sessionId, String status) throws DbgException {
        db.updateState(sessionId, status);
    }

    public void initializeSessionState(String sessionId, String routineName, String status)
            throws DbgException {
        db.initSessionState(sessionId, routineName, status);
    }

    public void clearLog(String sessionId) throws DbgException {
        db.clearLog(sessionId);
    }

    public DebugSession openSession(String routineName, String sessionId,
                                    DebugEventListener listener, Executor eventExecutor) {
        DebugSession session = new DebugSession(sessionId, routineName, db);
        session.start(listener, eventExecutor);
        return session;
    }

    public void continueExecution(DebugSession session, DebugDeployment deployment) {
        setCalleeStatus(deployment, "running");
        session.doContinue();
    }

    public void stepOver(DebugSession session, DebugDeployment deployment) {
        setCalleeStatus(deployment, "running");
        session.doStep();
    }

    public void stepInto(DebugSession session, DebugDeployment deployment) {
        setCalleeStatus(deployment, "step");
        if (deployment != null) {
            for (DeployedRoutine callee : deployment.callees)
                session.registerChildSession(callee.name, callee.sessionId);
        }
        session.doStep();
    }

    public void step(DebugSession session) { session.doStep(); }

    public void stepOut(DebugSession session) { session.doContinue(); }

    private void setCalleeStatus(DebugDeployment deployment, String status) {
        if (deployment == null) return;
        for (DeployedRoutine callee : deployment.callees) {
            try { db.initSessionState(callee.sessionId, callee.name, status); }
            catch (DbgException ignored) {}
        }
    }

    public void unblock(DebugDeployment deployment) {
        if (deployment == null) return;
        try { db.updateState(deployment.root.sessionId, "continue"); }
        catch (DbgException ignored) {}
        for (DeployedRoutine callee : deployment.callees) {
            try { db.updateState(callee.sessionId, "continue"); }
            catch (DbgException ignored) {}
        }
    }
}
