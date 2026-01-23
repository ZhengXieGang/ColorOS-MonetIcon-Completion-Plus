#!/system/bin/sh

# === 路径配置 ===
MOD_DIR="/data/adb/modules/ThemedIconCompletion"
WEB_ROOT="$MOD_DIR/webroot"
VERSION_FILE="$WEB_ROOT/version"

# 临时缓存目录
CACHE_DIR="/data/adb/uxicons_cache_tmp"

# 下载的 zip 路径
ZIP_FILE="$CACHE_DIR/uxicons.zip"
WEBUI_ZIP="$CACHE_DIR/webui.zip"

# 目标路径
TARGET_A="$MOD_DIR/data/oplus/uxicons/"
TARGET_B="$MOD_DIR/my_product/media/theme/uxicons/hdpi/"

# 传入的新版本号参数
NEW_VERSION="$1"

# === 开始执行 ===
echo ">>> 脚本开始执行 (Root)..."
echo ">>> 正在处理缓存: $CACHE_DIR"

# 1. 检查文件是否存在
if [ ! -f "$ZIP_FILE" ]; then
    echo "错误: 在缓存目录找不到 uxicons.zip"
    exit 1
fi

echo ">>> 正在解压图标包..."
# 解压到缓存目录
unzip -o "$ZIP_FILE" -d "$CACHE_DIR" > /dev/null 2>&1

if [ $? -ne 0 ]; then
    echo "错误: 解压失败，文件可能损坏"
    rm -rf "$CACHE_DIR"
    exit 1
fi

# 2. 检查 WebUI 更新包
if [ -f "$WEBUI_ZIP" ]; then
    echo ">>> 发现 WebUI 包，正在部署..."
    # 解压到模块根目录
    unzip -o "$WEBUI_ZIP" -d "$MOD_DIR" > /dev/null 2>&1
    if [ $? -ne 0 ]; then
        echo "警告: WebUI 更新失败"
    else
        echo "WebUI 已更新"
    fi
fi

echo ">>> 正在合并图标..."
# 确保目标目录存在
mkdir -p "$TARGET_A"
mkdir -p "$TARGET_B"

# 复制文件 (cp -rf)
# 将 uxicons 里的所有内容复制到目标目录
cp -rf "$CACHE_DIR/uxicons/"* "$TARGET_A"
cp -rf "$CACHE_DIR/uxicons/"* "$TARGET_B"

echo ">>> 检查图标屏蔽列表..."
PKG_LIST="/data/adb/monet_pkglist"
if [ -f "$PKG_LIST" ]; then
    count=0
    # Read line by line; handle missing newline at EOF
    while IFS= read -r pkg || [ -n "$pkg" ]; do
        # Trim whitespace
        pkg=$(echo "$pkg" | xargs)
        if [ ! -z "$pkg" ]; then
            # Delete from Target A
            rm -rf "$TARGET_A/$pkg"
            # Delete from Target B
            rm -rf "$TARGET_B/$pkg"
            count=$((count + 1))
        fi
    done < "$PKG_LIST"
    echo "已清理 $count 个被屏蔽的图标"
else
    echo "无屏蔽列表 (pkglist)"
fi

echo ">>> 更新版本信息..."
if [ ! -z "$NEW_VERSION" ]; then
    echo "$NEW_VERSION" > "$VERSION_FILE"
fi

echo ">>> 清理临时目录..."
rm -rf "$CACHE_DIR"

echo ">>> 全部完成 (Success)"
exit 0