# MySQL Routine Debugger for Apache NetBeans

> [!CAUTION]
> **Early alpha software:** this project has undergone only limited testing. Features may be incomplete, behavior may change without notice, and defects may affect database objects or data. Do not use it with production databases. Use only isolated development or test environments and ensure you have current backups.

Issue reports and help with collaborative testing are very welcome. If you try the debugger, please [open an issue](https://github.com/rk22-projects/mysql-routine-debugger/issues) to share problems, suggestions, or successful test results. Every constructive report helps make the project safer and more useful.

This module integrates the MySQL Routine Debugger into Apache NetBeans. It adds a debugger window for MySQL and MariaDB stored procedures and functions, plus actions in the Database Explorer for opening routines in the debugger.

The frontend supports breakpoints, Continue, Step Into, Step Over, Step Out, variable watches, and execution logs. It uses NetBeans database connections and MySQL Connector/J for both MySQL and compatible MariaDB servers.

## Requirements

- Apache NetBeans 27 or a compatible newer release
- A reachable MySQL or MariaDB server
- A database account with the required routine and schema privileges

## Install

1. Open the project's [latest release](https://github.com/rk22-projects/mysql-routine-debugger/releases/latest).
2. Download the NetBeans plugin file ending in `.nbm`.
3. In NetBeans, open **Tools > Plugins** and select the **Downloaded** tab.
4. Select **Add Plugins**, choose the downloaded `.nbm` file, and complete the installer.
5. Restart NetBeans if prompted.

## Usage documentation

See the [Apache NetBeans usage guide](../docs/usage/netbeans.md) for connections, Database Explorer integration, breakpoints, stepping, watches, logs, nested routines, and recovery.
