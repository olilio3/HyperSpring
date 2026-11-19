#!/usr/bin/env bash
# Compila los dos APK SIN Android SDK y SIN las herramientas de GitHub de local-build/build.sh
# (jadx, uber-apk-signer), solo con cosas de Maven Central. Sirve donde GitHub releases está
# bloqueado (p. ej. las sesiones de Claude Code en la nube).
#
# Herramientas (en $TOOLS, por defecto ~/tools):
#   android.jar           API 35: https://raw.githubusercontent.com/Sable/android-platforms/master/android-35/android.jar
#   dalvik-dx-16.0.1.jar  DEX:    https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/
#   apktool-lib-3.0.3.jar aapt2:  https://repo1.maven.org/maven2/org/apktool/apktool-lib/3.0.3/ (lleva prebuilt/linux/aapt2)
#   apksig-2.3.0.jar      firma:  https://repo1.maven.org/maven2/com/android/tools/build/apksig/2.3.0/
#   debug.keystore        la clave de depuración de uber-apk-signer (la de las releases):
#                         https://raw.githubusercontent.com/patrickfav/uber-apk-signer/main/src/main/resources/debug.keystore
#                         (alias androiddebugkey, contraseña android; huella SHA-256 1E:08:A9:03:…:59:53)
#
# Diferencias con build.sh: DEX con dx (más antiguo que D8; el DEX sale ~6 % más grande), firma
# solo v2 (con minSdk 30 la v1 no hace falta, y la apksig de Maven no firma v1 con JDK nuevos) y
# alineado con zipalign.py. Para publicar releases, mejor build.sh o el CI.
#
# Uso: TOOLS=/ruta bash local-build/maven/build.sh   → apk/HyperSpring.apk y apk/HyperSpring-legacy.apk
#
# Opcional:
#   KEYSTORE=/ruta/hyperspring.jks  firma con tu clave de releases (pide la contraseña; alias en
#                                   KS_ALIAS, por defecto hyperspring). Sin él, clave de depuración.
#   AAPT2=/ruta/aapt2               aapt2 propio. Si no, el del PATH (Termux: pkg install aapt2) y,
#                                   si no hay, el de apktool-lib (solo ordenadores x86_64).
#
# En Termux (Android): pkg install openjdk-17 aapt2 python zip unzip curl git
set -euo pipefail
cd "$(dirname "$0")/../.."
TOOLS="${TOOLS:-$HOME/tools}"
HERE=local-build/maven
O="${OUT:-local-build/out-maven}"
X="--add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED"
VN=$(sed -n 's/.*NAME = "\(.*\)";/\1/p' app/src/main/java/com/icehome/up/Version.java)
VC=$(sed -n 's/.*CODE = \([0-9]*\);/\1/p' app/src/main/java/com/icehome/up/Version.java)
rm -rf "$O" && mkdir -p "$O"/{stub,classes,dex,res,apk/modern,apk/legacy,out,signer}

# Stubs de las dos API de Xposed (solo para compilar)
javac -nowarn -source 8 -target 8 -bootclasspath "$TOOLS/android.jar" -d "$O/stub" $(find local-build/xposed-stub -name '*.java') 2>/dev/null || true
javac -nowarn -encoding UTF-8 --release 17 -cp "$TOOLS/android.jar" -d "$O/stub" $(find local-build/libxposed-stub -name '*.java') 2>/dev/null || true
# Código del módulo
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$TOOLS/android.jar" -cp "$O/stub" \
  -d "$O/classes" $(find app/src/main/java -name '*.java') 2>&1 | grep -v "^warning\|^Note\|warning$" || true
[ -n "$(find "$O/classes" -name 'MainHook.class')" ] || { echo "ERROR: javac ha fallado"; exit 1; }
# DEX
java -cp "$TOOLS/dalvik-dx-16.0.1.jar" com.android.dx.command.Main --dex --min-sdk-version=26 \
  --output="$O/dex/classes.dex" "$O/classes"
# Recursos y manifiesto
# aapt2: el indicado, el del sistema (Termux, ARM) o el que trae apktool (Linux x86_64).
if [ -n "${AAPT2:-}" ]; then cp "$AAPT2" "$O/aapt2"
elif command -v aapt2 >/dev/null 2>&1; then ln -sf "$(command -v aapt2)" "$O/aapt2"
else unzip -p "$TOOLS/apktool-lib-3.0.3.jar" prebuilt/linux/aapt2 > "$O/aapt2"; fi
chmod +x "$O/aapt2" 2>/dev/null || true
"$O/aapt2" compile --dir app/src/main/res -o "$O/res/res.zip"
sed 's#<manifest #<manifest package="com.icehome.up" #' app/src/main/AndroidManifest.xml > "$O/AndroidManifest.xml"
"$O/aapt2" link -I "$TOOLS/android.jar" --manifest "$O/AndroidManifest.xml" --min-sdk-version 30 --target-sdk-version 35 \
  --version-code "$VC" --version-name "$VN" -A app/src/main/assets -o "$O/apk/unsigned.apk" "$O/res/res.zip"
(cd "$O/dex" && zip -q -j ../apk/unsigned.apk classes.dex)
# Dos APK: con META-INF/xposed (libxposed 102) y sin él (legacy, API 93)
cp "$O/apk/unsigned.apk" "$O/apk/legacy/HyperSpring-legacy.apk"
cp "$O/apk/unsigned.apk" "$O/apk/modern/HyperSpring.apk"
(cd app/src/main/resources && zip -q -r -D "$OLDPWD/$O/apk/modern/HyperSpring.apk" META-INF/xposed)
# Alinear, firmar y comprobar
javac -nowarn -cp "$TOOLS/apksig-2.3.0.jar" -d "$O/signer" "$HERE/Sign.java" "$HERE/Verify.java"
KS="${KEYSTORE:-$TOOLS/debug.keystore}"
if [ -n "${KEYSTORE:-}" ] && [ -z "${KS_PASS:-}" ]; then
  # La contraseña se pide una vez y sirve para los dos APK (no se ve al escribirla).
  printf "Contraseña del keystore: "; stty -echo 2>/dev/null; read -r KS_PASS; stty echo 2>/dev/null; echo
  export KS_PASS
fi
for v in modern/HyperSpring legacy/HyperSpring-legacy; do
  python3 -I "$HERE/zipalign.py" "$O/apk/$v.apk" "$O/apk/$v-aligned.apk"
  java $X -cp "$O/signer:$TOOLS/apksig-2.3.0.jar" Sign "$KS" "$O/apk/$v-aligned.apk" "$O/out/$(basename $v).apk"
  java $X -cp "$O/signer:$TOOLS/apksig-2.3.0.jar" Verify "$O/out/$(basename $v).apk"
done
"$O/aapt2" dump badging "$O/out/HyperSpring.apk" > "$O/badging.txt"; head -1 "$O/badging.txt"
mkdir -p apk && cp "$O"/out/*.apk apk/
echo "OK -> apk/HyperSpring.apk (API 102) y apk/HyperSpring-legacy.apk (API 93)"
