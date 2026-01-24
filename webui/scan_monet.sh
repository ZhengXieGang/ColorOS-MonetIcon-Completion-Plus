#!/system/bin/sh

# Logging Setup
LOG_FILE="/data/adb/moneticon_tmp/scan.log"
RESULT_FILE="/data/adb/moneticon_tmp/moneticon_apps"

# Ensure clean state
echo "正在初始化扫描..." > "$LOG_FILE"
echo "" > "$RESULT_FILE"

# === 1. Prepare AAPT2 ===
# MODDIR corresponds to the directory where this script resides.
MODDIR=${0%/*}
# AAPT binaries are expected in webroot/aapt2/
AAPT_DIR="$MODDIR/webroot/aapt2"

# Detect Architecture
ABI=$(getprop ro.product.cpu.abi)
if echo "$ABI" | grep -q "arm64"; then
    AAPT_BIN="aapt2-arm64-v8a"
else
    # Fallback to 32-bit (v7a) for others, assuming mostly arm devices
    AAPT_BIN="aapt2-armeabi-v7a"
fi

AAPT="$AAPT_DIR/$AAPT_BIN"

# Validation
if [ ! -f "$AAPT" ]; then
    echo "错误: 未找到 AAPT2 二进制文件: $AAPT" >> "$LOG_FILE"
    echo "DONE" >> "$LOG_FILE"
    exit 1
fi

chmod +x "$AAPT"
echo "引擎: $AAPT_BIN" >> "$LOG_FILE"

# === 2. Fetch App List ===
echo "正在获取应用列表..." >> "$LOG_FILE"
# Format: package:/data/app/~~.../base.apk=com.example
RAW_LIST=$(pm list packages -f -3)
TOTAL=$(echo "$RAW_LIST" | grep -c "package:")
CURRENT=0

echo "开始深度扫描... (共 $TOTAL 个应用)" >> "$LOG_FILE"

# === 3. Scanning Loop ===
echo "$RAW_LIST" | while read -r line; do
    # Skip empty lines
    [ -z "$line" ] && continue
    
    # Parse line: package:PATH=PKG
    temp=${line#package:}
    apk_path=${temp%=*}
    pkg_name=${temp##*=}

    if [ -z "$apk_path" ] || [ -z "$pkg_name" ]; then
        continue
    fi

    CURRENT=$((CURRENT + 1))

    # --- Analysis Logic ---
    is_supported=false

    # Step 1: Get Icon Path from Badging
    # Output line example: application: label='App Name' icon='res/mipmap-anydpi-v26/ic_launcher.xml'
    icon_path=$($AAPT dump badging "$apk_path" 2>/dev/null | grep "application: label" | sed -n "s/.*icon='\([^']*\)'.*/\1/p")
    
    # Only proceed if we found an XML icon (Adaptive Icons are usually XML)
    if [[ "$icon_path" == *.xml ]]; then
        # Step 2: Dump XML Tree and search for 'monochrome'
        # This confirms if the adaptive icon has a monochrome layer defined
        if $AAPT dump xmltree "$apk_path" --file "$icon_path" 2>/dev/null | grep -q "monochrome"; then
            is_supported=true
        fi
    fi

    if [ "$is_supported" = true ]; then
        echo "$pkg_name" >> "$RESULT_FILE"
        # Optional: Log finding
        # echo "Found: $pkg_name" >> "$LOG_FILE"
    fi

    # Progress Update
    echo "PROGRESS:$CURRENT/$TOTAL:$pkg_name" >> "$LOG_FILE"
done

echo "扫描完成。" >> "$LOG_FILE"
echo "DONE" >> "$LOG_FILE"
