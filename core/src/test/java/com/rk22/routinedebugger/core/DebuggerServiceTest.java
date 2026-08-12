package com.rk22.routinedebugger.core;

import com.rk22.routinedebugger.core.database.DbgConnection;
import com.rk22.routinedebugger.core.database.RoutineMetadata;
import com.rk22.routinedebugger.core.session.DebugSession;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class DebuggerServiceTest {

    @Test
    public void deployOwnsRootAndCalleeWorkflow() throws Exception {
        FakeDb db = new FakeDb();
        db.ddl.put("root", "CREATE PROCEDURE `root`()\nBEGIN\n  CALL child();\nEND");
        db.ddl.put("child", "CREATE PROCEDURE `child`()\nBEGIN\n  SELECT 1;\nEND");
        db.types.put("root", "PROCEDURE");
        db.types.put("child", "PROCEDURE");

        DebugDeployment deployment = new DebuggerService(db, "app").deploy("root", "PROCEDURE");

        assertEquals("root", deployment.root.name);
        assertEquals(1, deployment.callees.size());
        assertEquals("child", deployment.callees.get(0).name);
        assertEquals(List.of("root", "child"), db.deployed);
        assertEquals(2, db.sessionStates.size());
    }

    @Test
    public void stopUnblocksAndRestoresWholeDeployment() throws Exception {
        FakeDb db = new FakeDb();
        db.ddl.put("root", "CREATE PROCEDURE `root`() BEGIN SELECT 1; END");
        db.ddl.put("child", "CREATE PROCEDURE `child`() BEGIN SELECT 1; END");
        db.originals.put("root", db.ddl.get("root"));
        db.originals.put("child", db.ddl.get("child"));
        DebugDeployment deployment = new DebugDeployment(
            new DeployedRoutine("root", "PROCEDURE", "root-session", db.ddl.get("root"), List.of()),
            List.of(new DeployedRoutine("child", "PROCEDURE", "child-session",
                db.ddl.get("child"), List.of())));

        String restored = new DebuggerService(db, "app").stop(deployment);

        assertEquals(db.ddl.get("root"), restored);
        assertEquals(Set.of("root-session", "child-session"), new HashSet<>(db.unblocked));
        assertEquals(Set.of("root", "child"), new HashSet<>(db.restored));
    }

    @Test
    public void loadDeploymentReconstructsDeployedCallees() throws Exception {
        FakeDb db = new FakeDb();
        db.originals.put("root", "CREATE PROCEDURE `root`() BEGIN CALL child(); END");
        db.originals.put("child", "CREATE PROCEDURE `child`() BEGIN SELECT 1; END");
        db.types.put("root", "PROCEDURE");
        db.types.put("child", "PROCEDURE");
        db.sessions.put("root", "root-session");
        db.sessions.put("child", "child-session");

        DebugDeployment deployment =
            new DebuggerService(db, "app").loadDeployment("root", "PROCEDURE");

        assertNotNull(deployment);
        assertEquals("root-session", deployment.root.sessionId);
        assertEquals(1, deployment.callees.size());
        assertEquals("child", deployment.callees.get(0).name);
        assertEquals("child-session", deployment.callees.get(0).sessionId);
    }

    @Test
    public void stepIntoDiscoversCalleeWhenDeploymentCacheIsIncomplete() throws Exception {
        FakeDb db = new FakeDb();
        db.originals.put("root", "CREATE PROCEDURE `root`() BEGIN CALL child(); END");
        db.originals.put("child", "CREATE PROCEDURE `child`() BEGIN SELECT 1; END");
        db.types.put("child", "PROCEDURE");
        db.sessions.put("child", "child-session");
        DebugSession session = new DebugSession("root-session", "root", db);
        DebugDeployment incomplete = new DebugDeployment(
            new DeployedRoutine("root", "PROCEDURE", "root-session",
                db.originals.get("root"), List.of()), List.of());

        new DebuggerService(db, "app").stepInto(session, incomplete);

        assertEquals("step", db.sessionStates.get("child-session"));
        assertEquals("step", db.updatedStates.get("root-session"));
    }

    private static final class FakeDb extends DbgConnection {
        final Map<String, String> ddl = new HashMap<>();
        final Map<String, String> types = new HashMap<>();
        final Map<String, String> originals = new HashMap<>();
        final Map<String, String> sessions = new HashMap<>();
        final List<String> deployed = new ArrayList<>();
        final List<String> restored = new ArrayList<>();
        final List<String> unblocked = new ArrayList<>();
        final Map<String, String> sessionStates = new HashMap<>();
        final Map<String, String> updatedStates = new HashMap<>();

        FakeDb() { super(null); }

        @Override public String fetchRoutineDdl(String name, String type) {
            return ddl.get(name);
        }

        @Override public RoutineMetadata fetchRoutineMetadata(String schema, String name, String type) {
            return new RoutineMetadata(new RoutineInfo(name, type), List.of(), null, false);
        }

        @Override public String findRoutineType(String schema, String name) {
            return types.get(name);
        }

        @Override public boolean isDeployed(String name) {
            return deployed.contains(name);
        }

        @Override public void deployDebug(String name, String type, String originalDdl,
                                          String origCopyDdl, String dbgDdl, String proxyDdl,
                                          String sessionId) {
            deployed.add(name);
            originals.put(name, originalDdl);
        }

        @Override public void initSessionState(String sessionId, String routineName, String status) {
            sessionStates.put(sessionId, status);
        }

        @Override public List<String> loadBreakpoints(String routineName) {
            return List.of();
        }

        @Override public void updateState(String sessionId, String status) {
            updatedStates.put(sessionId, status);
            if ("continue".equals(status)) unblocked.add(sessionId);
        }

        @Override public String loadOriginalDdl(String name) {
            return originals.get(name);
        }

        @Override public String loadOriginalType(String name) {
            return types.get(name);
        }

        @Override public String loadSessionId(String name) {
            return sessions.get(name);
        }

        @Override public void restoreOriginal(String name, String type, String originalDdl) {
            restored.add(name);
            originals.remove(name);
        }
    }
}
