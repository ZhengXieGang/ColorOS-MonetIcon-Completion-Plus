#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
BUILD_TOOLS="$SDK/build-tools/36.1.0"
LIBXPOSED_API_VERSION="${LIBXPOSED_API_VERSION:-101.0.1}"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "Missing Android platform: $ANDROID_JAR" >&2
  exit 1
fi

if [ ! -x "$BUILD_TOOLS/aapt2" ] || [ ! -x "$BUILD_TOOLS/d8" ] || [ ! -x "$BUILD_TOOLS/apksigner" ]; then
  echo "Missing Android build tools: $BUILD_TOOLS" >&2
  exit 1
fi

OUT="$ROOT/build"
MAIN_CLASSES="$OUT/main-classes"
RES_FLAT="$OUT/res-flat"
APK_UNSIGNED="$OUT/native-mono-fix-unsigned.apk"
APK_ALIGNED="$OUT/native-mono-fix-aligned.apk"
APK_SIGNED="$OUT/native-mono-fix.apk"
API_CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/oplusmonet"
API_CACHE_AAR="$API_CACHE_DIR/libxposed-api-${LIBXPOSED_API_VERSION}.aar"
LIBS_DIR="$OUT/libs"
LIBXPOSED_AAR="$LIBS_DIR/libxposed-api-${LIBXPOSED_API_VERSION}.aar"
LIBXPOSED_DIR="$LIBS_DIR/libxposed-api"
LIBXPOSED_JAR="$LIBXPOSED_DIR/classes.jar"
SIGNING_DIR="$ROOT/signing"
KEYSTORE="${NATIVE_MONO_FIX_KEYSTORE:-$SIGNING_DIR/native-mono-fix.keystore}"
KEY_ALIAS="${NATIVE_MONO_FIX_KEY_ALIAS:-native-mono-fix}"
KEY_STORE_PASS="${NATIVE_MONO_FIX_STORE_PASS:-android}"
KEY_PASS="${NATIVE_MONO_FIX_KEY_PASS:-android}"
MAIN_JAR="$OUT/main-classes.jar"

rm -rf "$OUT"
mkdir -p "$MAIN_CLASSES" "$RES_FLAT" "$OUT/dex" "$LIBS_DIR" "$LIBXPOSED_DIR" "$SIGNING_DIR" "$API_CACHE_DIR"

if [ ! -f "$API_CACHE_AAR" ]; then
  curl -L -f --retry 5 --retry-all-errors --retry-delay 2 \
    -o "$API_CACHE_AAR.tmp" \
    "https://repo.maven.apache.org/maven2/io/github/libxposed/api/${LIBXPOSED_API_VERSION}/api-${LIBXPOSED_API_VERSION}.aar"
  mv "$API_CACHE_AAR.tmp" "$API_CACHE_AAR"
fi
cp -f "$API_CACHE_AAR" "$LIBXPOSED_AAR"
unzip -q "$LIBXPOSED_AAR" classes.jar -d "$LIBXPOSED_DIR"

find "$ROOT/src/main/java" -name '*.java' | sort > "$OUT/main-sources.txt"
javac --release 17 -cp "$ANDROID_JAR:$LIBXPOSED_JAR" -d "$MAIN_CLASSES" @"$OUT/main-sources.txt"
jar --create --file "$MAIN_JAR" -C "$MAIN_CLASSES" .

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/src/main/res" -o "$RES_FLAT"
"$BUILD_TOOLS/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -o "$APK_UNSIGNED" \
  "$RES_FLAT"/*.flat

"$BUILD_TOOLS/d8" \
  --lib "$ANDROID_JAR" \
  --lib "$LIBXPOSED_JAR" \
  --min-api 33 \
  --output "$OUT/dex" \
  "$MAIN_JAR"

jar --update --file "$APK_UNSIGNED" -C "$OUT/dex" classes.dex
jar --update --file "$APK_UNSIGNED" -C "$ROOT/src/main/resources" META-INF/xposed/java_init.list
jar --update --file "$APK_UNSIGNED" -C "$ROOT/src/main/resources" META-INF/xposed/scope.list
jar --update --file "$APK_UNSIGNED" -C "$ROOT/src/main/resources" META-INF/xposed/module.prop

"$BUILD_TOOLS/zipalign" -p -f 4 "$APK_UNSIGNED" "$APK_ALIGNED"

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass "$KEY_STORE_PASS" \
    -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=ColorOS Native Mono Fix,O=Local,C=CN" >/dev/null 2>&1
fi

"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_STORE_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out "$APK_SIGNED" \
  "$APK_ALIGNED"

"$BUILD_TOOLS/apksigner" verify "$APK_SIGNED"
echo "$APK_SIGNED"
