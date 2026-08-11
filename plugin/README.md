# MySQL Routine Debugger for Apache NetBeans

This module integrates the MySQL Routine Debugger into Apache NetBeans. It adds a debugger window for MySQL and MariaDB stored procedures and functions, plus actions in the Database Explorer for opening routines in the debugger.

The frontend supports breakpoints, Continue, Step Into, Step Over, Step Out, variable watches, and execution logs. It uses NetBeans database connections and includes the MySQL and MariaDB JDBC drivers.

> [!WARNING]
> Use the debugger only on development or test databases. It temporarily replaces routines and creates supporting objects in the selected schema.

## Requirements

- Apache NetBeans 27 or a compatible newer release
- A reachable MySQL or MariaDB server
- A database account allowed to inspect, create, replace, and restore routines and to create supporting schema objects

## Install

1. Open the project's [latest release](https://github.com/rk22-projects/mysql-routine-debugger/releases/latest).
2. Download the NetBeans plugin file ending in `.nbm`.
3. In NetBeans, open **Tools > Plugins** and select the **Downloaded** tab.
4. Select **Add Plugins**, choose the downloaded `.nbm` file, and complete the installer.
5. Restart NetBeans if prompted.

The latest-release link will become available when the first packaged release is published.

## Open the debugger

Open **Window > MySQL Routine Debugger**. You can also find a debugger action on stored procedures and functions in the NetBeans Database Explorer.

Connect to a development or test database, select a routine, add breakpoints, and start debugging. Invoke the instrumented routine from a separate SQL client or application connection.

