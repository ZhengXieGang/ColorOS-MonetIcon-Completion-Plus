#!/system/bin/sh

# === 路径配置 ===
MOD_DIR="/data/adb/modules/ThemedIconCompletion"
WEB_ROOT="$MOD_DIR/webroot"
VERSION_FILE="$WEB_ROOT/version"

# 临时缓存目录
CACHE_DIR="/data/adb/uxicons_cache_tmp"

# 下载的 zip 路径
ZIP_FILE="$CACHE_DIR/uxicons.zip"

# 目标路径 A (保持不变)
TARGET_A="$MOD_DIR/data/oplus/uxicons/"

# 【修正点】目标路径 B (增加了 hdpi 子目录)
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

echo ">>> 正在解压..."
# 解压到缓存目录
unzip -o "$ZIP_FILE" -d "$CACHE_DIR" > /dev/null 2>&1

if [ $? -ne 0 ]; then
    echo "错误: 解压失败，文件可能损坏"
    rm -rf "$CACHE_DIR"
    exit 1
fi

echo ">>> 正在合并图标..."
# 确保目标目录存在
mkdir -p "$TARGET_A"
mkdir -p "$TARGET_B"

# 复制文件 (cp -rf)
# 将 uxicons 里的所有内容复制到目标目录
cp -rf "$CACHE_DIR/uxicons/"* "$TARGET_A"
cp -rf "$CACHE_DIR/uxicons/"* "$TARGET_B"

echo ">>> 更新版本信息..."
if [ ! -z "$NEW_VERSION" ]; then
    echo "$NEW_VERSION" > "$VERSION_FILE"
fi

echo ">>> 清理临时目录..."
rm -rf "$CACHE_DIR"

echo ">>> 刷新系统主题缓存..."
rm -rf /data/data/com.heytap.theme/cache/*
rm -rf /data/data/com.oplus.themestore/cache/*
rm -rf /data/user_de/0/com.android.launcher/cache/*

echo ">>> 全部完成 (Success)"
exit 0