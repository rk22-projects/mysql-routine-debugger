# MariaDB Procedure Debugger

A debugger for MariaDB stored procedures and functions, available as an Apache NetBeans plugin, a standalone JavaFX application, and a Visual Studio Code extension.

All frontends provide breakpoints, stepping, watches, variable inspection, and execution logs.

> [!WARNING]
> Use the debugger only on development or test databases with an appropriately privileged database account.

## What you need

- Windows 10/11 or a supported Linux distribution
- A reachable MariaDB database
- Internet access during the first build
- Git, unless you download the source as a ZIP
- Apache NetBeans already installed when installing the NetBeans frontend
- Visual Studio Code already installed when installing the VS Code frontend

The installers obtain supporting build tools such as Java and Node.js where possible. They never install NetBeans or Visual Studio Code. Maven does not need to be installed separately because the repository contains the [Apache Maven Wrapper](https://maven.apache.org/wrapper/).

## Download a compiled release

Ready-to-use alpha packages are available on the [GitHub Releases page](https://github.com/rk22-projects/proc-debugger-nb/releases). Choose the `.nbm` for NetBeans, the `.vsix` for Visual Studio Code, or the JAR matching your operating system for the standalone application. Java 17 or newer is required by the standalone JAR.

## Get the source code

Open a terminal and run:

```text
git clone https://github.com/rk22-projects/proc-debugger-nb.git
cd proc-debugger-nb
```

For an existing clone, open its folder and run `git pull` instead. You may also download the repository as a ZIP from GitHub and extract it.

## Windows installation

Open the project folder in File Explorer, type `cmd` in the address bar, and press Enter. Then run one of these commands:

| Frontend | Command |
| --- | --- |
| NetBeans | `scripts\install-netbeans.cmd` |
| Standalone | `scripts\install-standalone.cmd` |
| Visual Studio Code | `scripts\install-vscode.cmd` |

The `.cmd` file is the convenient entry point. Each one invokes a self-contained PowerShell installer in the same folder.

### NetBeans on Windows

```bat
scripts\install-netbeans.cmd
```

The installer requires an existing Apache NetBeans installation and builds for the detected version. If NetBeans cannot be found, it stops without installing the frontend. Earlier and later releases are accepted, but versions not previously tested by the project should be verified before wider use.

For a custom installation or user profile:

```bat
scripts\install-netbeans.cmd -NetBeansHome "C:\Tools\netbeans"
scripts\install-netbeans.cmd -NetBeansHome "C:\Tools\netbeans" -NetBeansUserDir "D:\NetBeansUser\28"
```

After installation, restart NetBeans and open **Window → MariaDB Procedure Debugger**. The generated `.nbm` is also available under `release\netbeans`.

To install the `.nbm` manually, open **Tools → Plugins → Downloaded → Add Plugins** in NetBeans, select the file, and follow the prompts.

### Standalone application on Windows

```bat
scripts\install-standalone.cmd
```

When the build finishes, double-click `MariaDB Procedure Debugger.cmd` under `release\standalone`, or run:

```bat
"release\standalone\MariaDB Procedure Debugger.cmd"
```

### Visual Studio Code on Windows

```bat
scripts\install-vscode.cmd
```

The installer builds and installs the extension. Reload VS Code, select the MariaDB Debugger icon in the activity bar, and choose **Open Debugger**.

Visual Studio Code must already be installed. If it cannot be found, the installer stops before building or installing the extension.

To build a `.vsix` without installing it:

```bat
scripts\install-vscode.cmd -SkipExtensionInstall
```

The `.vsix` is placed under `release\vscode`. It can be installed manually from the Extensions view using **… → Install from VSIX…**.

Add `-SkipToolInstall` to any Windows command to prevent automatic installation of missing tools.

## Linux installation

Open a terminal in the project directory. The Linux installers support `apt`, `dnf`, `yum`, `pacman`, and `zypper`. They use `sudo` only when a system package must be installed.

Run one of these commands:

| Frontend | Command |
| --- | --- |
| NetBeans | `bash scripts/install-netbeans.sh` |
| Standalone | `bash scripts/install-standalone.sh` |
| Visual Studio Code | `bash scripts/install-vscode.sh` |

Using `bash` explicitly works even when executable file permissions were lost while extracting a ZIP.

### NetBeans on Linux

```bash
bash scripts/install-netbeans.sh
```

The installer requires an existing NetBeans installation in a common location. If it cannot find one, it stops without installing the frontend. Use `--netbeans-home` for a custom installation:

```bash
bash scripts/install-netbeans.sh --netbeans-home /opt/netbeans
bash scripts/install-netbeans.sh --netbeans-home /opt/netbeans --netbeans-user-dir "$HOME/.netbeans/28"
```

Restart NetBeans after installation and open **Window → MariaDB Procedure Debugger**. The reusable `.nbm` is placed under `release/netbeans`.

### Standalone application on Linux

```bash
bash scripts/install-standalone.sh
```

The Linux build automatically includes the Linux JavaFX libraries. Start it with:

```bash
"release/standalone/MariaDB Procedure Debugger.sh"
```

### Visual Studio Code on Linux

```bash
bash scripts/install-vscode.sh
```

Visual Studio Code must already be installed. The installer stops before building if it cannot find the `code` command. It does not install VS Code or configure a vendor package repository.

To produce the `.vsix` without installing it:

```bash
bash scripts/install-vscode.sh --skip-extension-install
```

Add `--skip-tool-install` to any Linux installer to report missing tools without installing system packages.

## Using the debugger

The general workflow is the same in every frontend:

1. Enter the MariaDB host, port, database, username, and password, then connect.
2. Select a stored procedure or function.
3. Add breakpoints and start debugging.
4. Call the routine from another SQL client or application.
5. Inspect variables and watches when execution pauses.
6. Use Continue or Step to resume execution.
7. Stop the debug session when finished.

Use **Reset All Debug Changes** if an earlier session did not close normally.

## Build without installing

The frontend installers are the recommended build method because they also check prerequisites and place reusable packages under `release`.

Run the shared tests on Windows:

```bat
mvnw.cmd -pl core test
```

Run them on Linux:

```bash
bash mvnw -pl core test
```

Build the standalone application directly:

```text
Windows: mvnw.cmd -pl standalone -am clean package
Linux:   bash mvnw -pl standalone -am clean package
```

Build and package the VS Code extension directly:

```text
Windows: mvnw.cmd -pl vscode -am clean package
         cd vscode
         npm.cmd run package

Linux:   bash mvnw -pl vscode -am clean package
         cd vscode
         npm run package
```

Use the NetBeans installer for NetBeans builds because it detects the selected NetBeans release and prepares matching build dependencies.

## Project folders

```text
core/        Shared debugger logic
plugin/      Apache NetBeans frontend
standalone/  JavaFX frontend
vscode/      Visual Studio Code frontend
scripts/     Self-contained Windows and Linux installers
release/     Packages produced by the installers
```
