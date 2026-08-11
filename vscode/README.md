# MySQL Routine Debugger for Visual Studio Code

The Visual Studio Code frontend supports MySQL and MariaDB stored procedures and functions.

## Requirements

- Visual Studio Code 1.96 or newer
- Java 17 or newer
- A reachable MySQL or MariaDB server
- A database account with permission to inspect and modify routines in the selected schema

## Usage

1. Open the **MySQL Routine Debugger** activity-bar view.
2. Choose **Open Debugger**.
3. Select MySQL or MariaDB and enter the connection details.
4. Select a routine, add breakpoints, and start debugging.
5. Invoke the routine from another database client.
6. Use Continue or Step when execution pauses, then stop the session when finished.

Passwords are stored in Visual Studio Code secret storage. Use **Reset All Debug Changes** to recover from an interrupted session.
