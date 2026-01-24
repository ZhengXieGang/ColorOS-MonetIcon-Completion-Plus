#!/system/bin/sh
# scan_monet.sh - Robust Parallel Monet Scanner

# === 1. Environment & Constraints ===
TMP_DIR="/data/adb/moneticon_tmp"
LOCK_FILE="$TMP_DIR/scan.lock"
PROGRESS_FILE="$TMP_DIR/progress.json"
RESULT_FILE="$TMP_DIR/moneticon_apps"
PIPE_FILE="$TMP_DIR/worker.pipe"
STATUS_PIPE="$TMP_DIR/status.pipe"

# Ensure clean environment
mkdir -p "$TMP_DIR"
rm -f "$PIPE_FILE" "$STATUS_PIPE" "$LOCK_FILE"
# Check lock? For now, we force overwrite/start as this IS the scan process.
touch "$LOCK_FILE"

# Trap signals for cleanup
cleanup() {
    rm -f "$PIPE_FILE" "$STATUS_PIPE" "$LOCK_FILE"
    # Kill descendants (if supported by shell context, mostly best effort)
    pkill -P $$ 2>/dev/null
    exit 0
}
trap cleanup EXIT INT TERM

# === 2. Configuration ===
MODDIR=${0%/*}
AAPT_DIR="$MODDIR/webroot/aapt2"

# Detect Architecture
ABI=$(getprop ro.product.cpu.abi)
if echo "$ABI" | grep -q "arm64"; then
    AAPT_BIN="aapt2-arm64-v8a"
else
    AAPT_BIN="aapt2-armeabi-v7a"
fi
AAPT="$AAPT_DIR/$AAPT_BIN"
chmod +x "$AAPT"

# Detect CPU Cores & Set Threads
CPU_CORES=$(grep -c ^processor /proc/cpuinfo 2>/dev/null)
[ -z "$CPU_CORES" ] && CPU_CORES=4
THREADS=$CPU_CORES
[ "$THREADS" -gt 8 ] && THREADS=8

# === 3. Initialize Concurrency (Token Bucket) ===
mkfifo "$PIPE_FILE"
# Open file descriptor 3 for read/write on the pipe
exec 3<>"$PIPE_FILE"

# Inject tokens
for i in $(seq 1 $THREADS); do
    echo >&3
done

# === 4. Progress Monitor ===
mkfifo "$STATUS_PIPE"
echo "" > "$RESULT_FILE" # Clear result file

# Background process to handle status aggregation
(
    total=0
    current=0
    found=0
    
    # Wait for total count first
    read -r total_count < "$STATUS_PIPE"
    total=$total_count

    while read -r status < "$STATUS_PIPE"; do
        if [ "$status" = "DONE" ]; then
             # Final flush
            echo "{\"total\": $total, \"current\": $current, \"found\": $found}" > "$PROGRESS_FILE.tmp"
            mv "$PROGRESS_FILE.tmp" "$PROGRESS_FILE"
            break
        elif [ "$status" = "CHECKED" ]; then
            current=$((current + 1))
        elif [ "$status" = "FOUND" ]; then
            current=$((current + 1))
            found=$((found + 1))
        fi
        
        # Throttling: Write only every 5 updates to reduce I/O pressure and avoid pipe deadlock
        if [ $((current % 5)) -eq 0 ]; then
            echo "{\"total\": $total, \"current\": $current, \"found\": $found}" > "$PROGRESS_FILE.tmp"
            mv "$PROGRESS_FILE.tmp" "$PROGRESS_FILE"
        fi
    done
) &
STATUS_PID=$!
exec 4>"$STATUS_PIPE"

# === 5. Scan Logic (The Worker Check) ===
check_app() {
    local apk_path="$1"
    local pkg_name="$2"
    
    # [Step 1: Pre-check] Zip structure
    # Check if any resource folder for API 26+ exists (adaptive icons started roughly there)
    # unzip -l is fast.
    if ! unzip -l "$apk_path" 2>/dev/null | grep -q "res/.*-v26"; then
        return 1 # Fail
    fi

    # [Step 2: Entry Lookup] Badging
    local output
    output=$("$AAPT" dump badging "$apk_path" 2>/dev/null)
    # Extract icon path
    local icon_path
    icon_path=$(echo "$output" | grep "application: label" | sed -n "s/.*icon='\([^']*\)'.*/\1/p")
    
    # Must be XML
    if [[ "$icon_path" != *.xml ]]; then
        return 1
    fi

    # [Step 3: Deep Inspection] XML Tree
    if "$AAPT" dump xmltree "$apk_path" --file "$icon_path" 2>/dev/null | grep -q -i "monochrome"; then
        echo "$pkg_name" >> "$RESULT_FILE"
        return 0 # Success
    fi
    
    return 1
}

# === 6. Main Dispatcher ===
echo "Gathering package list..."
# Load list into memory variable. Warning: Large lists might consume memory, but usually safe for package list.
RAW_LIST=$(pm list packages -f -3)
TOTAL_COUNT=$(echo "$RAW_LIST" | grep -c "package:")

# Send total to progress monitor
echo "$TOTAL_COUNT" >&4

# Use for-loop with IFS='newline' to avoid subshell issues with 'wait'
IFS=$'\n'
for line in $RAW_LIST; do
    unset IFS
    [ -z "$line" ] && continue
    
    temp=${line#package:}
    apk_path=${temp%=*}
    pkg_name=${temp##*=}
    
    if [ -z "$apk_path" ]; then continue; fi

    # Acquire Token
    read -u 3 token

    # Spawn Worker
    (
        if check_app "$apk_path" "$pkg_name"; then
             echo "FOUND" >&4
        else
             echo "CHECKED" >&4
        fi
        
        # Return Token
        echo >&3
    ) &
done

# Wait for all background jobs to finish
wait

# Signal DONE
echo "DONE" >&4
wait $STATUS_PID

# Final Cleanup (handled by trap too, but good to be explicit)
rm -f "$LOCK_FILE"
