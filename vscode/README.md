# MySQL Routine Debugger for Visual Studio Code

The Visual Studio Code frontend supports MySQL and MariaDB stored procedures and functions.

## Requirements

- Visual Studio Code 1.96 or newer
- Java 17 or newer
- A reachable MySQL or MariaDB server
- A database account with permission to inspect and modify routines in the selected schema

## Usage

1. Run **MySQL Routine Debugger: Open Debugger** from the Command Palette, or select its status-bar item.
2. Select **Connect**, choose MySQL or MariaDB, and enter the connection details. Use **Disconnect** when finished; disconnecting is disabled during an active debug session.
3. Type in the routine field to filter its autocomplete dropdown, then choose a routine to load it automatically. Add breakpoints and start debugging.
4. Invoke the routine from another database client.
5. Use Continue (`F5`), Step Into (`F7`), Step Out (`Ctrl+F7`), or Step Over (`F8`) when execution pauses, then stop the session when finished. Step Into and Step Out appear as separate buttons only where each operation applies.

The debug log is hidden by default; use **Show Log** when you need it. Passwords are stored in Visual Studio Code secret storage. Use **Reset All Debug Changes** to recover from an interrupted session.
