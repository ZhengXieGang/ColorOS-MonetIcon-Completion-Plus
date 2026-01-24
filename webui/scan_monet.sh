#!/system/bin/sh

# Logging Setup
LOG_FILE="/data/adb/moneticon_tmp/scan.log"
RESULT_FILE="/data/adb/moneticon_tmp/moneticon_apps"

# Ensure clean state
echo "正在初始化扫描..." > "$LOG_FILE"
echo "" > "$RESULT_FILE"

# === 1. Setup Environment ===
MODDIR=${0%/*}
AAPT_DIR="$MODDIR/webroot/aapt2"

# Detect Architecture for AAPT2
ABI=$(getprop ro.product.cpu.abi)
if echo "$ABI" | grep -q "arm64"; then
    AAPT_BIN="aapt2-arm64-v8a"
else
    AAPT_BIN="aapt2-armeabi-v7a"
fi
AAPT="$AAPT_DIR/$AAPT_BIN"

if [ ! -f "$AAPT" ]; then
    echo "错误: 未找到 AAPT2 二进制文件: $AAPT" >> "$LOG_FILE"
    echo "DONE" >> "$LOG_FILE"
    exit 1
fi
chmod +x "$AAPT"

# Detect CPU Cores for Parallelism
CPU_CORES=$(grep -c ^processor /proc/cpuinfo 2>/dev/null)
if [ -z "$CPU_CORES" ] || [ "$CPU_CORES" -lt 1 ]; then
    CPU_CORES=4
fi

# Determine Thread Count (Conservative: Cores - 1, but max 8)
if [ "$CPU_CORES" -gt 4 ]; then
    THREADS=$(($CPU_CORES - 1))
else
    THREADS=$CPU_CORES
fi
[ "$THREADS" -gt 8 ] && THREADS=8

echo "引擎: $AAPT_BIN | 线程: $THREADS" >> "$LOG_FILE"

# === 2. Generate Helper Script (Strict Check) ===
# This script runs in parallel for each app
HELPER_SCRIPT="$MODDIR/strict_check_worker.sh"
cat > "$HELPER_SCRIPT" <<EOF
#!/system/bin/sh
path="\$1"
pkg="\$2"
# AAPT path injected from parent
AAPT="$AAPT"

# [Trust Chain Step 1]: Ask AndroidManifest for the active icon
# Output: application: label='App' icon='res/mipmap.../icon.xml'
badging=\$("\$AAPT" dump badging "\$path" 2>/dev/null)
icon_path=\$(echo "\$badging" | grep "application: label" | sed -n "s/.*icon='\([^']*\)'.*/\1/p")

# If no icon path or not XML, it's not a valid Monet target
if [[ "\$icon_path" != *.xml ]]; then
    echo "CHECKED:\$pkg"
    exit 0
fi

# [Trust Chain Step 2]: Check ONLY the active icon file for monochrome tag
if "\$AAPT" dump xmltree "\$path" --file "\$icon_path" 2>/dev/null | grep -q -i "monochrome"; then
    echo "FOUND:\$pkg"
else
    echo "CHECKED:\$pkg"
fi
EOF
chmod +x "$HELPER_SCRIPT"

# === 3. Execute Scan ===
echo "正在获取应用列表..." >> "$LOG_FILE"
RAW_LIST=$(pm list packages -f -3)
TOTAL=$(echo "$RAW_LIST" | grep -c "package:")
CURRENT=0

echo "开始通过 $THREADS 线程并发扫描... (共 $TOTAL 个应用)" >> "$LOG_FILE"

# Pipeline:
# 1. echo List -> 2. Sed format args -> 3. xargs Parallel -> 4. While loop aggregate
echo "$RAW_LIST" | sed 's/^package://; s/=/ /' | \
xargs -n 2 -P "$THREADS" sh "$HELPER_SCRIPT" | \
while read -r line; do
    pkg=""
    
    if [[ "$line" == "FOUND:"* ]]; then
        pkg=${line#FOUND:}
        echo "$pkg" >> "$RESULT_FILE"
    elif [[ "$line" == "CHECKED:"* ]]; then
        pkg=${line#CHECKED:}
    fi
    
    # Update Progress
    if [ -n "$pkg" ]; then
        CURRENT=$((CURRENT + 1))
        # Log progress for WebUI
        echo "PROGRESS:$CURRENT/$TOTAL:$pkg" >> "$LOG_FILE"
    fi
done

# === 4. Cleanup ===
rm "$HELPER_SCRIPT"

echo "扫描完成。" >> "$LOG_FILE"
echo "DONE" >> "$LOG_FILE"
