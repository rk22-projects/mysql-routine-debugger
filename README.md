# MySQL Routine Debugger

Debug MySQL and MariaDB stored procedures and functions with breakpoints, stepping, watches, variable inspection, and execution logs.

[![MySQL Routine Debugger paused in Visual Studio Code](docs/images/vscode_mysql_debugger.png)](docs/images/vscode_mysql_debugger.png)

*A routine paused in the Visual Studio Code frontend. Click the image to view it at full size.*

> [!CAUTION]
> **Early alpha software:** this project has undergone only limited testing. Features may be incomplete, behavior may change without notice, and defects may modify routines or other database objects incorrectly. Do not use it with production databases. Use only isolated development or test environments and ensure you have current backups.

Issue reports and help with collaborative testing are very welcome. If you try the debugger, please [open an issue](https://github.com/rk22-projects/mysql-routine-debugger/issues) to share problems, suggestions, or successful test results. Every constructive report helps make the project safer and more useful.

> [!WARNING]
> Use the debugger only on development or test databases. The connected account must be authorized to inspect, create, replace, and restore routines and to create supporting objects in the selected schema.

## Install in Visual Studio Code

Visual Studio Code is the recommended and easiest way to use the debugger:

1. Install [Visual Studio Code](https://code.visualstudio.com/) 1.96 or newer and a Java 17 or newer runtime.
2. Open the **Extensions** view in Visual Studio Code (`Ctrl+Shift+X` on Windows and Linux, or `Cmd+Shift+X` on macOS).
3. Search for **MySQL Routine Debugger** by **RK22**, or open it directly in the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=RK22.mysql-routine-debugger).
4. Select **Install**.
5. Run **MySQL Routine Debugger: Open Debugger** from the Command Palette.

The extension normally finds Java automatically. If it does not, set **MySQL Routine Debugger: Java Path** (`mysqlRoutineDebugger.javaPath`) to a Java 17+ executable in Visual Studio Code Settings.

See the [Visual Studio Code guide](vscode/README.md) for connection and debugging instructions. The same core workflow is also available as an [Apache NetBeans plugin](netbeans/README.md) and a [standalone JavaFX application](standalone/README.md).

## Compatibility and requirements

The debugger uses its bundled MySQL Connector/J for both MySQL and compatible MariaDB servers. Enter the server connection details directly; the debugger has no separate MySQL/MariaDB connection-type or JDBC-driver choice. Compatibility work targets modern MySQL and MariaDB releases, but not every server version, SQL mode, authentication configuration, or routine syntax has been verified yet.

You will need:

- Java 17 or newer for the Visual Studio Code extension or standalone application
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
netbeans/    Apache NetBeans frontend
standalone/  JavaFX frontend
vscode/      Visual Studio Code frontend
docs/        Documentation images and supporting files
```
