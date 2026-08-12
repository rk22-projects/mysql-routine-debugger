package com.rk22.routinedebugger.core;

import com.rk22.routinedebugger.core.database.DbgConnection;
import com.rk22.routinedebugger.core.database.RoutineMetadata;
import com.rk22.routinedebugger.core.instrumentation.InstrumentEngine;
import com.rk22.routinedebugger.core.session.DebugSession;

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
        return initializeWithRecovery().routines();
    }

    /** Detects and restores artifacts left by an interrupted frontend session. */
    public StartupResult initializeWithRecovery() throws DbgException {
        // setupInfrastructure ensures the recovery tables exist while preserving
        // _dbg_originals, which is the durable source for original routine DDL.
        db.setupInfrastructure();
        List<RoutineInfo> recovered = db.findLeftoverRoutines(schema);
        if (!recovered.isEmpty()) {
            db.restoreAll(schema);
            db.setupInfrastructure();
        }
        return new StartupResult(db.fetchRoutines(schema), recovered);
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

    /**
     * Reconstructs an existing debug deployment from the originals store.
     * This is used when a frontend reconnects to, or reloads, a routine that
     * was already deployed in an earlier UI session.
     */
    public DebugDeployment loadDeployment(String name, String type) throws DbgException {
        DeployedRoutine root = loadDeployedRoutine(name, type);
        if (root == null) return null;

        List<DeployedRoutine> callees = new ArrayList<>();
        for (String calleeName : InstrumentEngine.findCallees(root.ddl)) {
            String calleeType = db.loadOriginalType(calleeName);
            if (calleeType == null) continue;
            DeployedRoutine callee = loadDeployedRoutine(calleeName, calleeType);
            if (callee != null) callees.add(callee);
        }
        return new DebugDeployment(root, callees);
    }

    /** Loads one deployed routine without requiring a frontend-owned deployment cache. */
    public DeployedRoutine loadDeployedRoutine(String name) throws DbgException {
        String type = db.loadOriginalType(name);
        return type == null ? null : loadDeployedRoutine(name, type);
    }

    private DeployedRoutine loadDeployedRoutine(String name, String type) throws DbgException {
        String ddl = db.loadOriginalDdl(name);
        if (ddl == null) return null;
        String sessionId = db.loadSessionId(name);
        if (sessionId == null)
            throw new DbgException("Deployed routine has no saved session: " + name);
        return new DeployedRoutine(name, type, sessionId, ddl, db.loadBreakpoints(name));
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
        DbgException failure = null;
        List<DeployedRoutine> routines = new ArrayList<>();
        routines.add(deployment.root);
        routines.addAll(deployment.callees);
        for (DeployedRoutine routine : routines) {
            try {
                restore(routine);
            } catch (DbgException ex) {
                if (failure == null) failure = new DbgException("Failed to restore one or more routines.");
                failure.addSuppressed(ex);
            }
        }
        if (failure != null) throw failure;
        return db.fetchRoutineDdl(deployment.root.name, deployment.root.type);
    }

    private void restore(DeployedRoutine routine) throws DbgException {
        db.updateState(routine.sessionId, "continue");
        String ddl = db.loadOriginalDdl(routine.name);
        if (ddl == null) ddl = routine.ddl;
        if (ddl == null) throw new DbgException("No saved original found for " + routine.name + ".");
        db.restoreOriginal(routine.name, routine.type, ddl);
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
        return openSession(routineName, sessionId, null, listener, eventExecutor);
    }

    public DebugSession openSession(String routineName, String sessionId,
                                    DebugDeployment deployment,
                                    DebugEventListener listener, Executor eventExecutor) {
        DebugSession session = new DebugSession(sessionId, routineName, db);
        if (deployment != null) {
            for (DeployedRoutine callee : deployment.callees)
                session.registerChildSession(callee.name, callee.sessionId);
        }
        session.start(listener, eventExecutor);
        return session;
    }

    public void continueExecution(DebugSession session, DebugDeployment deployment) throws DbgException {
        setCalleeStatus(resolveCallees(session, deployment), "running");
        session.doContinue();
    }

    public void stepOver(DebugSession session, DebugDeployment deployment) throws DbgException {
        setCalleeStatus(resolveCallees(session, deployment), "running");
        session.doStep();
    }

    public void stepInto(DebugSession session, DebugDeployment deployment) throws DbgException {
        List<DeployedRoutine> callees = resolveCallees(session, deployment);
        setCalleeStatus(callees, "step");
        for (DeployedRoutine callee : callees)
            session.registerChildSession(callee.name, callee.sessionId);
        session.doStep();
    }

    public void step(DebugSession session) { session.doStep(); }

    public void stepOut(DebugSession session) { session.doContinue(); }

    private List<DeployedRoutine> resolveCallees(DebugSession session,
                                                  DebugDeployment deployment) throws DbgException {
        Map<String, DeployedRoutine> resolved = new LinkedHashMap<>();
        if (deployment != null) {
            for (DeployedRoutine callee : deployment.callees) resolved.put(callee.name, callee);
        }

        String rootDdl = deployment == null ? null : deployment.root.ddl;
        if (rootDdl == null) rootDdl = db.loadOriginalDdl(session.routineName);
        if (rootDdl != null) {
            for (String calleeName : InstrumentEngine.findCallees(rootDdl)) {
                if (resolved.containsKey(calleeName)) continue;
                DeployedRoutine callee = loadDeployedRoutine(calleeName);
                if (callee != null) resolved.put(calleeName, callee);
            }
        }
        return List.copyOf(resolved.values());
    }

    private void setCalleeStatus(Collection<DeployedRoutine> callees, String status)
            throws DbgException {
        for (DeployedRoutine callee : callees)
            db.initSessionState(callee.sessionId, callee.name, status);
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
