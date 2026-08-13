# MySQL Routine Debugger — Standalone

> [!CAUTION]
> **Early alpha software:** this project has undergone only limited testing. Features may be incomplete, behavior may change without notice, and defects may modify routines or other database objects incorrectly. Do not use it with production databases. Use only isolated development or test environments and ensure you have current backups.

Issue reports and help with collaborative testing are very welcome. If you try the debugger, please [open an issue](https://github.com/rk22-projects/mysql-routine-debugger/issues) to share problems, suggestions, or successful test results. Every constructive report helps make the project safer and more useful.

This module provides a desktop MySQL and MariaDB routine debugger that runs independently of an IDE. Its JavaFX interface lets you connect to a database, browse stored procedures and functions, set breakpoints, step through execution, inspect variables and watches, and review execution logs.

Use this frontend when you want the complete debugging workflow without installing a NetBeans or Visual Studio Code extension.

## Requirements

- A 64-bit Java 17 or newer runtime
- Windows or Linux matching the downloaded package
- A reachable MySQL or MariaDB server
- A database account allowed to inspect, create, replace, and restore routines and to create supporting schema objects

## Install and run

1. Open the project's [latest release](https://github.com/rk22-projects/mysql-routine-debugger/releases/latest).
2. Download the standalone package for your operating system.
3. Extract it if it is distributed as an archive.
4. Start it with the included launcher, or run the standalone JAR from a terminal:

   ```shell
   java -jar mysql-routine-debugger-standalone.jar
   ```

Use the package matching your operating system because it contains platform-specific JavaFX libraries.

## Start debugging

Connect to a development or test database, select a routine, add breakpoints, and start debugging. The bundled MySQL Connector/J is used for both MySQL and compatible MariaDB servers; the debugger has no separate connection-type or JDBC-driver choice. Invoke the instrumented routine from a separate SQL client or application connection, then use Continue, Step Into, Step Over, or Step Out when execution pauses.
