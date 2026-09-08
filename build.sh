#!/bin/bash
# 手工构建玻璃天气小组件 APK（无需 Gradle）
set -e
BT=/workspace/sdk/android-sdk/build-tools/34.0.0
PLAT=/workspace/sdk/android-sdk/platforms/android-36/android.jar
BIN=/workspace/sdk/bin
PROJ=/workspace/glasswidget
cd "$PROJ"

rm -rf build gen classes
mkdir -p build gen classes

echo "[1/6] aapt2 compile 资源..."
"$BIN/aapt2" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link..."
"$BIN/aapt2" link \
  -I "$PLAT" \
  --manifest AndroidManifest.xml \
  --java gen \
  -o build/app-unsigned.apk \
  --auto-add-overlay \
  build/res.zip

echo "[3/6] javac 编译 Java..."
find src gen -name '*.java' > build/sources.txt
javac -source 8 -target 8 -bootclasspath "$PLAT" -encoding UTF-8 -d classes @build/sources.txt 2>&1 | grep -v "bootstrap class path\|source value 8\|target value 8\|^1 warning" || true

echo "[4/6] d8 打包 classes.dex..."
(cd classes && jar cf "$PROJ/build/app.jar" .)
rm -rf build/dex && mkdir -p build/dex
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$PLAT" --output build/dex build/app.jar

echo "[5/6] 合并 dex + zipalign..."
python3 - <<'EOF'
import zipfile, shutil
src = '/workspace/glasswidget/build/app-unsigned.apk'
dex = '/workspace/glasswidget/build/dex/classes.dex'
tmp = '/workspace/glasswidget/build/app-merged.apk'
shutil.copy(src, tmp)
with zipfile.ZipFile(tmp, 'a', zipfile.ZIP_DEFLATED) as z:
    if 'classes.dex' in z.namelist():
        pass
    z.write(dex, 'classes.dex')
print('classes.dex merged, total entries ok')
EOF
"$BIN/zipalign" -f 4 build/app-merged.apk build/app-aligned.apk

echo "[6/6] 签名..."
if [ ! -f build/debug.keystore ]; then
  keytool -genkeypair -keystore build/debug.keystore -alias gw -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=Glass Weather,O=GlassWeather,C=CN" 2>/dev/null
fi
"$BT/apksigner" sign \
  --ks build/debug.keystore --ks-key-alias gw \
  --ks-pass pass:android --key-pass pass:android \
  --out build/glass-weather.apk build/app-aligned.apk

echo "==== 完成 ===="
ls -lh build/glass-weather.apk
"$BT/apksigner" verify build/glass-weather.apk && echo "签名验证 OK"
