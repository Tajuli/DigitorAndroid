#!/usr/bin/env sh
set -eu

GRADLE_VERSION="9.5.0"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BOOTSTRAP_DIR="$SCRIPT_DIR/.gradle-bootstrap"
GRADLE_HOME="$BOOTSTRAP_DIR/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  echo "Gradle $GRADLE_VERSION not found. Downloading..."
  mkdir -p "$BOOTSTRAP_DIR"
  ZIP="$BOOTSTRAP_DIR/gradle-$GRADLE_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  else
    echo "curl or wget is required to bootstrap Gradle." >&2
    exit 1
  fi
  unzip -q -o "$ZIP" -d "$BOOTSTRAP_DIR"
  rm -f "$ZIP"
  chmod +x "$GRADLE_BIN"
fi

exec "$GRADLE_BIN" "$@"
