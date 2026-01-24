#!/system/bin/sh
# scan_monet.sh - Pre-filtered Sequential Scanner

# === 1. Environment ===
TMP_DIR="/data/adb/moneticon_tmp"
PROGRESS_FILE="$TMP_DIR/progress.json"
RESULT_FILE="$MODDIR/webroot/moneticon_apps"
SKIP_FILE="$TMP_DIR/skip_list.txt"
RAW_MAP="$TMP_DIR/raw_map.txt"
TARGET_LIST="$TMP_DIR/target_list.txt"
LOG_FILE="$TMP_DIR/scan.log"

# Clean & Init
mkdir -p "$TMP_DIR"
# Do NOT delete RESULT_FILE here (Managed by UI Refresh)
# Cleanup previous run temp files
rm -f "$PROGRESS_FILE" "$SKIP_FILE" "$RAW_MAP" "$TARGET_LIST"

# Trap signals
cleanup() {
    rm -f "$PROGRESS_FILE" "$SKIP_FILE" "$RAW_MAP" "$TARGET_LIST"
    exit 0
}
trap cleanup EXIT INT TERM

# === 2. Configuration ===
# Hardcode specific path to avoid ambiguity in different execution contexts
MODDIR="/data/adb/modules/ThemedIconCompletion"
# Ensure the directory exists
mkdir -p "$MODDIR/webroot"
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

echo "准备扫描列表..." > "$LOG_FILE"

# === 3. Prepare Lists ===

# 3.1 Build Skip List (Results + Blacklist)
# Filter empty lines & strip CR
echo -n "" > "$SKIP_FILE"
if [ -f "$RESULT_FILE" ]; then
    grep -v '^$' "$RESULT_FILE" | tr -d '\r' >> "$SKIP_FILE" 2>/dev/null
fi
if [ -f "$BLACKLIST_FILE" ]; then
    grep -v '^$' "$BLACKLIST_FILE" | tr -d '\r' >> "$SKIP_FILE" 2>/dev/null
fi
# Ensure unique and sorted
sort -u "$SKIP_FILE" -o "$SKIP_FILE"

# 3.2 Build Raw Map (pkg path)
# pm list output: package:/path/to/apk=com.pkg
# Parsing: Split on LAST '=' to handle paths with '='
pm list packages -f -3 | sed 's/^package://' | while IFS= read -r line; do
    # Extract package name (everything after last =)
    pkg_name="${line##*=}"
    # Extract APK path (everything before last =)
    apk_path="${line%=*}"
    
    # Strip CR/whitespace from pkg_name
    pkg_name=$(echo "$pkg_name" | tr -d '\r' | tr -d '[:space:]')
    
    if [ -n "$pkg_name" ] && [ -n "$apk_path" ]; then
        echo "$pkg_name $apk_path"
    fi
done > "$RAW_MAP"

# 3.3 Filter Targets
# Filter RAW_MAP against SKIP_FILE
# awk logic: Read SKIP_FILE first, store in array. Read RAW_MAP, print if $1 (pkg) not in array.
awk 'NR==FNR {skip[$1]=1; next} !($1 in skip) {print $0}' "$SKIP_FILE" "$RAW_MAP" > "$TARGET_LIST"

# === 4. Scanning Loop ===
TOTAL=$(wc -l < "$TARGET_LIST")
CURRENT=0
# Session found count (Starts at 0, only counts NEW findings in this session)
FOUND=0 

# Debug Stats
RAW_COUNT=$(wc -l < "$RAW_MAP")
SKIP_COUNT=$(wc -l < "$SKIP_FILE")
echo "DEBUG: Raw=$RAW_COUNT, Skip=$SKIP_COUNT, Target=$TOTAL" >> "$LOG_FILE"
echo "Start Scanning ($TOTAL apps)..." >> "$LOG_FILE"

# Initial Progress
echo "{\"total\": $TOTAL, \"current\": 0, \"found\": 0, \"pkg\": \"Starting...\"}" > "$PROGRESS_FILE.tmp"
mv "$PROGRESS_FILE.tmp" "$PROGRESS_FILE"

# Loop through TARGET_LIST
# Format: pkg_name apk_path
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
    echo "{\"total\": $TOTAL, \"current\": $CURRENT, \"found\": $FOUND, \"pkg\": \"$pkg_name\"}" > "$PROGRESS_FILE.tmp"
    mv "$PROGRESS_FILE.tmp" "$PROGRESS_FILE"

done < "$TARGET_LIST"

# Final Update
echo "{\"total\": $TOTAL, \"current\": $CURRENT, \"found\": $FOUND, \"pkg\": \"Completed\"}" > "$PROGRESS_FILE.tmp"
mv "$PROGRESS_FILE.tmp" "$PROGRESS_FILE"

echo "扫描完成。" >> "$LOG_FILE"
echo "DONE" >> "$LOG_FILE"

# Cleanup
rm -f "$SKIP_FILE" "$RAW_MAP" "$TARGET_LIST"
