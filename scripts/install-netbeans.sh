#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_TOOL_INSTALL=0
NETBEANS_HOME="${NETBEANS_HOME:-}"
NETBEANS_USER_DIR=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-tool-install) SKIP_TOOL_INSTALL=1; shift ;;
        --netbeans-home) NETBEANS_HOME="$2"; shift 2 ;;
        --netbeans-user-dir) NETBEANS_USER_DIR="$2"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

run() { printf '> '; printf '%q ' "$@"; printf '\n'; "$@"; }
as_root() {
    if [[ ${EUID:-$(id -u)} -eq 0 ]]; then "$@"
    elif command -v sudo >/dev/null 2>&1; then sudo "$@"
    else echo "Root access is required to install packages. Install them manually or add --skip-tool-install." >&2; exit 1
    fi
}

install_packages() {
    if command -v apt-get >/dev/null 2>&1; then as_root apt-get update; as_root apt-get install -y "$@"
    elif command -v dnf >/dev/null 2>&1; then as_root dnf install -y "$@"
    elif command -v yum >/dev/null 2>&1; then as_root yum install -y "$@"
    elif command -v pacman >/dev/null 2>&1; then as_root pacman -S --needed --noconfirm "$@"
    elif command -v zypper >/dev/null 2>&1; then as_root zypper --non-interactive install "$@"
    else echo "No supported package manager was found." >&2; exit 1
    fi
}

java_major() { java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1; }
ensure_tools() {
    local major=""
    if command -v java >/dev/null 2>&1; then major="$(java_major)"; fi
    if [[ -z "$major" || "$major" -lt 17 ]]; then
        [[ $SKIP_TOOL_INSTALL -eq 0 ]] || { echo "Java 17+ is required." >&2; exit 1; }
        if command -v apt-get >/dev/null 2>&1; then install_packages openjdk-17-jdk curl unzip
        elif command -v pacman >/dev/null 2>&1; then install_packages jdk17-openjdk curl unzip
        else install_packages java-17-openjdk-devel curl unzip
        fi
    fi
    if ! command -v unzip >/dev/null 2>&1; then
        [[ $SKIP_TOOL_INSTALL -eq 0 ]] || { echo "unzip is required." >&2; exit 1; }
        install_packages unzip
    fi
    if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
        [[ $SKIP_TOOL_INSTALL -eq 0 ]] || { echo "curl or wget is required." >&2; exit 1; }
        install_packages curl
    fi
    local java_bin
    java_bin="$(readlink -f "$(command -v java)")"
    export JAVA_HOME="$(dirname "$(dirname "$java_bin")")"
    echo "Using Java $(java_major): $java_bin"
}

netbeans_major() {
    local home="$1" manifest
    [[ -f "$home/platform/core/core.jar" ]] || return 1
    manifest="$(unzip -p "$home/platform/core/core.jar" META-INF/MANIFEST.MF 2>/dev/null || true)"
    printf '%s\n' "$manifest" | tr -d '\r' | sed -n 's/^OpenIDE-Module-Implementation-Version:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n 1
}

valid_netbeans_home() {
    local home="$1"
    [[ -n "$home" && -x "$home/bin/netbeans" && -f "$home/ide/modules/org-netbeans-modules-db.jar" ]]
}

reset_repo_directory() {
    local path="$1"
    [[ "$path" == "$REPO_ROOT"/* ]] || { echo "Unsafe repository path: $path" >&2; exit 1; }
    rm -rf -- "$path"
    mkdir -p "$path"
}

if ! valid_netbeans_home "$NETBEANS_HOME"; then
    candidates=("/opt/netbeans" "/usr/local/netbeans")
    for candidate in "$REPO_ROOT"/.tools/netbeans-*/netbeans; do
        [[ -d "$candidate" ]] && candidates+=("$candidate")
    done
    if command -v netbeans >/dev/null 2>&1; then candidates+=("$(dirname "$(dirname "$(readlink -f "$(command -v netbeans)")")")"); fi
    for candidate in "${candidates[@]}"; do
        if valid_netbeans_home "$candidate"; then NETBEANS_HOME="$candidate"; break; fi
    done
fi

valid_netbeans_home "$NETBEANS_HOME" || { echo "Apache NetBeans is a prerequisite and was not found. Install it locally or pass --netbeans-home." >&2; exit 1; }
ensure_tools
NETBEANS_MAJOR="$(netbeans_major "$NETBEANS_HOME")"
[[ -n "$NETBEANS_MAJOR" ]] || { echo "Could not determine the installed NetBeans version." >&2; exit 1; }
NETBEANS_RELEASE="RELEASE${NETBEANS_MAJOR}0"
NETBEANS_USER_DIR="${NETBEANS_USER_DIR:-$HOME/.netbeans/$NETBEANS_MAJOR}"
echo "Using Apache NetBeans $NETBEANS_MAJOR: $NETBEANS_HOME"

DB_MODULE="$NETBEANS_HOME/ide/modules/org-netbeans-modules-db.jar"
echo "Registering the NetBeans DB Explorer API in the local Maven repository..."
run bash "$REPO_ROOT/mvnw" -N org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    "-Dfile=$DB_MODULE" -DgroupId=org.netbeans.modules -DartifactId=org-netbeans-modules-db \
    "-Dversion=$NETBEANS_RELEASE" -Dpackaging=jar -DgeneratePom=true

echo "Building the NetBeans plugin..."
run bash "$REPO_ROOT/mvnw" "-Dnb.version=$NETBEANS_RELEASE" -pl plugin -am clean package
NBM="$REPO_ROOT/plugin/target/proc-debugger-nb-1.0-SNAPSHOT.nbm"
[[ -f "$NBM" ]] || { echo "NetBeans module was not produced: $NBM" >&2; exit 1; }

RELEASE="$REPO_ROOT/release/netbeans"
reset_repo_directory "$RELEASE"
cp "$NBM" "$RELEASE/"

EXTRACT="$REPO_ROOT/.tools/nbm-extract"
reset_repo_directory "$EXTRACT"
run unzip -q "$NBM" -d "$EXTRACT"
mkdir -p "$NETBEANS_USER_DIR"
cp -a "$EXTRACT/netbeans/." "$NETBEANS_USER_DIR/"

echo "Installed NetBeans plugin: $RELEASE/$(basename "$NBM")"
echo "Restart NetBeans $NETBEANS_MAJOR, then open Window > MariaDB Procedure Debugger."
