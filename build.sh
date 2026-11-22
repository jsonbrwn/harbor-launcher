#!/usr/bin/env bash
# Builds Harbor without Gradle, using the Debian/Ubuntu Android SDK packages:
#   apt-get install android-sdk-platform-23 android-sdk-build-tools dalvik-exchange apksigner zipalign
# The APK targets API 29 but compiles against the API 23 platform jar, which covers every API used.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
JAR="$SDK/platforms/android-23/android.jar"
BT="$SDK/build-tools/debian"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"

"$BT/aapt" package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -A assets -0 jpg -I "$JAR" -F "$OUT/harbor.unaligned.apk"
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -classpath "$JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java') 2>&1 | grep -v 'bootstrap class path\|options were found\|To suppress' || true
test -f "$OUT/classes/dev/harbor/launcher/MainActivity.class"
"$BT/dx" --dex --min-sdk-version=21 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && "$BT/aapt" add harbor.unaligned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/harbor.unaligned.apk" "$OUT/harbor.aligned.apk"

KS=${HARBOR_KEYSTORE:-keystore/harbor.jks}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass harbor -keypass harbor -alias harbor \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Harbor Launcher" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:harbor --key-pass pass:harbor --ks-key-alias harbor \
  --out "$OUT/Harbor.apk" "$OUT/harbor.aligned.apk"
apksigner verify "$OUT/Harbor.apk"
echo "Built $OUT/Harbor.apk"
