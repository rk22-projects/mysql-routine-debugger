#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_TOOL_INSTALL=0
SKIP_EXTENSION_INSTALL=0

for arg in "$@"; do
    case "$arg" in
        --skip-tool-install) SKIP_TOOL_INSTALL=1 ;;
        --skip-extension-install) SKIP_EXTENSION_INSTALL=1 ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
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

ensure_java() {
    local major=""
    if command -v java >/dev/null 2>&1; then major="$(java_major)"; fi
    if [[ -z "$major" || "$major" -lt 17 ]]; then
        [[ $SKIP_TOOL_INSTALL -eq 0 ]] || { echo "Java 17+ is required." >&2; exit 1; }
        if command -v apt-get >/dev/null 2>&1; then install_packages openjdk-17-jdk curl
        elif command -v pacman >/dev/null 2>&1; then install_packages jdk17-openjdk curl
        else install_packages java-17-openjdk-devel curl
        fi
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

ensure_node() {
    local major=0
    if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
        major="$(node -p 'process.versions.node.split(".")[0]')"
    fi
    [[ "$major" -ge 20 ]] && return
    [[ $SKIP_TOOL_INSTALL -eq 0 ]] || { echo "Node.js 20+ and npm are required." >&2; exit 1; }

    command -v curl >/dev/null 2>&1 || install_packages curl
    if ! command -v xz >/dev/null 2>&1; then
        if command -v apt-get >/dev/null 2>&1; then install_packages xz-utils
        else install_packages xz
        fi
    fi

    local machine node_arch base sums archive checksum tools node_home
    machine="$(uname -m)"
    case "$machine" in
        x86_64|amd64) node_arch=x64 ;;
        aarch64|arm64) node_arch=arm64 ;;
        armv7l) node_arch=armv7l ;;
        *) echo "Unsupported CPU architecture for automatic Node.js installation: $machine" >&2; exit 1 ;;
    esac
    tools="$REPO_ROOT/.tools"
    node_home="$tools/node-20"
    base="https://nodejs.org/dist/latest-v20.x"
    mkdir -p "$tools"
    sums="$tools/node-20-SHASUMS256.txt"
    run curl -fL "$base/SHASUMS256.txt" -o "$sums"
    archive="$(awk -v arch="linux-$node_arch.tar.xz" '$2 ~ arch"$" { print $2; exit }' "$sums")"
    [[ -n "$archive" ]] || { echo "Could not find a Node.js 20 package for $machine." >&2; exit 1; }
    checksum="$(awk -v file="$archive" '$2 == file { print $1; exit }' "$sums")"
    run curl -fL "$base/$archive" -o "$tools/$archive"
    printf '%s  %s\n' "$checksum" "$tools/$archive" | sha256sum -c -
    rm -rf -- "$node_home"
    mkdir -p "$node_home"
    run tar -xJf "$tools/$archive" --strip-components=1 -C "$node_home"
    export PATH="$node_home/bin:$PATH"
    echo "Using Node.js $(node --version) from $node_home"
}

ensure_code() {
    command -v code >/dev/null 2>&1 && return
    echo "Visual Studio Code is a prerequisite and was not found. Install it locally or use --skip-extension-install to build only." >&2
    exit 1
}

reset_release() {
    local path="$1"
    [[ "$path" == "$REPO_ROOT"/* ]] || { echo "Unsafe output path: $path" >&2; exit 1; }
    rm -rf -- "$path"
    mkdir -p "$path"
}

[[ $SKIP_EXTENSION_INSTALL -eq 1 ]] || ensure_code
ensure_java
ensure_node
echo "Building the shared core and VS Code bridge..."
run bash "$REPO_ROOT/mvnw" -pl vscode -am clean package
echo "Packaging the VS Code extension..."
(cd "$REPO_ROOT/vscode" && run npm run package)

VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$REPO_ROOT/vscode/package.json" | head -n 1)"
VSIX="$REPO_ROOT/vscode/mariadb-procedure-debugger-$VERSION.vsix"
[[ -f "$VSIX" ]] || { echo "VSIX was not produced: $VSIX" >&2; exit 1; }
RELEASE="$REPO_ROOT/release/vscode"
reset_release "$RELEASE"
cp "$VSIX" "$RELEASE/"

if [[ $SKIP_EXTENSION_INSTALL -eq 0 ]]; then
    run code --install-extension "$VSIX" --force
fi

echo "VS Code extension package: $RELEASE/$(basename "$VSIX")"
[[ $SKIP_EXTENSION_INSTALL -eq 1 ]] || echo "Reload Visual Studio Code before opening MariaDB Procedure Debugger."
