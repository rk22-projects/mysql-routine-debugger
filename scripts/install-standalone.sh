#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_TOOL_INSTALL=0

for arg in "$@"; do
    case "$arg" in
        --skip-tool-install) SKIP_TOOL_INSTALL=1 ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

run() {
    printf '> '
    printf '%q ' "$@"
    printf '\n'
    "$@"
}

as_root() {
    if [[ ${EUID:-$(id -u)} -eq 0 ]]; then "$@"
    elif command -v sudo >/dev/null 2>&1; then sudo "$@"
    else echo "Root access is required to install packages. Install them manually or add --skip-tool-install." >&2; exit 1
    fi
}

install_packages() {
    if command -v apt-get >/dev/null 2>&1; then
        as_root apt-get update
        as_root apt-get install -y "$@"
    elif command -v dnf >/dev/null 2>&1; then as_root dnf install -y "$@"
    elif command -v yum >/dev/null 2>&1; then as_root yum install -y "$@"
    elif command -v pacman >/dev/null 2>&1; then as_root pacman -S --needed --noconfirm "$@"
    elif command -v zypper >/dev/null 2>&1; then as_root zypper --non-interactive install "$@"
    else echo "No supported package manager was found. Install Java 17 and curl manually." >&2; exit 1
    fi
}

java_major() {
    java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}

ensure_tools() {
    local major=""
    if command -v java >/dev/null 2>&1; then major="$(java_major)"; fi
    if [[ -z "$major" || "$major" -lt 17 ]]; then
        [[ $SKIP_TOOL_INSTALL -eq 0 ]] || { echo "Java 17+ is required." >&2; exit 1; }
        if command -v apt-get >/dev/null 2>&1; then install_packages openjdk-17-jdk curl
        elif command -v pacman >/dev/null 2>&1; then install_packages jdk17-openjdk curl
        else install_packages java-17-openjdk-devel curl
        fi
    elif ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
        [[ $SKIP_TOOL_INSTALL -eq 0 ]] || { echo "curl or wget is required." >&2; exit 1; }
        install_packages curl
    fi
    local java_bin
    java_bin="$(readlink -f "$(command -v java)")"
    export JAVA_HOME="$(dirname "$(dirname "$java_bin")")"
    echo "Using Java $(java_major): $java_bin"
}

reset_release() {
    local path="$1"
    [[ "$path" == "$REPO_ROOT"/* ]] || { echo "Unsafe output path: $path" >&2; exit 1; }
    rm -rf -- "$path"
    mkdir -p "$path"
}

ensure_tools
echo "Building the standalone JavaFX application for Linux..."
run bash "$REPO_ROOT/mvnw" -pl standalone -am clean package

JAR="$REPO_ROOT/standalone/target/proc-debugger-standalone.jar"
[[ -f "$JAR" ]] || { echo "Standalone artifact was not produced: $JAR" >&2; exit 1; }
RELEASE="$REPO_ROOT/release/standalone"
reset_release "$RELEASE"
cp "$JAR" "$RELEASE/"

printf '%s\n' '#!/usr/bin/env bash' 'SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"' 'exec java -jar "$SCRIPT_DIR/proc-debugger-standalone.jar"' > "$RELEASE/MariaDB Procedure Debugger.sh"
chmod +x "$RELEASE/MariaDB Procedure Debugger.sh"

echo "Installed standalone application in: $RELEASE"
echo "Run: \"$RELEASE/MariaDB Procedure Debugger.sh\""
