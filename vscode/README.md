# MariaDB Procedure Debugger for VS Code

This module is a native VS Code frontend for the debugger in this repository. It uses the same Java `core` module as the NetBeans and standalone frontends, through a small bundled JSON-lines server.

## Build and run

Requirements: Java 17+, Maven, VS Code 1.96+, and Node.js when packaging a VSIX. The extension automatically discovers Java 17+ from `JAVA_HOME`, PATH, standard OS installation locations, and common IDE-bundled JDKs; users do not normally need to configure a Java path.

1. From the repository root, run `mvn package`. This builds the shared core and the executable bridge at `vscode/target/proc-debugger-vscode-server.jar`.
2. Open the `vscode` directory in VS Code and press F5 to launch an Extension Development Host.
3. Open the MariaDB Debugger activity-bar view and choose **Open Debugger**.

To create an installable extension, run `npm run package` in this directory after the Maven build. The package script downloads `@vscode/vsce` through `npx` if it is not already installed.

## Workflow

- Use the debugger panel's connection form, then select a procedure or function from its toolbar.
- Click the source gutter or press F9 on a selected executable line to toggle a breakpoint.
- Choose **Start Debugging**, then invoke the routine normally from any SQL client.
- Use F5 to continue and F8 to step when the routine pauses.
- Right-click identifiers to add them to **Watches** and hover identifiers to inspect their latest value.
- Choose **Stop Debugging** to restore the original routine.

Connection fields are regular VS Code settings. The password is stored only in VS Code secret storage.

> The debugger temporarily replaces selected database routines and creates `_dbg_*` objects. Use it only against a database where you are authorized to alter routines. The **Reset All Debug Changes** command is the recovery path after an interrupted session.
