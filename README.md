# MariaDB Procedure Debugger

A debugger for MariaDB stored procedures and functions, available as:

- An Apache NetBeans plugin
- A standalone JavaFX application
- A Visual Studio Code extension

The frontends provide breakpoints, stepping, watches, variable inspection, and execution logs.

> [!WARNING]
> Use the debugger only on development or test databases with an appropriately privileged database account.

## Requirements

- Windows 10 or 11
- PowerShell 5.1 or newer
- A reachable MariaDB database
- Internet access during the first build
- Windows Package Manager (`winget`) if tools must be installed automatically

The scripts install missing prerequisites where possible. Maven is supplied through the committed [Apache Maven Wrapper](https://maven.apache.org/wrapper/), so no global Maven installation is needed.

## Install after a clean pull

Open Command Prompt or PowerShell in the repository root and run one installer:

| Frontend | Command | Result |
| --- | --- | --- |
| NetBeans | `scripts\install-netbeans.cmd` | Builds and installs the `.nbm` module |
| Standalone | `scripts\install-standalone.cmd` | Creates an executable JAR and launcher |
| VS Code | `scripts\install-vscode.cmd` | Builds and installs the `.vsix` extension |

### NetBeans

The installer requires Apache NetBeans 27. It uses an existing compatible installation or downloads and verifies NetBeans 27 automatically.

After installation, restart NetBeans and open **Window → MariaDB Procedure Debugger**. The debugger is also available from supported procedures and functions in Database Explorer.

Optional custom locations:

```bat
scripts\install-netbeans.cmd -NetBeansHome "C:\Tools\netbeans" -NetBeansUserDir "D:\NetBeansUser\27"
```

The built module is placed in `release\netbeans`.

### Standalone

Start the installed application with:

```bat
"release\standalone\MariaDB Procedure Debugger.cmd"
```

The distribution is placed in `release\standalone`.

### Visual Studio Code

Reload VS Code after installation. Open the MariaDB Debugger icon in the activity bar and select **Open Debugger**.

To build the VSIX without installing it:

```bat
scripts\install-vscode.cmd -SkipExtensionInstall
```

The built extension is placed in `release\vscode`.

### Disable automatic tool installation

Pass `-SkipToolInstall` to any installer to stop instead of installing a missing prerequisite:

```bat
scripts\install-standalone.cmd -SkipToolInstall
```

## Basic usage

1. Connect to a MariaDB host and schema.
2. Select a stored procedure or function.
3. Set breakpoints and start debugging.
4. Call the routine from another SQL client or application.
5. Inspect variables and use Continue or Step while paused.
6. Stop the debug session when finished.

Use **Reset All Debug Changes** if a previous session ended unexpectedly.

## Development

Run the shared-core tests:

```bat
mvnw.cmd -pl core test
```

Build a frontend and its dependencies:

```bat
mvnw.cmd -pl standalone -am package
mvnw.cmd -pl vscode -am package
```

Use `scripts\install-netbeans.cmd` for NetBeans builds because it prepares the required NetBeans dependencies.

```text
core/        Shared debugger logic
plugin/      Apache NetBeans frontend
standalone/  JavaFX frontend
vscode/      Visual Studio Code frontend
scripts/     Installation scripts
```
