#!/system/bin/sh
# scan_monet.sh - Real Total Calculator & Scanner

# === 1. Environment ===
TMP_DIR="/data/adb/moneticon_tmp"
RESULT_FILE="$TMP_DIR/moneticon_apps"
SKIP_FILE="$TMP_DIR/skip_list.txt"
RAW_MAP="$TMP_DIR/raw_map.txt"
TARGET_LIST="$TMP_DIR/target_list.txt"
LOG_FILE="$TMP_DIR/scan.log"

mkdir -p "$TMP_DIR"
rm -f "$SKIP_FILE" "$RAW_MAP" "$TARGET_LIST"

trap "rm -f $SKIP_FILE $RAW_MAP $TARGET_LIST; exit 0" EXIT INT TERM

# === 2. Config ===
MODDIR=${0%/*}
AAPT_DIR="$MODDIR/webroot/aapt2"
BLACKLIST_FILE="$MODDIR/webroot/blacklist"

# Architecture
ABI=$(getprop ro.product.cpu.abi)
if echo "$ABI" | grep -q "arm64"; then
    AAPT_BIN="aapt2-arm64-v8a"
else
    AAPT_BIN="aapt2-armeabi-v7a"
fi
AAPT="$AAPT_DIR/$AAPT_BIN"
[ -f "$AAPT" ] && chmod +x "$AAPT"

# === 3. Prepare Lists ===
echo "准备扫描列表..." > "$LOG_FILE"

# 3.1 Build Skip List
echo -n "" > "$SKIP_FILE"
[ -f "$RESULT_FILE" ] && cat "$RESULT_FILE" >> "$SKIP_FILE"
[ -f "$BLACKLIST_FILE" ] && cat "$BLACKLIST_FILE" >> "$SKIP_FILE"
# Ensure clean unique list of packages
sort -u "$SKIP_FILE" -o "$SKIP_FILE"

# 3.2 Build Raw Map (pkg path)
# pm list output: package:/path/to/apk=com.pkg
# We sed to: com.pkg /path/to/apk
pm list packages -f -3 | sed 's/^package://' | sed 's/=/\t/' | awk '{print $2, $1}' > "$RAW_MAP"

# 3.3 Filter Targets
# We want lines from RAW_MAP where $1 (pkg) is NOT in SKIP_FILE
awk 'NR==FNR {skip[$1]=1; next} !($1 in skip) {print $0}' "$SKIP_FILE" "$RAW_MAP" > "$TARGET_LIST"

# === 4. Scanning Loop ===
TOTAL=$(wc -l < "$TARGET_LIST")
CURRENT=0
FOUND=0
[ -f "$RESULT_FILE" ] && FOUND=$(grep -c . "$RESULT_FILE")

echo "Start Scanning ($TOTAL new apps)..." > "$LOG_FILE"

# Format of TARGET_LIST: com.pkg /path/to/apk
while read -r pkg_name apk_path; do
    [ -z "$pkg_name" ] && continue
    
    CURRENT=$((CURRENT + 1))
    
    # Check
    output=$("$AAPT" dump badging "$apk_path" 2>/dev/null)
    icon_path=$(echo "$output" | grep "application:" | sed -n "s/.*icon='\([^']*\)'.*/\1/p" | head -n 1)
    
    if [[ "$icon_path" == *.xml ]]; then
        if "$AAPT" dump xmltree "$apk_path" --file "$icon_path" 2>/dev/null | grep -q -i -E "monochrome|themed_icon"; then
            echo "$pkg_name" >> "$RESULT_FILE"
            FOUND=$((FOUND + 1))
        fi
    fi
    
    # Real-time Update
    # PROGRESS:Index/Total:FoundCount
    echo "PROGRESS:$CURRENT/$TOTAL:$FOUND" >> "$LOG_FILE"

done < "$TARGET_LIST"

# Final
echo "PROGRESS:$TOTAL/$TOTAL:$FOUND" >> "$LOG_FILE"
echo "扫描完成。" >> "$LOG_FILE"
echo "DONE" >> "$LOG_FILE"

rm -f "$SKIP_FILE" "$RAW_MAP" "$TARGET_LIST"
