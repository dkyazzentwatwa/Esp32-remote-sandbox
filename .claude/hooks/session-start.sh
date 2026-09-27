#!/bin/bash
# Prepares Claude Code on the web sessions to build, lint and test EspSketchIDE.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

SDK="$HOME/android-sdk"
APP_DIR="$CLAUDE_PROJECT_DIR/EspSketchIDE"
CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -sSfL -o "$tmp/clt.zip" "$CLT_URL"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

# Idempotent: sdkmanager skips packages that are already installed.
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "platform-tools" "platforms;android-36" "platforms;android-37.0" "build-tools;36.0.0" >/dev/null

echo "sdk.dir=$SDK" > "$APP_DIR/local.properties"
echo "export ANDROID_HOME=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
echo "export ANDROID_SDK_ROOT=\"$SDK\"" >> "$CLAUDE_ENV_FILE"

# Maven Central often answers 429 Too Many Requests from cloud sessions. Try Google's
# mirror of Maven Central first; repositories added in beforeSettings precede the
# ones declared in settings.gradle.kts, so the project's own files stay unchanged.
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$GRADLE_HOME_DIR/init.d"
cat > "$GRADLE_HOME_DIR/init.d/maven-central-mirror.gradle" <<'GRADLE'
def mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
beforeSettings { settings ->
    settings.pluginManagement.repositories { maven { url mirror } }
    settings.dependencyResolutionManagement.repositories { maven { url mirror } }
}
GRADLE

# Warm the Gradle wrapper and dependency caches; Gradle keeps partial progress, so retry.
cd "$APP_DIR"
for attempt in 1 2 3 4; do
  if ./gradlew --no-daemon -q :app:compileDebugKotlin :app:compileDebugUnitTestKotlin >/dev/null 2>&1; then
    exit 0
  fi
  echo "Gradle warm-up attempt $attempt failed; retrying" >&2
  sleep $((attempt * 5))
done
echo "Gradle warm-up failed; builds will fetch dependencies on demand" >&2
exit 0
