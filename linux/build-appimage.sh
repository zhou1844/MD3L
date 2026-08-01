#!/usr/bin/env bash
#
# build-appimage.sh —— 将 MD3L 打包为 AppImage 格式
#
# 用法：
#   ./build-appimage.sh                # 构建 AppImage
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

PKG_NAME="MD3L"
PKG_VERSION="1.4.4.a"
APPIMAGE_NAME="MD3L-${PKG_VERSION}-x86_64.AppImage"

echo ">>> 开始构建 AppImage ..."

# 1. 使用 gradle 构建应用目录
echo ">>> 使用 gradle 构建应用目录 ..."
./gradlew packageAppImage --console=plain

# 2. 查找生成的应用目录
APP_DIR="$(find build/compose/binaries/main/app -maxdepth 1 -type d -name 'MD3L' | head -1)"

if [[ -z "$APP_DIR" || ! -d "$APP_DIR" ]]; then
    echo "!!! 未找到生成的应用目录" >&2
    exit 1
fi

echo ">>> 应用目录: $APP_DIR"

# 3. 准备 AppDir 结构 (AppImage 需要的格式)
APPDIR="$ROOT_DIR/AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR"

echo ">>> 准备 AppDir 结构: $APPDIR"

# 创建 AppDir 基本结构
# 关键：jpackage 启动器使用相对路径 ../lib/ 查找库文件
# 从 usr/bin/ 看，../lib/ 指向 usr/lib/
# 所以我们需要把 lib 内容直接放在 usr/lib/ 下
mkdir -p "$APPDIR/usr/bin"
mkdir -p "$APPDIR/usr/lib"
mkdir -p "$APPDIR/usr/share/applications"
mkdir -p "$APPDIR/usr/share/icons/hicolor/256x256/apps"

# 复制 lib 内容到 usr/lib/ (不是 usr/lib/lib/)
cp -r "$APP_DIR/lib/"* "$APPDIR/usr/lib/"

# 复制 bin 内容到 usr/bin/
cp -r "$APP_DIR/bin/"* "$APPDIR/usr/bin/"

# 在 AppDir 根目录创建 .desktop 文件 (appimagetool 要求)
cat > "$APPDIR/MD3L.desktop" <<'DESKTOP'
[Desktop Entry]
Name=MD3L
Comment=MD3L - A modern Minecraft launcher (Linux Edition)
Exec=AppRun
Icon=MD3L
Terminal=false
Type=Application
Categories=Game;
DESKTOP

# 同时复制到 usr/share/applications
cp "$APPDIR/MD3L.desktop" "$APPDIR/usr/share/applications/MD3L.desktop"

# 复制图标到根目录
ICON_FILE=""
if [[ -f "$APP_DIR/lib/MD3L.png" ]]; then
    cp "$APP_DIR/lib/MD3L.png" "$APPDIR/MD3L.png"
    ICON_FILE="$APPDIR/MD3L.png"
elif [[ -f "$ROOT_DIR/src/main/resources/app_icon.png" ]]; then
    cp "$ROOT_DIR/src/main/resources/app_icon.png" "$APPDIR/MD3L.png"
    ICON_FILE="$APPDIR/MD3L.png"
fi

# 复制图标到 hicolor 主题目录
if [[ -n "$ICON_FILE" ]]; then
    cp "$ICON_FILE" "$APPDIR/usr/share/icons/hicolor/256x256/apps/MD3L.png"
fi

# 创建符号链接让 jpackage 启动器找到文件
# 在 AppDir 根目录创建指向 usr 下文件的符号链接
ln -sf usr/lib "$APPDIR/lib"
ln -sf usr/bin "$APPDIR/bin"

# 创建 AppRun 入口脚本
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/bash
SELF="$(readlink -f "$0")"
HERE="${SELF%/*}"
exec "$HERE/usr/bin/MD3L" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"

# 4. 检查并下载 appimagetool
APPIMAGETOOL=""
if command -v appimagetool &>/dev/null; then
    APPIMAGETOOL="appimagetool"
elif [[ -f "$ROOT_DIR/appimagetool-x86_64.AppImage" ]]; then
    APPIMAGETOOL="$ROOT_DIR/appimagetool-x86_64.AppImage"
else
    echo ">>> 未找到 appimagetool，正在下载 ..."
    wget -q -O "$ROOT_DIR/appimagetool-x86_64.AppImage" \
        "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
    chmod +x "$ROOT_DIR/appimagetool-x86_64.AppImage"
    APPIMAGETOOL="$ROOT_DIR/appimagetool-x86_64.AppImage"
fi

# 5. 打包为 AppImage
OUTPUT="$ROOT_DIR/$APPIMAGE_NAME"
echo ">>> 打包为 AppImage: $OUTPUT"

# 移除已存在的文件
rm -f "$OUTPUT"

# 使用 appimagetool 打包
if [[ "$APPIMAGETOOL" == *".AppImage" ]]; then
    # AppImage 类型的工具需要 APPIMAGE_EXTRACT_AND_RUN 环境变量
    APPIMAGE_EXTRACT_AND_RUN=1 "$APPIMAGETOOL" "$APPDIR" "$OUTPUT"
else
    "$APPIMAGETOOL" "$APPDIR" "$OUTPUT"
fi

if [[ -f "$OUTPUT" ]]; then
    chmod +x "$OUTPUT"
    echo ">>> AppImage 构建完成!"
    ls -lh "$OUTPUT"
    # 清理 AppDir
    rm -rf "$APPDIR"
else
    echo "!!! AppImage 构建失败" >&2
    exit 1
fi
