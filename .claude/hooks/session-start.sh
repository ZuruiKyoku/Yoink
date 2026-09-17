#!/bin/bash
# Ensures the Android SDK is present so ./gradlew works in a Claude Code on the
# web / cloud session. Checks in order: an already-configured $ANDROID_HOME, a
# previous install cached at $HOME/android-sdk, and only then downloads it.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

COMPILE_SDK="35"
BUILD_TOOLS_VERSION="35.0.0"
CMDLINE_TOOLS_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

sdk_is_ready() {
  # $1 = candidate ANDROID_HOME
  [ -d "$1/platforms/android-$COMPILE_SDK" ] && [ -d "$1/cmdline-tools/latest/bin" ]
}

if [ -n "${ANDROID_HOME:-}" ] && sdk_is_ready "$ANDROID_HOME"; then
  echo "Android SDK already configured at \$ANDROID_HOME ($ANDROID_HOME) — nothing to do."
  ANDROID_SDK_DIR="$ANDROID_HOME"
else
  ANDROID_SDK_DIR="$HOME/android-sdk"

  if sdk_is_ready "$ANDROID_SDK_DIR"; then
    echo "Android SDK already installed at $ANDROID_SDK_DIR (from a cached prior session)."
  else
    echo "Android SDK not found — installing cmdline-tools, platform-tools, platforms;android-$COMPILE_SDK, build-tools;$BUILD_TOOLS_VERSION into $ANDROID_SDK_DIR"
    mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"

    TMP_ZIP="$(mktemp)"
    curl -fsSL "$CMDLINE_TOOLS_ZIP_URL" -o "$TMP_ZIP"
    unzip -q "$TMP_ZIP" -d "$ANDROID_SDK_DIR/cmdline-tools"
    rm -f "$TMP_ZIP"
    # The zip's top-level folder is itself named "cmdline-tools"; sdkmanager
    # requires the nested layout cmdline-tools/latest/bin/sdkmanager.
    rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest"
    mv "$ANDROID_SDK_DIR/cmdline-tools/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"

    SDKMANAGER="$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
    yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_DIR" --licenses >/dev/null 2>&1 || true
    "$SDKMANAGER" --sdk_root="$ANDROID_SDK_DIR" \
      "platform-tools" \
      "platforms;android-$COMPILE_SDK" \
      "build-tools;$BUILD_TOOLS_VERSION"
  fi
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$ANDROID_SDK_DIR\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_SDK_DIR\""
    echo "export PATH=\"\$PATH:$ANDROID_SDK_DIR/cmdline-tools/latest/bin:$ANDROID_SDK_DIR/platform-tools\""
  } >> "$CLAUDE_ENV_FILE"
fi

echo "ANDROID_HOME set to $ANDROID_SDK_DIR"
