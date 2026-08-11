# MySQL Routine Debugger for Visual Studio Code

This module integrates the MySQL Routine Debugger into Visual Studio Code. It provides a dedicated debugger panel for MySQL and MariaDB stored procedures and functions, with searchable routine selection, breakpoints, Continue, Step Into, Step Over, Step Out, variable watches, and execution logs.

The extension runs its Java debugger server in the background and stores database passwords in Visual Studio Code secret storage.

> [!WARNING]
> Use the debugger only on development or test databases. It temporarily replaces routines and creates supporting objects in the selected schema.

## Requirements

- Visual Studio Code 1.96 or newer
- Java 17 or newer
- A reachable MySQL or MariaDB server
- A database account allowed to inspect, create, replace, and restore routines and to create supporting schema objects

## Install

1. Open the project's [latest release](https://github.com/rk22-projects/mysql-routine-debugger/releases/latest).
2. Download the Visual Studio Code extension file ending in `.vsix`.
3. In Visual Studio Code, open the **Extensions** view.
4. Open the **Views and More Actions** (`...`) menu and select **Install from VSIX...**.
5. Choose the downloaded `.vsix` file and reload Visual Studio Code if prompted.

You can also install it from a terminal:

```shell
code --install-extension mysql-routine-debugger-<version>.vsix
```

The latest-release link will become available when the first packaged release is published.

## Open the debugger

1. Run **MySQL Routine Debugger: Open Debugger** from the Command Palette, or select its status-bar item.
2. Select **Connect**, choose MySQL or MariaDB, and enter the connection details. Use **Disconnect** when finished; disconnecting is disabled during an active debug session.
3. Type in the routine field to filter its autocomplete dropdown, then choose a routine to load it automatically. Add breakpoints and start debugging.
4. Invoke the routine from another database client.
5. Use Continue (`F5`), Step Into (`F7`), Step Out (`Ctrl+F7`), or Step Over (`F8`) when execution pauses, then stop the session when finished. Step Into and Step Out appear as separate buttons only where each operation applies.

The debug log is hidden by default; use **Show Log** when you need it. Use **Reset All Debug Changes** to recover from an interrupted session.
