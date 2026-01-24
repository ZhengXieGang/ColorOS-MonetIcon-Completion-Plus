#!/system/bin/sh
# scan_monet.sh - Sequential Incremental Scanner (Legacy Protocol)

# === 1. Environment Setup ===
TMP_DIR="/data/adb/moneticon_tmp"
RESULT_FILE="$TMP_DIR/moneticon_apps"
SKIP_FILE="$TMP_DIR/skip_list.txt"
LOG_FILE="$TMP_DIR/scan.log"

# Clean & Init
mkdir -p "$TMP_DIR"
rm -f "$SKIP_FILE"

# Trap signals
cleanup() {
    rm -f "$SKIP_FILE"
    exit 0
}
trap cleanup EXIT INT TERM

# === 2. Configuration ===
MODDIR=${0%/*}
AAPT_DIR="$MODDIR/webroot/aapt2"
BLACKLIST_FILE="$MODDIR/webroot/blacklist"

# Architecture Check
ABI=$(getprop ro.product.cpu.abi)
if echo "$ABI" | grep -q "arm64"; then
    AAPT_BIN="aapt2-arm64-v8a"
else
    AAPT_BIN="aapt2-armeabi-v7a"
fi
AAPT="$AAPT_DIR/$AAPT_BIN"
if [ -f "$AAPT" ]; then
    chmod +x "$AAPT"
fi

# === 3. Incremental Logic Setup ===
echo -n "" > "$SKIP_FILE"

# Load existing results (if file exists)
if [ -f "$RESULT_FILE" ]; then
    cat "$RESULT_FILE" >> "$SKIP_FILE"
fi
# Load blacklist (if exists)
if [ -f "$BLACKLIST_FILE" ]; then
    cat "$BLACKLIST_FILE" >> "$SKIP_FILE"
fi
sort -u "$SKIP_FILE" -o "$SKIP_FILE"

echo "正在获取应用列表..." > "$LOG_FILE"
RAW_LIST=$(pm list packages -f -3)
TOTAL=$(echo "$RAW_LIST" | grep -c "package:")
CURRENT=0

echo "开始扫描... (共 $TOTAL 个应用)" >> "$LOG_FILE"

# === 4. Sequential Scan Loop ===
IFS=$'\n'
for line in $RAW_LIST; do
    unset IFS
    [ -z "$line" ] && continue
    
    # Parse line: package:PATH=PKG
    temp=${line#package:}
    apk_path=${temp%=*}
    pkg_name=${temp##*=}
    
    if [ -z "$apk_path" ] || [ -z "$pkg_name" ]; then continue; fi

    CURRENT=$((CURRENT + 1))
    
    # --- Check Logic ---
    # 0. Incremental Skip
    if grep -F -x -q "$pkg_name" "$SKIP_FILE"; then
        # Update Log Periodically even when skipping, to keep UI alive
        if [ $((CURRENT % 5)) -eq 0 ]; then
             echo "PROGRESS:$CURRENT/$TOTAL:$pkg_name" >> "$LOG_FILE"
        fi
        continue
    fi

    # 1. Direct AAPT Check (Trust Chain)
    # Output line example: application: label='App Name' icon='res/mipmap-anydpi-v26/ic_launcher.xml'
    # We grab the icon path.
    output=$("$AAPT" dump badging "$apk_path" 2>/dev/null)
    icon_path=$(echo "$output" | grep "application:" | sed -n "s/.*icon='\([^']*\)'.*/\1/p" | head -n 1)
    
    if [[ "$icon_path" == *.xml ]]; then
        # 2. XML Tree Deep Check
        # Check for 'monochrome' OR 'themed_icon'
        if "$AAPT" dump xmltree "$apk_path" --file "$icon_path" 2>/dev/null | grep -q -i -E "monochrome|themed_icon"; then
            echo "$pkg_name" >> "$RESULT_FILE"
        fi
    fi
    
    # Update Progress (Every 5 processed items)
    if [ $((CURRENT % 5)) -eq 0 ]; then
        echo "PROGRESS:$CURRENT/$TOTAL:$pkg_name" >> "$LOG_FILE"
    fi
done

# Final flush
echo "PROGRESS:$TOTAL/$TOTAL:完成" >> "$LOG_FILE"
echo "扫描完成。" >> "$LOG_FILE"
echo "DONE" >> "$LOG_FILE"
