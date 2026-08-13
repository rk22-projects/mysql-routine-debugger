# MySQL Routine Debugger for Visual Studio Code

> [!CAUTION]
> **Early alpha software:** this project has undergone only limited testing. Features may be incomplete, behavior may change without notice, and defects may affect database objects or data. Do not use it with production databases. Use only isolated development or test environments and ensure you have current backups.

Issue reports and help with collaborative testing are very welcome. If you try the debugger, please [open an issue](https://github.com/rk22-projects/mysql-routine-debugger/issues) to share problems, suggestions, or successful test results. Every constructive report helps make the project safer and more useful.

This module integrates the MySQL Routine Debugger into Visual Studio Code. It provides a dedicated debugger panel for MySQL and MariaDB stored procedures and functions, with searchable routine selection, breakpoints, Continue, Step Into, Step Over, Step Out, variable watches, and execution logs.

The extension runs its Java debugger server in the background and stores database passwords in Visual Studio Code secret storage.

## Requirements

- Visual Studio Code 1.96 or newer
- Java 17 or newer
- A reachable MySQL or MariaDB server
- A database account with the required routine and schema privileges

## Install from the Visual Studio Marketplace

1. Install a Java 17 or newer runtime if one is not already available.
2. In Visual Studio Code, open the **Extensions** view (`Ctrl+Shift+X` on Windows and Linux, or `Cmd+Shift+X` on macOS).
3. Search for **MySQL Routine Debugger** by **RK22**, or open the extension in the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=RK22.mysql-routine-debugger).
4. Select **Install** and reload Visual Studio Code if prompted.
5. Run **MySQL Routine Debugger: Open Debugger** from the Command Palette.

You can install the Marketplace version from a terminal instead:

```shell
code --install-extension RK22.mysql-routine-debugger
```

The extension normally discovers Java automatically. If it cannot find Java, open Visual Studio Code Settings, search for **MySQL Routine Debugger: Java Path**, and set `mysqlRoutineDebugger.javaPath` to a Java 17+ executable.

### Manual VSIX installation

To install without the Marketplace, download the `.vsix` file from the project's [latest release](https://github.com/rk22-projects/mysql-routine-debugger/releases/latest). In the **Extensions** view, open the **Views and More Actions** (`...`) menu, select **Install from VSIX...**, and choose the downloaded file.

## Usage documentation

See the [Visual Studio Code usage guide](../docs/usage/vscode.md) for connections, breakpoints, stepping, watches, logs, nested routines, and recovery.
