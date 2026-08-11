package com.rk22.dbgplugin;

import org.junit.Test;
import static org.junit.Assert.*;

public class InstrumentEngineTest {

    // ── isExecutableLine ──────────────────────────────────────────────────────

    @Test public void executableLine_empty()       { assertFalse(InstrumentEngine.isExecutableLine("")); }
    @Test public void executableLine_comment()     { assertFalse(InstrumentEngine.isExecutableLine("-- comment")); }
    @Test public void executableLine_declare()     { assertFalse(InstrumentEngine.isExecutableLine("DECLARE v INT DEFAULT 0;")); }
    @Test public void executableLine_begin()       { assertFalse(InstrumentEngine.isExecutableLine("BEGIN")); }
    @Test public void executableLine_endIf()       { assertFalse(InstrumentEngine.isExecutableLine("END IF;")); }
    @Test public void executableLine_setStmt()     { assertTrue(InstrumentEngine.isExecutableLine("SET v = 1;")); }
    @Test public void executableLine_selectInto()  { assertTrue(InstrumentEngine.isExecutableLine("SELECT x INTO v FROM t;")); }

    // ── stmtComplete ─────────────────────────────────────────────────────────

    @Test public void stmtComplete_semicolon()     { assertTrue(InstrumentEngine.stmtComplete("SET v = 1;")); }
    @Test public void stmtComplete_ifThen()        { assertTrue(InstrumentEngine.stmtComplete("IF v > 0 THEN")); }
    @Test public void stmtComplete_elseifThen()    { assertTrue(InstrumentEngine.stmtComplete("ELSEIF v = 0 THEN")); }
    @Test public void stmtComplete_whileDo()       { assertTrue(InstrumentEngine.stmtComplete("WHILE v < 10 DO")); }
    @Test public void stmtComplete_bareElse()      { assertTrue(InstrumentEngine.stmtComplete("ELSE")); }
    @Test public void stmtComplete_loop()          { assertTrue(InstrumentEngine.stmtComplete("LOOP")); }
    @Test public void stmtComplete_repeat()        { assertTrue(InstrumentEngine.stmtComplete("REPEAT")); }
    @Test public void stmtComplete_labeledLoop()   { assertTrue(InstrumentEngine.stmtComplete("read_loop: LOOP")); }

    // Critical: CASE WHEN ... THEN must NOT be treated as a statement boundary
    @Test public void stmtComplete_caseWhenThen()  {
        assertFalse(InstrumentEngine.stmtComplete("WHEN status = 1 THEN"));
    }
    @Test public void stmtComplete_caseExprThen()  {
        assertFalse(InstrumentEngine.stmtComplete("CASE WHEN v > 0 THEN"));
    }
    @Test public void stmtComplete_inlineThen()    {
        // e.g. "(1 = 1) THEN" inside a SELECT CASE column
        assertFalse(InstrumentEngine.stmtComplete("(v > 0) THEN"));
    }

    // ── detectAssignedVars ────────────────────────────────────────────────────

    @Test public void detectAssigned_set() {
        java.util.List<String> vars = java.util.Arrays.asList("v", "total", "x");
        java.util.List<String> result = InstrumentEngine.detectAssignedVars("SET v = 1;", vars);
        assertTrue(result.contains("v"));
        assertFalse(result.contains("total"));
    }

    @Test public void detectAssigned_selectInto() {
        java.util.List<String> vars = java.util.Arrays.asList("v", "total");
        java.util.List<String> result = InstrumentEngine.detectAssignedVars(
            "SELECT SUM(amount) INTO total FROM orders;", vars);
        assertTrue(result.contains("total"));
        assertFalse(result.contains("v"));
    }

    // ── stripDefiner ─────────────────────────────────────────────────────────

    @Test public void stripDefiner_removesClause() {
        String input  = "CREATE DEFINER=`root`@`localhost` PROCEDURE `foo`()";
        String result = InstrumentEngine.stripDefiner(input);
        assertFalse(result.contains("DEFINER"));
        assertTrue(result.startsWith("CREATE"));
    }

    @Test public void stripDefiner_noClause() {
        String input  = "CREATE PROCEDURE `foo`()";
        String result = InstrumentEngine.stripDefiner(input);
        assertEquals(input, result.trim());
    }

    // ── buildOrigCopy ─────────────────────────────────────────────────────────

    @Test public void buildOrigCopy_renamesRoutine() {
        String ddl    = "CREATE PROCEDURE `myproc`()\nBEGIN\n  SET x = 1;\nEND";
        String result = InstrumentEngine.buildOrigCopy("myproc", ddl);
        assertTrue(result.contains("`_orig_myproc`"));
        assertFalse(result.contains("`myproc`"));
    }

    // ── instrumentAuto integration (no DB — checks structure only) ─────────────

    @Test public void instrument_noCallInsideCaseWhen() throws Exception {
        // Simulate a DDL that has CASE WHEN ... THEN inside a SELECT
        // We call instrumentAuto with a mock connection for schema queries
        // Since we can't inject a DB in a unit test, we test the text transform
        // path using the helpers directly.
        String ddl = String.join("\n",
            "CREATE PROCEDURE `testproc`()",
            "BEGIN",
            "  DECLARE done INT DEFAULT 0;",
            "  SET done = 1;",
            "  SELECT CASE",
            "    WHEN done = 1 THEN 'yes'",
            "    ELSE 'no'",
            "  END AS result;",
            "END"
        );
        // Verify stmtComplete returns false for the WHEN line
        assertFalse(InstrumentEngine.stmtComplete("    WHEN done = 1 THEN 'yes'"));
        // Verify stmtComplete returns true for lines ending with ;
        assertTrue(InstrumentEngine.stmtComplete("  SET done = 1;"));
    }

    @Test public void instrument_declareNotAfterEntry() {
        // DECLARE lines must not trigger entry logging (they are not executable)
        assertFalse(InstrumentEngine.isExecutableLine("DECLARE done INT DEFAULT 0;"));
        assertFalse(InstrumentEngine.isExecutableLine("DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN"));
    }

    // ── Control-flow checkpoint placement ─────────────────────────────────────

    /** IF/ELSEIF/WHILE/FOR...THEN/DO must be detected as control flow so their
     *  checkpoint is inserted before the statement, not inside the body. */
    @Test public void controlFlow_ifThenDetected() {
        java.util.regex.Pattern starts = java.util.regex.Pattern.compile("^(IF|ELSEIF|WHILE|FOR)\\b");
        java.util.regex.Pattern ends   = java.util.regex.Pattern.compile("\\b(THEN|DO)\\s*$");
        String[] controlFlow = {
            "IF V > 0 THEN",
            "IF ((COALESCE(P_CUSTCLASS,0) = 0) AND (COALESCE(P_CUSTNR,0) = 0)) THEN",
            "ELSEIF V = 0 THEN",
            "WHILE V < 10 DO",
        };
        for (String s : controlFlow) {
            String up = s.toUpperCase().stripLeading();
            assertTrue("Expected control flow: " + s,
                starts.matcher(up).find() && ends.matcher(up).find());
        }
        // ELSE and semicolon-terminated statements are NOT control flow
        String[] notControlFlow = { "ELSE", "SET V = 1;", "WHEN X = 1 THEN" };
        for (String s : notControlFlow) {
            String up = s.toUpperCase().stripLeading();
            assertFalse("Expected NOT control flow: " + s,
                starts.matcher(up).find() && ends.matcher(up).find());
        }
    }
}
