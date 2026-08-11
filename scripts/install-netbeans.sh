#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_TOOL_INSTALL=0
NETBEANS_HOME="${NETBEANS_HOME:-}"
NETBEANS_USER_DIR=""
NETBEANS_DOWNLOAD_VERSION="27"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-tool-install) SKIP_TOOL_INSTALL=1; shift ;;
        --netbeans-home) NETBEANS_HOME="$2"; shift 2 ;;
        --netbeans-user-dir) NETBEANS_USER_DIR="$2"; shift 2 ;;
        --netbeans-download-version) NETBEANS_DOWNLOAD_VERSION="$2"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done
[[ "$NETBEANS_DOWNLOAD_VERSION" =~ ^[0-9]+([.][0-9]+)?$ ]] || { echo "Invalid NetBeans download version." >&2; exit 2; }

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
    if ! command -v unzip >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
        [[ $SKIP_TOOL_INSTALL -eq 0 ]] || { echo "curl and unzip are required." >&2; exit 1; }
        install_packages curl unzip
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
    [[ -n "$home" && -x "$home/bin/netbeans" && -f "$home/ide/modules/org-netbeans-modules-db.jar" && -n "$(netbeans_major "$home")" ]]
}

reset_repo_directory() {
    local path="$1"
    [[ "$path" == "$REPO_ROOT"/* ]] || { echo "Unsafe repository path: $path" >&2; exit 1; }
    rm -rf -- "$path"
    mkdir -p "$path"
}

ensure_tools

if ! valid_netbeans_home "$NETBEANS_HOME"; then
    candidates=("/opt/netbeans" "/usr/local/netbeans" "$REPO_ROOT/.tools/netbeans-$NETBEANS_DOWNLOAD_VERSION/netbeans")
    if command -v netbeans >/dev/null 2>&1; then candidates+=("$(dirname "$(dirname "$(readlink -f "$(command -v netbeans)")")")"); fi
    for candidate in "${candidates[@]}"; do
        if valid_netbeans_home "$candidate"; then NETBEANS_HOME="$candidate"; break; fi
    done
fi

if ! valid_netbeans_home "$NETBEANS_HOME" && [[ $SKIP_TOOL_INSTALL -eq 0 ]]; then
    TOOLS="$REPO_ROOT/.tools"
    mkdir -p "$TOOLS"
    ZIP="$TOOLS/netbeans-$NETBEANS_DOWNLOAD_VERSION-bin.zip"
    INSTALL_ROOT="$TOOLS/netbeans-$NETBEANS_DOWNLOAD_VERSION"
    BASE_URL="https://archive.apache.org/dist/netbeans/netbeans/$NETBEANS_DOWNLOAD_VERSION/netbeans-$NETBEANS_DOWNLOAD_VERSION-bin.zip"
    echo "Downloading Apache NetBeans $NETBEANS_DOWNLOAD_VERSION..."
    run curl -fL "$BASE_URL" -o "$ZIP"
    run curl -fL "$BASE_URL.sha512" -o "$ZIP.sha512"
    EXPECTED="$(awk '{print toupper($1); exit}' "$ZIP.sha512")"
    ACTUAL="$(sha512sum "$ZIP" | awk '{print toupper($1)}')"
    [[ "$ACTUAL" == "$EXPECTED" ]] || { echo "Apache NetBeans checksum verification failed." >&2; exit 1; }
    reset_repo_directory "$INSTALL_ROOT"
    run unzip -q "$ZIP" -d "$INSTALL_ROOT"
    NETBEANS_HOME="$INSTALL_ROOT/netbeans"
fi

valid_netbeans_home "$NETBEANS_HOME" || { echo "Apache NetBeans was not found. Pass --netbeans-home or omit --skip-tool-install." >&2; exit 1; }
NETBEANS_MAJOR="$(netbeans_major "$NETBEANS_HOME")"
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
