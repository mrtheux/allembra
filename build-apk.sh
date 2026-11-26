#!/usr/bin/env bash
# Gera e assina um APK de teste usando o SDK Android instalado.
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
platform="${DAQUI_PLATFORM:-$sdk/platforms/android-35/android.jar}"
tools="${DAQUI_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
build="$PWD/build/preview"
source_dir="app/src/main"
package="br.appdaqui.capsula"
version_code=3
version_name=0.3.0
if [[ "${1:-}" == "--design-preview" ]]; then
    build="$PWD/build/design-preview"
    source_dir="design-preview/src/main"
    package="br.appdaqui.designpreview"
    version_code=2
    version_name=0.4.1-preview
fi
mkdir -p "$build/java" "$build/classes" "$build/dex"
rm -f "$build/unsigned.apk" "$build/aligned.apk" "$build/Allembra-teste.apk"
"$tools/aapt2" compile --dir "$source_dir/res" -o "$build/resources.zip"
sed "/package=/!s/<manifest /<manifest package=\"$package\" /" "$source_dir/AndroidManifest.xml" > "$build/AndroidManifest.xml"
"$tools/aapt2" link -I "$platform" --manifest "$build/AndroidManifest.xml" \
    --min-sdk-version 28 --target-sdk-version 35 --version-code "$version_code" --version-name "$version_name" \
    --rename-manifest-package "$package" --custom-package "$package" \
    --java "$build/java" -A "$source_dir/assets" -o "$build/unsigned.apk" "$build/resources.zip"
find "$source_dir/java" "$build/java" -name '*.java' > "$build/sources"
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
