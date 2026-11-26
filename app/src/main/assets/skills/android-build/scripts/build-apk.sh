#!/bin/sh
# Android APK build: Gradle route when the project has a Gradle build file,
# otherwise the Gradle-less aapt2 + javac + d8 + apksigner route.
# Usage: sh build-apk.sh [project_dir]
#
# Project layout (script route):
#   AndroidManifest.xml     package="..." is read from here
#   src/**/*.java           sources (Kotlin needs the Gradle route)
#   res/  assets/  libs/*.jar   all optional
#
# Environment (all optional):
#   ROUTE         auto (default), gradle or script
#   GRADLE_TASK   Gradle task when ROUTE=gradle (default assembleDebug)
#   GRADLE_AUTORUN  when 1, run the Gradle task instead of only printing it
#   ANDROID_JAR   path to android.jar (default: newest under $HOME/android-sdk/platforms)
#   MIN_SDK       default 26        TARGET_SDK  default 35
#   VERSION_CODE  default 1         VERSION_NAME default 1.0
#   KEYSTORE      default $HOME/.android/debug.keystore (created when missing)
#   KS_ALIAS      default androiddebugkey
#   KS_PASS       default android; read by apksigner through env:, never on a command line
#   STRICT_SDK    when 1, a manifest targetSdk different from TARGET_SDK fails
#   NO_CACHE      when 1, ignore a valid incremental cache
set -eu

PROJECT=${1:-.}
cd "$PROJECT"

ROUTE=${ROUTE:-auto}
GRADLE_TASK=${GRADLE_TASK:-assembleDebug}
MIN_SDK=${MIN_SDK:-26}
TARGET_SDK=${TARGET_SDK:-35}
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KS_ALIAS=${KS_ALIAS:-androiddebugkey}
KS_PASS=${KS_PASS:-android}
export KS_PASS

fail() { echo "build-apk: $* " >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "$1 is not installed. Run android_sdk_install first."; }

# ---------------------------------------------------------------- route ----
HAS_GRADLE=no
[ -f build.gradle.kts ] || [ -f build.gradle ] && HAS_GRADLE=yes
if [ "$ROUTE" = auto ]; then
    if [ "$HAS_GRADLE" = yes ]; then ROUTE=gradle; else ROUTE=script; fi
fi
[ "$ROUTE" = gradle ] || [ "$ROUTE" = script ] || fail "ROUTE must be auto, gradle or script"

if [ "$ROUTE" = gradle ]; then
    [ -f gradlew ] || fail "Gradle project has no gradlew. Generate the wrapper first or use ROUTE=script."
    echo "ROUTE: gradle"
    if [ "${GRADLE_AUTORUN:-0}" != 1 ]; then
        echo "GRADLE_COMMAND: sh ./gradlew $GRADLE_TASK"
        echo "Build report: the Gradle route writes its own outputs under build/."
        exit 0
    fi
    GRADLE_AUTORUN=1
fi

need aapt2
need javac
need jar
need d8
need apksigner
need keytool

[ -f AndroidManifest.xml ] || fail "AndroidManifest.xml not found in $(pwd)"

if [ -z "${ANDROID_JAR:-}" ]; then
    ANDROID_JAR=$(ls -d "$HOME"/android-sdk/platforms/android-*/android.jar 2>/dev/null | sort | tail -n 1 || true)
fi
[ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ] || fail "android.jar not found. Run android_sdk_install, or set ANDROID_JAR."

PACKAGE=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -n 1)
[ -n "$PACKAGE" ] || fail "AndroidManifest.xml has no package attribute"

# ------------------------------------------------------------- preflight ---
MANIFEST_TARGET=$(sed -n 's/.*targetSdkVersion="\([0-9]*\)".*/\1/p' AndroidManifest.xml | head -n 1 || true)
if [ -n "$MANIFEST_TARGET" ] && [ "$MANIFEST_TARGET" != "$TARGET_SDK" ]; then
    echo "build-apk: warning: manifest targetSdkVersion=$MANIFEST_TARGET differs from TARGET_SDK=$TARGET_SDK" >&2
    [ "${STRICT_SDK:-0}" != 1 ] || fail "targetSdk mismatch (STRICT_SDK=1)"
fi

mkdir -p build

# ----------------------------------------------------------- incremental ---
INPUT_LIST=build/inputs.txt
{
    echo "$PACKAGE $MIN_SDK $TARGET_SDK $VERSION_CODE $VERSION_NAME ${KS_ALIAS} ${KEYSTORE}"
    echo "jar:${ANDROID_JAR}"
    [ -f AndroidManifest.xml ] && echo "f:AndroidManifest.xml"
    find src res assets libs -type f 2>/dev/null | sort | while IFS= read -r f; do echo "f:$f"; done
} > "$INPUT_LIST"

if command -v sha256sum >/dev/null 2>&1; then
    HASH=$(cat "$INPUT_LIST" | sha256sum | cut -d' ' -f1)
elif command -v cksum >/dev/null 2>&1; then
    HASH=$(cat "$INPUT_LIST" | cksum | cut -d' ' -f1)
else
    HASH=none
fi
OUT="build/$PACKAGE.apk"
REPORT=build/build-report.json
INCREMENTAL=false
if [ "${NO_CACHE:-0}" != 1 ] && [ -f build/input.hash ] && [ -f "$OUT" ] && [ -f "$REPORT" ]; then
    if [ "$(cat build/input.hash)" = "$HASH" ]; then
        INCREMENTAL=true
        echo "INCREMENTAL: true"
        cat "$REPORT"
        echo "BUILD_REPORT: $(pwd)/$REPORT"
        echo "APK: $(pwd)/$OUT"
        echo "Package: $PACKAGE"
        exit 0
    fi
fi
echo "$HASH" > build/input.hash
echo "INCREMENTAL: $INCREMENTAL"

# Fresh outputs; keep the previous report until this build succeeds.
rm -rf build/gen build/classes build/dex
rm -f build/res.zip build/sources.txt build/base.apk build/unsigned.apk build/classes.jar "$OUT" build/build-error.log
mkdir -p build/classes build/dex build/gen

CHECKS=""
step() {
    label=$1
    cmd=$2
    printf '\n== %s ==\n' "$label" >> build/build.log
    if ! sh -c "$cmd" >>build/build.log 2>>build/build-error.log; then
        echo "build-apk: $label failed" >&2
        if [ -f build/build-error.log ]; then
            grep -E 'error:|Error:|FAILED' build/build-error.log | tail -n 3 >&2 || \
                tail -n 5 build/build-error.log >&2
        fi
        exit 1
    fi
    CHECKS="$CHECKS $label"
}

LINK_INPUTS=""
if [ -d res ] && [ -n "$(ls -A res 2>/dev/null)" ]; then
    step aapt2-compile "aapt2 compile --dir res -o build/res.zip"
    LINK_INPUTS="build/res.zip"
fi
ASSET_ARGS=""
[ -d assets ] && ASSET_ARGS="-A assets"

step aapt2-link "aapt2 link -I '$ANDROID_JAR' --manifest AndroidManifest.xml $ASSET_ARGS --java build/gen --min-sdk-version '$MIN_SDK' --target-sdk-version '$TARGET_SDK' --version-code '$VERSION_CODE' --version-name '$VERSION_NAME' -o build/base.apk $LINK_INPUTS"

CLASSPATH_JARS="$ANDROID_JAR"
LIB_JARS=""
for lib in libs/*.jar; do
    [ -f "$lib" ] || continue
    CLASSPATH_JARS="$CLASSPATH_JARS:$lib"
    LIB_JARS="$LIB_JARS '$lib'"
done

find src build/gen -name '*.java' > build/sources.txt
[ -s build/sources.txt ] || fail "no .java sources under src/"
step javac "javac -source 8 -target 8 -bootclasspath '$CLASSPATH_JARS' -Xlint:-options -d build/classes @build/sources.txt"
step jar "jar cf build/classes.jar -C build/classes ."
step d8 "d8 --min-api '$MIN_SDK' --lib '$ANDROID_JAR' --output build/dex build/classes.jar $LIB_JARS"

# Adding entries to the end keeps resources.arsc stored and 4-byte aligned,
# which Android 11+ requires when targetSdk is 30 or higher.
cp build/base.apk build/unsigned.apk
for dex in build/dex/classes*.dex; do
    [ -f "$dex" ] || continue
    jar --update --file build/unsigned.apk -C build/dex "$(basename "$dex")"
done

if [ ! -f "$KEYSTORE" ]; then
    [ "$KEYSTORE" = "$HOME/.android/debug.keystore" ] || fail "keystore $KEYSTORE does not exist"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >>build/build.log 2>&1 || fail "keytool could not create the debug keystore"
fi

step apksigner-sign "apksigner sign --ks '$KEYSTORE' --ks-key-alias '$KS_ALIAS' --ks-pass env:KS_PASS --key-pass env:KS_PASS --out '$OUT' build/unsigned.apk"
step apksigner-verify "apksigner verify '$OUT'"

APK_SIZE=$(wc -c < "$OUT" | tr -d ' ')
if command -v sha256sum >/dev/null 2>&1; then
    APK_SHA=$(sha256sum "$OUT" | cut -d' ' -f1)
elif command -v shasum >/dev/null 2>&1; then
    APK_SHA=$(shasum -a 256 "$OUT" | cut -d' ' -f1)
else
    APK_SHA=$(cksum "$OUT" | cut -d' ' -f1)
fi
BUILT_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)
KS_NAME=$(basename "$KEYSTORE")
CHECKS_JSON=$(echo "$CHECKS" | tr ' ' '\n' | sed '/^$/d' | sed 's/^/"/;s/$/"/' | paste -sd, -)

cat > "$REPORT" <<EOF
{"route":"script","package":"$PACKAGE","apk":"$OUT","size_bytes":$APK_SIZE,"sha256":"$APK_SHA","min_sdk":$MIN_SDK,"target_sdk":$TARGET_SDK,"version_code":$VERSION_CODE,"version_name":"$VERSION_NAME","keystore":"$KS_NAME","built_at":"$BUILT_AT","incremental":$INCREMENTAL,"checks":[$CHECKS_JSON]}
EOF

echo "ROUTE: script"
echo "BUILD_REPORT: $(pwd)/$REPORT"
echo "APK: $(pwd)/$OUT"
echo "Package: $PACKAGE"
echo "SHA256: $APK_SHA"
