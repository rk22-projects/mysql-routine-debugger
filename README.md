# MariaDB Procedure Debugger

A debugger for MariaDB stored procedures and functions, available as:

- An Apache NetBeans plugin
- A standalone JavaFX application
- A Visual Studio Code extension

All three frontends provide breakpoints, stepping, watches, variable inspection, and execution logs.

> [!WARNING]
> Use the debugger only on development or test databases with an appropriately privileged database account.

## Before you start

The provided scripts currently target Windows 10 and 11. You need:

- A reachable MariaDB database
- Internet access during the first build
- PowerShell 5.1 or newer, which is included with Windows
- Windows Package Manager (`winget`) if prerequisites need to be installed automatically

You do not need to install Maven. The repository includes the [Apache Maven Wrapper](https://maven.apache.org/wrapper/) and downloads the correct Maven version on first use.

## Get the source code

If Git is installed, open Command Prompt and run:

```bat
git clone https://github.com/fransensteven/proc-debugger-nb.git
cd proc-debugger-nb
```

If you already cloned the project, update it with:

```bat
git pull
```

You can also download the repository as a ZIP from GitHub and extract it. In either case, open the resulting `proc-debugger-nb` folder before running an installer.

An easy way to open a command window in the correct folder is to select the folder in File Explorer, type `cmd` in the address bar, and press Enter.

## Quick installation

Choose one frontend and run its command from the repository root:

| Frontend | Command |
| --- | --- |
| NetBeans | `scripts\install-netbeans.cmd` |
| Standalone application | `scripts\install-standalone.cmd` |
| Visual Studio Code | `scripts\install-vscode.cmd` |

The scripts check the required tools, install missing tools where possible, build the selected frontend, and prepare it for use.

### NetBeans plugin

Run:

```bat
scripts\install-netbeans.cmd
```

The installer looks for an existing Apache NetBeans installation and builds against that version. It does not reject earlier or later NetBeans releases, although compatibility outside the versions already tested by the project should be verified.

If NetBeans is not found, the script downloads a default version. A different download version can be requested:

```bat
scripts\install-netbeans.cmd -NetBeansDownloadVersion 28
```

If NetBeans is installed in a custom folder, specify it directly:

```bat
scripts\install-netbeans.cmd -NetBeansHome "C:\Tools\netbeans"
```

The installer detects the NetBeans version and selects the matching user directory automatically. You can override that location when using a custom profile:

```bat
scripts\install-netbeans.cmd -NetBeansHome "C:\Tools\netbeans" -NetBeansUserDir "D:\NetBeansUser\28"
```

When installation finishes:

1. Close and restart NetBeans.
2. Open **Window → MariaDB Procedure Debugger**.
3. Alternatively, use the debugger action on a supported procedure or function in Database Explorer.

The generated `.nbm` package is also copied to `release\netbeans`. To install that file manually, open **Tools → Plugins → Downloaded → Add Plugins** in NetBeans, select the `.nbm`, and follow the prompts.

### Standalone application

Run:

```bat
scripts\install-standalone.cmd
```

The script installs Java 17 if necessary and builds the complete application. When it finishes, start the debugger with:

```bat
"release\standalone\MariaDB Procedure Debugger.cmd"
```

You can also open `release\standalone` in File Explorer and double-click **MariaDB Procedure Debugger.cmd**.

### Visual Studio Code extension

Run:

```bat
scripts\install-vscode.cmd
```

The script installs Java 17, Node.js, and Visual Studio Code when they are missing. It then builds the extension and installs or upgrades it in VS Code.

When installation finishes:

1. Reload or restart Visual Studio Code.
2. Select the MariaDB Debugger icon in the activity bar.
3. Select **Open Debugger**.

To create the extension package without installing it:

```bat
scripts\install-vscode.cmd -SkipExtensionInstall
```

The `.vsix` is copied to `release\vscode`. To install it manually, open the Extensions view in VS Code, select the **…** menu, choose **Install from VSIX…**, and select the generated file.

## Prevent automatic tool installation

Add `-SkipToolInstall` to any installer if you do not want it to install missing prerequisites:

```bat
scripts\install-standalone.cmd -SkipToolInstall
```

The script will stop and explain which tool is missing.

## Using the debugger

The general workflow is the same in every frontend:

1. Enter the MariaDB host, port, database, username, and password, then connect.
2. Select a stored procedure or function.
3. Add breakpoints on the lines where execution should pause.
4. Start the debug session.
5. Call the selected routine from another SQL client or application.
6. When execution pauses, inspect variables and watches.
7. Use Continue or Step to resume execution.
8. Stop the debug session when finished.

Use **Reset All Debug Changes** if an earlier session was interrupted or did not close normally.

## Build without installing

The installation scripts are the simplest and recommended build method. They also produce reusable packages under `release`.

To run only the shared tests:

```bat
mvnw.cmd -pl core test
```

To build the standalone application without running its installer:

```bat
mvnw.cmd -pl standalone -am clean package
```

The executable JAR is created under `standalone\target`.

To build the VS Code Java component and package the extension:

```bat
mvnw.cmd -pl vscode -am clean package
cd vscode
npm.cmd run package
```

The `.vsix` is created in the `vscode` folder. Return to the repository root with `cd ..`.

For NetBeans, use `scripts\install-netbeans.cmd`. The script detects the chosen NetBeans version and prepares the matching build dependencies before creating the `.nbm`.

## Project folders

```text
core/        Shared debugger logic
plugin/      Apache NetBeans frontend
standalone/  JavaFX frontend
vscode/      Visual Studio Code frontend
scripts/     Build and installation scripts
release/     Packages produced by the installers
```
