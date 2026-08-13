# Using the Standalone MySQL Routine Debugger

This guide covers the complete standalone application workflow. See the [standalone README](../../standalone/README.md) for requirements and installation.

## Connect to a database

The connection dialog opens when the application starts. Enter the host, port, user, password, and database/schema. Use the ellipsis button beside the database field to discover schemas with the entered credentials, then select **Connect**. The last successful connection profile is restored the next time the application starts.

> [!NOTE]
> **Screenshot needed:** the standalone connection dialog with representative, non-sensitive values and the database discovery control visible. Save it as `../images/standalone_connection.png`, then replace this callout with `![Standalone connection dialog](../images/standalone_connection.png)`.

## Select a routine and add breakpoints

Type in the routine field to filter procedures and functions, then select one to load its original definition.

Click the gutter beside an executable source line to set or remove a breakpoint. You can also select a source line and press `F9`. Blank lines, comments, and other non-executable lines cannot receive breakpoints. Breakpoints are retained for that routine.

> [!NOTE]
> **Screenshot needed:** the full standalone window with a loaded routine, one breakpoint, Watch panel, and expanded Variable log. Save it as `../images/standalone_debugger.png`, then replace this callout with `![Standalone MySQL Routine Debugger](../images/standalone_debugger.png)`.

## Start and control a session

1. Select **Debug**. The green banner confirms that the routine and its directly called routines are ready.
2. Invoke the routine normally from a separate SQL client or application connection. For example:

   ```sql
   CALL my_schema.my_procedure(42);
   SELECT my_schema.my_function(42);
   ```

3. When execution pauses, use the toolbar or keyboard:

   | Action | Shortcut | Behavior |
   |---|---:|---|
   | Continue | `F5` | Run until the next breakpoint or completion. |
   | Step Into | `F7` | Enter a called routine when available. |
   | Step Over | `F8` | Advance without opening a called routine. |
   | Step Out | `Ctrl+F7` | Finish the called routine and return to its caller. |

Step Into opens a child window for the called routine. The child window provides Continue, Step Over, and Step Out; it closes when that routine completes and focus returns to the caller.

### Important behavior during debugging

- Starting a session temporarily replaces the selected routine and supported direct callees with debugger-managed versions. Other database users can encounter those versions until you select **Stop**.
- Invoke only one instance of the debugged call chain at a time. Concurrent invocations share the deployed debug state and are not supported.
- Do not edit, rename, or drop a deployed routine or any `_dbg_*` or `_orig_*` object while debugging.
- Pausing requires debugger checkpoints to commit database state. Do not use a debug run to validate transaction or rollback behavior.
- The SQL client that invoked the routine remains blocked while execution is paused. Continue, step, stop, or reset the session before closing that client.

## Inspect variables and logs

- Enter a parameter or local variable in the **Watch** field and select `+`.
- Right-click an identifier in the source and select **Add to Watch**.
- Select **All** to watch variables automatically after they first appear in the log.
- Changed values are highlighted after a pause. “Not yet seen” means no value has been recorded for that variable.
- Hover over an identifier whose value is known to see its current value.
- Expand **Variable log** to inspect recorded labels, variables, values, and breakpoint events. **Clear** removes log entries for the active session without removing breakpoints.

## Stop and recover

Select **Stop** before choosing another routine, changing connections, or closing the application. This unblocks paused calls and restores the original definitions of the selected routine and its prepared callees.

If the application or connection ends unexpectedly, reconnect to the same schema. The debugger automatically restores saved original definitions, removes orphaned generated routines, and reports the recovered routines.

Use **Reset all debug changes…** from the overflow menu only when normal Stop or automatic recovery cannot clean up a session. Reset affects all debugger deployments in the connected schema, restores every saved original routine, removes orphaned generated routines, and reinitializes the debugger infrastructure.

See the [documentation screenshot checklist](../screenshots.md) for the images still needed by this guide.
