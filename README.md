# MariaDB Procedure Debugger

A line-oriented debugger for MariaDB stored procedures and functions, available through three frontends:

| Frontend | Best for | Output |
| --- | --- | --- |
| Apache NetBeans plugin | Database Explorer integration and IDE workflow | `.nbm` module |
| Standalone JavaFX app | Running the debugger without an IDE | Executable fat JAR and launcher |
| Visual Studio Code extension | A VS Code-native debugger panel | `.vsix` extension |

All frontends use the same Java `core` module for routine instrumentation, breakpoints, execution control, watches, and log polling.

> [!WARNING]
> The debugger temporarily replaces database routines and creates `_dbg_*` tables and routines. It also introduces checkpoints that commit while execution is paused. Use it only on development or test databases where you are authorized to alter routines. Do not use it against production workloads.

## Features

- Load MariaDB procedures and functions directly from `information_schema`.
- Set line breakpoints on executable SQL statements.
- Continue with F5 and step with F8.
- Inspect parameter and local-variable values captured by instrumentation.
- Add watches manually or from identifiers in the source view.
- Review a timestamped execution log.
- Restore the original routine when debugging stops.
- Reset all debugger-created objects after an interrupted session.

The debugger is implemented through SQL instrumentation rather than MariaDB server internals. It creates an instrumented `_dbg_<routine>` copy, preserves the original DDL, and temporarily replaces the public routine with a proxy. `_dbg_state` coordinates pause/continue/step commands, while `_dbg_log` carries execution and variable updates back to the frontend.

## Supported environment

The provided install scripts currently target Windows 10/11 because the standalone Maven module packages Windows JavaFX natives.

Required for every frontend:

- A MariaDB database reachable over JDBC.
- A database account allowed to create, alter, and drop routines and debugger tables.
- PowerShell 5.1 or newer.
- Internet access on the first build so Maven can download dependencies.

The repository includes the official [Apache Maven Wrapper](https://maven.apache.org/wrapper/), pinned to Maven 3.9.11. Maven itself does not need to be installed globally. If Java 17 is absent, the scripts install Eclipse Temurin JDK 17 through Windows Package Manager.

## Install after a clean pull

Clone the repository, open Command Prompt or PowerShell in its root, and run exactly one installer.

### Apache NetBeans plugin

```bat
scripts\install-netbeans.cmd
```

This script:

1. Finds or installs Java 17.
2. Finds Apache NetBeans 27, or downloads the official NetBeans 27 binary archive and verifies its SHA-512 checksum.
3. Registers NetBeans' DB Explorer API JAR in the local Maven repository.
4. Builds the shared core and `.nbm` module.
5. Installs the module into `%APPDATA%\NetBeans\27`.

Restart NetBeans, then choose **Window → MariaDB Procedure Debugger**. A **Debug in Procedure Debugger…** action is also added to stored procedures and functions in the Database Explorer.

Optional parameters:

```powershell
scripts\install-netbeans.cmd -NetBeansHome "C:\Tools\netbeans" -NetBeansUserDir "D:\NetBeansUser\27"
scripts\install-netbeans.cmd -SkipToolInstall
```

The built module is copied to `release\netbeans`.

### Standalone JavaFX application

```bat
scripts\install-standalone.cmd
```

This script finds or installs Java 17, builds a self-contained JAR containing JavaFX and the MariaDB driver, and creates:

```text
release\standalone\
├── MariaDB Procedure Debugger.cmd
└── proc-debugger-standalone.jar
```

Launch it with:

```bat
"release\standalone\MariaDB Procedure Debugger.cmd"
```

Use `-SkipToolInstall` to require tools to be present instead of allowing the script to install them.

### Visual Studio Code extension

```bat
scripts\install-vscode.cmd
```

This script:

1. Finds or installs Java 17, Node.js LTS, and Visual Studio Code.
2. Builds the shared Java bridge used by the extension.
3. Packages the extension with `@vscode/vsce`.
4. Installs or upgrades the resulting VSIX using `code --install-extension --force`.

Reload VS Code after installation, open the MariaDB Debugger activity-bar icon, and select **Open Debugger**. Java 17 is discovered automatically at runtime; users normally do not need to configure a Java path.

To build the VSIX without installing it into VS Code:

```bat
scripts\install-vscode.cmd -SkipExtensionInstall
```

The packaged extension is copied to `release\vscode`.

## Installer policy and options

The scripts declare every external action before performing it:

- Tool installation uses `winget install --exact --id ...` with package and source agreements accepted for unattended setup.
- NetBeans 27 is downloaded only from the Apache archive and verified against Apache's published SHA-512 checksum.
- Maven is downloaded by the committed Maven Wrapper from Maven Central.
- Java, Node.js, and VS Code are installed only when missing.

Pass `-SkipToolInstall` to any PowerShell installer if automated tool installation is not desired. The script will then stop with a precise missing-prerequisite message.

## Debugging workflow

1. Connect to a MariaDB host and schema.
2. Select and load a procedure or function.
3. Set breakpoints in the source gutter.
4. Choose **Debug**. The original DDL is saved before the routine is replaced.
5. Call the routine normally from a separate SQL client or application.
6. When execution pauses, inspect watches and use F5 or F8.
7. Choose **Stop Debugging** to release the paused call and restore the original routine.

Clearing the frontend log does not end the active debug session. Closing or stopping the debugger sends `continue` to release a paused database call before restoration.

## Recovery and database objects

The debugger uses these database objects:

- `_dbg_log`, `_dbg_breakpoints`, `_dbg_state`, and `_dbg_originals` tables.
- `_dbg_log_var` and `_dbg_checkpoint` helper procedures.
- Temporary `_dbg_<name>` and `_orig_<name>` routine copies.

Use **Reset All Debug Changes** in any frontend to restore all saved originals and remove transient debugger objects. `_dbg_originals` is intentionally retained until restoration succeeds so original DDL remains recoverable after a crash.

If a client remains blocked after an abnormal frontend termination, a DBA can release debugger waits before reopening the frontend:

```sql
UPDATE _dbg_state
SET status = 'continue'
WHERE status IN ('paused', 'step');
```

Then use **Reset All Debug Changes** to restore routines cleanly.

## Manual development builds

Run the shared-core test suite:

```bat
mvnw.cmd -pl core test
```

Build an individual frontend and everything it depends on:

```bat
mvnw.cmd -pl standalone -am package
mvnw.cmd -pl vscode -am package
```

Package the VS Code extension after its bridge has been built:

```bat
cd vscode
npm run package
```

The NetBeans module has one additional local dependency: `org-netbeans-modules-db.jar` from NetBeans 27. The NetBeans install script registers it automatically, so using that script is the recommended clean-build path.

## Project structure

```text
core/        Shared JDBC, instrumentation, polling, and session logic
plugin/      Apache NetBeans module
standalone/  JavaFX desktop frontend
vscode/      VS Code extension and Java JSON-lines bridge
scripts/     Reproducible Windows installers
```

Core unit tests live under `core/src/test`. Frontend builds are Maven reactor modules declared in the root `pom.xml`.

## Known limitations

- Instrumentation is line- and statement-oriented; unusual formatting or highly dynamic SQL may not map perfectly to source lines.
- Static routine calls can be detected, but dynamically constructed calls cannot be discovered ahead of time.
- Debugging modifies database DDL and should be isolated from concurrent schema migrations.
- The bundled standalone distribution is currently Windows-only.
