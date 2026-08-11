# MySQL Routine Debugger

MySQL Routine Debugger provides interactive debugging for stored procedures and functions through three frontends:

- Apache NetBeans plugin
- Standalone JavaFX application
- Visual Studio Code extension

The frontends share the same debugging features, including breakpoints, stepping, watches, variable inspection, and execution logs.

> [!WARNING]
> Use the debugger only on development or test databases. The connected account must be authorized to inspect, create, replace, and restore routines and to create supporting objects in the selected schema.

## Database compatibility

MySQL is the default connection type. MariaDB remains available as a separate connection option, and the matching JDBC driver is selected automatically.

Compatibility work currently targets modern MySQL and MariaDB releases. Full behavior across every server version, SQL mode, authentication configuration, and routine syntax has not yet been verified. Treat this branch as pre-release software and validate it against a disposable database before wider use.

## Requirements

- Java 17 or newer for the standalone application
- Apache NetBeans for the NetBeans frontend
- Visual Studio Code 1.96 or newer for the VS Code frontend
- A reachable MySQL or MariaDB server
- A database account with the required routine and schema privileges

## Using the debugger

1. Choose MySQL or MariaDB and enter the connection details.
2. Select a stored procedure or function.
3. Add breakpoints and start debugging.
4. Invoke the routine from another SQL client or application.
5. Inspect variables and watches when execution pauses.
6. Continue, step, or stop the session when finished.

Use **Reset All Debug Changes** if an earlier session did not close normally.

## Project layout

```text
core/        Shared database and debugger logic
plugin/      Apache NetBeans frontend
standalone/  JavaFX frontend
vscode/      Visual Studio Code frontend
```

The former build and installation scripts are preserved on the `build-scripts` branch while the cross-database packaging workflow is redesigned.
