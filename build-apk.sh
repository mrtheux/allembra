#!/usr/bin/env bash
# Gera um APK de teste ou um AAB sem assinatura usando o SDK Android instalado.
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
tools="${DAQUI_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
build="$PWD/build/preview"
source_dir="app/src/main"
package="br.appdaqui.capsula"
version_code=3
version_name=0.3.0
target_sdk=35
build_mode="${1:-}"
if [[ "$build_mode" == "--design-preview" || "$build_mode" == "--play-store" ]]; then
    build="$PWD/build/design-preview"
    source_dir="design-preview/src/main"
    package="br.appdaqui.designreview.r3"
    version_code=1
    version_name=0.7.0-preview
    target_sdk=36
fi
resource_package="$package"
bundle_name=Allembra-rascunho.aab
if [[ "$build_mode" == "--play-store" ]]; then
    build="$PWD/build/play-store"
    package="br.institutocogita.allembra"
    version_name=0.7.0
    bundle_name=Allembra-sem-assinatura.aab
fi
platform="${DAQUI_PLATFORM:-$sdk/platforms/android-$target_sdk/android.jar}"
mkdir -p "$build/java" "$build/classes" "$build/dex"
rm -f "$build/unsigned.apk" "$build/aligned.apk" "$build/Allembra-teste.apk"
"$tools/aapt2" compile --dir "$source_dir/res" -o "$build/resources.zip"
sed "/package=/!s/<manifest /<manifest package=\"$package\" /" "$source_dir/AndroidManifest.xml" > "$build/AndroidManifest.xml"
if [[ "$build_mode" == "--play-store" ]]; then
    sed -i -e "s/br\.appdaqui\.designreview\.r3/$package/g" \
        -e 's/Allembra Teste 0\.7/Allembra/g' "$build/AndroidManifest.xml"
fi
"$tools/aapt2" link -I "$platform" --manifest "$build/AndroidManifest.xml" \
    --min-sdk-version 28 --target-sdk-version "$target_sdk" --version-code "$version_code" --version-name "$version_name" \
    --rename-manifest-package "$package" --custom-package "$resource_package" \
    --java "$build/java" -A "$source_dir/assets" -o "$build/unsigned.apk" "$build/resources.zip"
find "$source_dir/java" "$build/java" -name '*.java' > "$build/sources"
javac -encoding UTF-8 --release 8 -cp "$platform" -d "$build/classes" @"$build/sources"
find "$build/classes" -name '*.class' > "$build/bytecode"
"$tools/d8" --min-api 28 --lib "$platform" --output "$build/dex" @"$build/bytecode"
(cd "$build/dex" && zip -q "$build/unsigned.apk" classes.dex)
if [[ "$build_mode" != "--play-store" ]]; then
    "$tools/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
    if [[ ! -f "$build/preview.p12" ]]; then
        keytool -genkeypair -keystore "$build/preview.p12" -storetype PKCS12 -storepass android \
            -keypass android -alias daqui-teste -keyalg RSA -keysize 2048 -validity 3650 \
            -dname 'CN=Allembra Preview, O=Development, C=BR'
    fi
    "$tools/apksigner" sign --ks "$build/preview.p12" --ks-pass pass:android \
        --out "$build/Allembra-teste.apk" "$build/aligned.apk"
    "$tools/apksigner" verify --verbose "$build/Allembra-teste.apk"
fi

if [[ "$build_mode" == "--play-store" || "${2:-}" == "--bundle" ]]; then
    : "${BUNDLETOOL_JAR:?Aponte para o JAR do bundletool}"
    "$tools/aapt2" convert --output-format proto -o "$build/proto.apk" "$build/unsigned.apk"
    python3 - "$build" <<'PY_BUNDLE'
import sys
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

directory = Path(sys.argv[1])
with ZipFile(directory / 'proto.apk') as apk, ZipFile(directory / 'base.zip', 'w', ZIP_DEFLATED) as module:
    for item in apk.namelist():
        prefix = 'manifest/' if item == 'AndroidManifest.xml' else 'dex/' if item == 'classes.dex' else ''
        if item in {'AndroidManifest.xml', 'classes.dex', 'resources.pb'} or item.startswith(('res/', 'assets/')):
            module.writestr(prefix + item, apk.read(item))
PY_BUNDLE
    rm -f "$build/$bundle_name"
    java -jar "$BUNDLETOOL_JAR" build-bundle --modules="$build/base.zip" --output="$build/$bundle_name"
    java -jar "$BUNDLETOOL_JAR" validate --bundle="$build/$bundle_name"
fi
