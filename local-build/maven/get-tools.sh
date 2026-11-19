#!/usr/bin/env bash
# Descarga en $TOOLS (por defecto ~/tools) lo que necesita local-build/maven/build.sh.
# Todo de Maven Central y raw.githubusercontent.com. Uso: bash local-build/maven/get-tools.sh
set -euo pipefail
TOOLS="${TOOLS:-$HOME/tools}"
mkdir -p "$TOOLS"
get() {
  local name="$1" url="$2"
  if [ -s "$TOOLS/$name" ] && [ "$(wc -c < "$TOOLS/$name")" -gt 1000 ]; then echo "ya está: $name"; return; fi
  for i in 1 2 3 4; do
    curl -fsSL -o "$TOOLS/$name" "$url" && [ "$(wc -c < "$TOOLS/$name")" -gt 1000 ] && { echo "descargado: $name"; return; }
    # Maven Central limita peticiones seguidas (error 429): se espera y se repite.
    echo "reintentando $name…"; sleep $((i * 10))
  done
  echo "ERROR: no se pudo descargar $name"; exit 1
}
M=https://repo1.maven.org/maven2
get android.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-35/android.jar
get dalvik-dx-16.0.1.jar $M/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar
get apksig-2.3.0.jar $M/com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar
# Solo para la clave de depuración (pruebas) y el aapt2 de ordenadores x86_64.
get debug.keystore https://raw.githubusercontent.com/patrickfav/uber-apk-signer/main/src/main/resources/debug.keystore
if ! command -v aapt2 >/dev/null 2>&1; then
  get apktool-lib-3.0.3.jar $M/org/apktool/apktool-lib/3.0.3/apktool-lib-3.0.3.jar
fi
echo "Listo: herramientas en $TOOLS"
