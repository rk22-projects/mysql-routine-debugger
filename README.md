# MySQL Routine Debugger

Debug MySQL and MariaDB stored procedures and functions with breakpoints, stepping, watches, variable inspection, and execution logs.

[![MySQL Routine Debugger paused in Visual Studio Code](docs/images/vscode_mysql_debugger.png)](docs/images/vscode_mysql_debugger.png)

*A routine paused in the Visual Studio Code frontend. Click the image to view it at full size.*

> [!CAUTION]
> **Early alpha software:** this project has undergone only limited testing. Features may be incomplete, behavior may change without notice, and defects may modify routines or other database objects incorrectly. Do not use it with production databases. Use only isolated development or test environments and ensure you have current backups.

Issue reports and help with collaborative testing are very welcome. If you try the debugger, please [open an issue](https://github.com/rk22-projects/mysql-routine-debugger/issues) to share problems, suggestions, or successful test results. Every constructive report helps make the project safer and more useful.

The debugger is available through three frontends, each with its own installation guide:

- [Visual Studio Code extension](vscode/README.md)
- [Apache NetBeans plugin](plugin/README.md)
- [Standalone JavaFX application](standalone/README.md)

All three frontends provide the same core debugging workflow, so you can use the interface that best fits your development environment.

> [!WARNING]
> Use the debugger only on development or test databases. The connected account must be authorized to inspect, create, replace, and restore routines and to create supporting objects in the selected schema.

## Compatibility and requirements

MySQL is the default connection type. MariaDB is available as a separate option, with the matching JDBC driver selected automatically. Compatibility work targets modern MySQL and MariaDB releases, but not every server version, SQL mode, authentication configuration, or routine syntax has been verified yet.

You will need:

- Java 17 or newer for the standalone application
- Visual Studio Code 1.96 or newer for the VS Code extension
- Apache NetBeans for the NetBeans plugin
- A reachable MySQL or MariaDB server
- A database account with the required routine and schema privileges

## Quick start

1. Open your preferred frontend and connect to a development or test database.
2. Select a stored procedure or function.
3. Add breakpoints and start the debug session.
4. Invoke the routine from a separate SQL client or application connection.
5. Inspect variables and watches when execution pauses.
6. Continue, step into, step over, step out, or stop the session.

Use **Reset All Debug Changes** if an earlier session did not close normally.

## Project layout

```text
core/        Shared database and debugger logic
plugin/      Apache NetBeans frontend
standalone/  JavaFX frontend
vscode/      Visual Studio Code frontend
docs/        Documentation images and supporting files
```

The former build and installation scripts are preserved on the `build-scripts` branch while the cross-database packaging workflow is redesigned.
