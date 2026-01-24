#!/system/bin/sh
# scan_monet.sh - Sequential Incremental Scanner

# === 1. Environment Setup ===
TMP_DIR="/data/adb/moneticon_tmp"
PROGRESS_FILE="$TMP_DIR/progress.json"
RESULT_FILE="$TMP_DIR/moneticon_apps"
SKIP_FILE="$TMP_DIR/skip_list.txt"
LOG_FILE="$TMP_DIR/scan.log"

# Clean & Init
mkdir -p "$TMP_DIR"
# Do NOT delete RESULT_FILE here (Managed by UI Refresh)
# Cleanup previous run temp files if any
rm -f "$PROGRESS_FILE" "$SKIP_FILE"

# Trap signals
cleanup() {
    rm -f "$PROGRESS_FILE" "$SKIP_FILE"
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
    # Ensure blacklist items are removed from result count calculation below?
    # No, skip file is just for skipping scan.
fi

# Ensure unique entries for fast grep
sort -u "$SKIP_FILE" -o "$SKIP_FILE"

# Initialize Counters
TOTAL=0
CURRENT=0
FOUND=0
if [ -f "$RESULT_FILE" ]; then
    FOUND=$(grep -c . "$RESULT_FILE")
fi

echo "正在获取应用列表..." > "$LOG_FILE"
RAW_LIST=$(pm list packages -f -3)
TOTAL=$(echo "$RAW_LIST" | grep -c "package:")

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
        # Already processed or blacklisted
        # We don't increment FOUND here because we want to count *new* findings?
        # Or do we want to show total valid apps?
        # The UI shows "Found: X". If we skip existing results, FOUND stays at initial value.
        # This is correct.
        
        # Update Progress Periodically (every 10 skipped items to be fast)
        if [ $((CURRENT % 10)) -eq 0 ]; then
            echo "{\"total\": $TOTAL, \"current\": $CURRENT, \"found\": $FOUND, \"pkg\": \"$pkg_name\"}" > "$PROGRESS_FILE.tmp"
            mv "$PROGRESS_FILE.tmp" "$PROGRESS_FILE"
        fi
        continue
    fi

    # 1. Direct AAPT Check (No unzip pre-check)
    output=$("$AAPT" dump badging "$apk_path" 2>/dev/null)
    
    # Robust icon path extraction (filter line first, then extract)
    # This handles cases where badging output format might vary
    icon_path=$(echo "$output" | grep "application:" | sed -n "s/.*icon='\([^']*\)'.*/\1/p" | head -n 1)
    
    if [[ "$icon_path" == *.xml ]]; then
        # 2. XML Tree Deep Check
        # Check for 'monochrome' OR 'themed_icon'
        if "$AAPT" dump xmltree "$apk_path" --file "$icon_path" 2>/dev/null | grep -q -i -E "monochrome|themed_icon"; then
            echo "$pkg_name" >> "$RESULT_FILE"
            FOUND=$((FOUND + 1))
        fi
    fi
    
    # Update Progress (Every 5 processed items)
    if [ $((CURRENT % 5)) -eq 0 ]; then
        echo "{\"total\": $TOTAL, \"current\": $CURRENT, \"found\": $FOUND, \"pkg\": \"$pkg_name\"}" > "$PROGRESS_FILE.tmp"
        mv "$PROGRESS_FILE.tmp" "$PROGRESS_FILE"
    fi
done

# Final Update
echo "{\"total\": $TOTAL, \"current\": $CURRENT, \"found\": $FOUND, \"pkg\": \"Completed\"}" > "$PROGRESS_FILE.tmp"
mv "$PROGRESS_FILE.tmp" "$PROGRESS_FILE"

echo "扫描完成。" >> "$LOG_FILE"
echo "DONE" >> "$LOG_FILE"
