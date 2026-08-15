#!/usr/bin/env sh
set -eu

GRADLE_VERSION="8.11.1"
GRADLE_SHA256="f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
BOOTSTRAP_DIR="$GRADLE_USER_HOME/kaw-bootstrap/gradle-$GRADLE_VERSION"
ZIP_PATH="$GRADLE_USER_HOME/kaw-bootstrap/gradle-$GRADLE_VERSION-bin.zip"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo "A SHA-256 utility is required (sha256sum or shasum)." >&2
    exit 1
  fi
}

if [ ! -x "$BOOTSTRAP_DIR/bin/gradle" ]; then
  mkdir -p "$(dirname "$ZIP_PATH")"
  if [ ! -f "$ZIP_PATH" ]; then
    URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    if command -v curl >/dev/null 2>&1; then
      curl --fail --location --proto '=https' --tlsv1.2 "$URL" --output "$ZIP_PATH"
    elif command -v wget >/dev/null 2>&1; then
      wget --https-only "$URL" -O "$ZIP_PATH"
    else
      echo "curl or wget is required to bootstrap Gradle." >&2
      exit 1
    fi
  fi
  ACTUAL_SHA="$(sha256_file "$ZIP_PATH")"
  if [ "$ACTUAL_SHA" != "$GRADLE_SHA256" ]; then
    echo "Gradle distribution checksum mismatch." >&2
    rm -f "$ZIP_PATH"
    exit 1
  fi
  rm -rf "$BOOTSTRAP_DIR"
  mkdir -p "$(dirname "$BOOTSTRAP_DIR")"
  unzip -q "$ZIP_PATH" -d "$(dirname "$BOOTSTRAP_DIR")"
fi

exec "$BOOTSTRAP_DIR/bin/gradle" "$@"
