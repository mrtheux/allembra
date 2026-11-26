#!/usr/bin/env bash
# Gera e assina um APK de teste usando o SDK Android instalado.
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
platform="${DAQUI_PLATFORM:-$sdk/platforms/android-35/android.jar}"
tools="${DAQUI_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
build="$PWD/build/preview"
mkdir -p "$build/java" "$build/classes" "$build/dex"
rm -f "$build/unsigned.apk" "$build/aligned.apk" "$build/Allembra-teste.apk"
"$tools/aapt2" compile --dir app/src/main/res -o "$build/resources.zip"
sed 's/<manifest /<manifest package="br.appdaqui.capsula" /' app/src/main/AndroidManifest.xml > "$build/AndroidManifest.xml"
"$tools/aapt2" link -I "$platform" --manifest "$build/AndroidManifest.xml" \
    --min-sdk-version 28 --target-sdk-version 35 --version-code 3 --version-name 0.3.0 \
    --rename-manifest-package br.appdaqui.capsula --custom-package br.appdaqui.capsula \
    --java "$build/java" -A app/src/main/assets -o "$build/unsigned.apk" "$build/resources.zip"
find app/src/main/java "$build/java" -name '*.java' > "$build/sources"
javac -encoding UTF-8 --release 8 -cp "$platform" -d "$build/classes" @"$build/sources"
find "$build/classes" -name '*.class' > "$build/bytecode"
"$tools/d8" --min-api 28 --lib "$platform" --output "$build/dex" @"$build/bytecode"
(cd "$build/dex" && zip -q "$build/unsigned.apk" classes.dex)
"$tools/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
if [[ ! -f "$build/preview.p12" ]]; then
    keytool -genkeypair -keystore "$build/preview.p12" -storetype PKCS12 -storepass android \
        -keypass android -alias daqui-teste -keyalg RSA -keysize 2048 -validity 3650 \
        -dname 'CN=Allembra Preview, O=Development, C=BR'
fi
"$tools/apksigner" sign --ks "$build/preview.p12" --ks-pass pass:android \
    --out "$build/Allembra-teste.apk" "$build/aligned.apk"
"$tools/apksigner" verify --verbose "$build/Allembra-teste.apk"
