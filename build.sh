#!/bin/sh
# Builds build/haven.apk with plain Android SDK tools (no Gradle).
#
# Needs: JDK 11+ (javac, jar, keytool on PATH or JAVA_HOME), and an Android SDK with
# build-tools and platforms;android-34 installed:
#   ANDROID_HOME=/path/to/sdk sh build.sh
# Optional: BUILD_TOOLS=34.0.0, KEYSTORE=haven.keystore, KS_PASS=... (see below),
# TMDB_KEY=... or TMDB_KEY_FILE=path (a TMDB API key for Top picks; JustWatch is used without one).
# Works on Linux, macOS and Windows (Git Bash).
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] && [ -d "$SDK" ] || { echo "Set ANDROID_HOME to your Android SDK folder." >&2; exit 1; }
BT="$SDK/build-tools/${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}"
JAR="$SDK/platforms/android-34/android.jar"
[ -f "$JAR" ] || { echo "Missing $JAR: install platforms;android-34 with sdkmanager." >&2; exit 1; }
if [ -n "$JAVA_HOME" ]; then
  # Git Bash: PATH needs /c/... paths, while the SDK's .bat tools need JAVA_HOME as C:\...
  if command -v cygpath >/dev/null 2>&1; then
    PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"; export JAVA_HOME="$(cygpath -w "$JAVA_HOME")"
  else
    PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

# Windows build-tools ship d8/apksigner as .bat and zipalign as .exe.
tool() { for t in "$BT/$1" "$BT/$1.bat" "$BT/$1.exe"; do [ -f "$t" ] && { echo "$t"; return; }; done; echo "$BT/$1"; }

rm -rf build && mkdir -p build/gen/home/haven/tv build/classes build/dex
# Optional TMDB API key for Top picks (TMDB_KEY, or a file named by TMDB_KEY_FILE). Without one,
# Haven uses JustWatch. The key is only written into build/, never into the source.
[ -z "${TMDB_KEY:-}" ] && [ -n "${TMDB_KEY_FILE:-}" ] && [ -f "$TMDB_KEY_FILE" ] && TMDB_KEY="$(tr -d ' \r\n' < "$TMDB_KEY_FILE")"
printf 'package home.haven.tv;\n\nfinal class BuildKey {\n    static final String TMDB = "%s";\n\n    private BuildKey() { }\n}\n' \
    "$(printf '%s' "${TMDB_KEY:-}" | tr -cd 'A-Za-z0-9._-')" > build/gen/home/haven/tv/BuildKey.java
"$(tool aapt2)" compile --dir res -o build/res.zip
"$(tool aapt2)" link -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 34 --java build/gen -o build/base.apk build/res.zip
# Projectivy's wallpaper plugin interface (aidl/), compiled to Java next to R.java.
"$(tool aidl)" -I aidl -o build/gen aidl/tv/projectivy/plugin/wallpaperprovider/api/IWallpaperProviderService.aidl
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR" -encoding UTF-8 \
    -d build/classes $(find build/gen src -name '*.java')
"$(tool d8)" --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && jar -uMf ../unsigned.apk classes.dex)
# Assets go in via jar: aapt2 -A on Windows writes backslashes into the zip paths.
jar -uM0f build/unsigned.apk assets
"$(tool zipalign)" -p -f 4 build/unsigned.apk build/aligned.apk

# Your own signing key, made on first build. Keep it: Android only installs updates signed with
# the same key, and a reinstall loses the adb-granted permissions. Never commit it.
KEYSTORE="${KEYSTORE:-haven.keystore}"
KS_PASS="${KS_PASS:-haven-local}"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
      -alias haven -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Haven" >/dev/null 2>&1
fi
"$(tool apksigner)" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --out build/haven.apk build/aligned.apk
ls -la build/haven.apk
