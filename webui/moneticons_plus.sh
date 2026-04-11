#!/system/bin/sh

MOD_ROOT="/data/adb/modules/ThemedIconCompletion"
WEB_ROOT="${MOD_ROOT}/webroot"
CACHE_ROOT="/data/adb/moneticon_tmp"
SCAN_LOG="${CACHE_ROOT}/scan.log"
# Result file now in webroot as requested
SCAN_RESULT="${WEB_ROOT}/moneticon_apps"

VERSION_FILE="${WEB_ROOT}/version"
ICON_PATH_FILE="${WEB_ROOT}/icon_path"
PKGLIST_FILE="${WEB_ROOT}/pkglist"
BLACKLIST_FILE="${WEB_ROOT}/blacklist"

AAPT_DIR="${WEB_ROOT}/aapt2"

# === Environment Setup ===
mkdir -p "$CACHE_ROOT"
mkdir -p "$WEB_ROOT"
chmod 755 "$CACHE_ROOT" 2>/dev/null
chmod 755 "$WEB_ROOT" 2>/dev/null

# Determine Architecture for AAPT
ABI=$(getprop ro.product.cpu.abi)
if echo "$ABI" | grep -q "arm64"; then
    AAPT_BIN="aapt2-arm64-v8a"
else
    AAPT_BIN="aapt2-armeabi-v7a"
fi
AAPT="$AAPT_DIR/$AAPT_BIN"
[ -f "$AAPT" ] && chmod +x "$AAPT"

scan_monet() {
    SKIP_FILE="$CACHE_ROOT/skip_list.txt"
    RAW_MAP="$CACHE_ROOT/raw_map.txt"
    TARGET_LIST="$CACHE_ROOT/target_list.txt"
    PROGRESS_FILE="$CACHE_ROOT/progress.json"

    # Helper to update progress JSON
    write_js() {
        # $1=total, $2=current, $3=found, $4=pkg, $5=msg, $6=status
        echo "{\"total\": $1, \"current\": $2, \"found\": $3, \"pkg\": \"$4\", \"msg\": \"$5\", \"status\": \"$6\"}" > "$PROGRESS_FILE.tmp"
        mv "$PROGRESS_FILE.tmp" "$PROGRESS_FILE"
    }

    # Clean legacy/temp files
    rm -f "$PROGRESS_FILE" "$SKIP_FILE" "$RAW_MAP" "$TARGET_LIST"
    
    # Init
    write_js 0 0 0 "" "正在准备扫描..." "init"

    # 1. Build Skip List
    # Output to msg directly?
    write_js 0 0 0 "" "加载已扫描结果..." "init"
    
    echo -n "" > "$SKIP_FILE"
    if [ -f "$SCAN_RESULT" ]; then
        cat "$SCAN_RESULT" | tr -d '\r' | cut -d'|' -f1 | grep -v '^$' >> "$SKIP_FILE" 2>/dev/null
    fi
    if [ -f "$BLACKLIST_FILE" ]; then
        write_js 0 0 0 "" "加载黑名单..." "init"
        cat "$BLACKLIST_FILE" | tr -d '\r' | grep -v '^$' >> "$SKIP_FILE" 2>/dev/null
    fi
    sort -u "$SKIP_FILE" -o "$SKIP_FILE"

    # 2. Build Raw Map
    write_js 0 0 0 "" "获取应用列表..." "init"
    pm list packages -f -3 | sed 's/^package://' | while IFS= read -r line; do
        pkg_name="${line##*=}"
        apk_path="${line%=*}"
        pkg_name=$(echo "$pkg_name" | tr -d '\r' | tr -d '[:space:]')
        if [ -n "$pkg_name" ] && [ -n "$apk_path" ]; then
            echo "$pkg_name $apk_path"
        fi
    done > "$RAW_MAP"

    # 3. Filter Targets
    awk 'NR==FNR {skip[$1]=1; next} !($1 in skip) {print $0}' "$SKIP_FILE" "$RAW_MAP" > "$TARGET_LIST"

    TOTAL=$(wc -l < "$TARGET_LIST")
    CURRENT=0
    FOUND=0

    write_js "$TOTAL" 0 0 "" "开始扫描 ($TOTAL 个新应用)..." "running"

    # 4. Scanning Loop
    while read -r pkg_name apk_path; do
        [ -z "$pkg_name" ] && continue
        CURRENT=$((CURRENT + 1))

        # Update Progress
        write_js "$TOTAL" "$CURRENT" "$FOUND" "$pkg_name" "" "running"

        # 使用 AAPT 检查图标
        if [ ! -f "$AAPT" ]; then
            continue
        fi
        output=$("$AAPT" dump badging "$apk_path" 2>/dev/null)
        icon_path=$(echo "$output" | grep "application:" | sed -n "s/.*icon='\([^']*\)'.*/\1/p" | head -n 1)

        case "$icon_path" in
            *.xml)
                if "$AAPT" dump xmltree "$apk_path" --file "$icon_path" 2>/dev/null | grep -q -i -E "monochrome|themed_icon"; then
                    label=$(echo "$output" | grep "application-label:" | head -n 1 | sed "s/.*:'//; s/'.*//")
                    [ -z "$label" ] && label="$pkg_name"
                    echo "${pkg_name}|${label}" >> "$SCAN_RESULT"
                    FOUND=$((FOUND + 1))
                fi
                ;;
        esac
    done < "$TARGET_LIST"

    # Final Update
    write_js "$TOTAL" "$CURRENT" "$FOUND" "Completed" "扫描完成" "done"
    
    # Cleanup
    rm -f "$SKIP_FILE" "$RAW_MAP" "$TARGET_LIST"
}

scan_paths() {
    # Scan known uxicons directories and output path|count per line
    # These are the standard ColorOS themed icon paths
    # Note: icon_path config is loaded separately by the WebUI's loadPaths()
    KNOWN_PATHS="
        ${MOD_ROOT}/data/oplus/uxicons
        ${MOD_ROOT}/my_product/media/theme/uxicons/hdpi
    "

    # Deduplicate and scan each path
    echo "$KNOWN_PATHS" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$' | sort -u | while read -r dir; do
        if [ -d "$dir" ]; then
            count=$(find "$dir" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l)
            echo "${dir}|${count}"
        fi
    done
}

clean_icon() {
    DELETE_LIST="${CACHE_ROOT}/delete_packages.txt"
    # Fallback to pkglist if delete list doesn't exist (assuming full sync mode? or check logic)
    # The original clean_icon used PKGLIST directly for blacklist mode logic
    
    if [ ! -f "$PKGLIST_FILE" ]; then
        echo ">>> pkglist 不存在，跳过清理。"
        return
    fi

    # Read default icon paths
    TARGET_A="${MOD_ROOT}/data/oplus/uxicons/"
    TARGET_B="${MOD_ROOT}/my_product/media/theme/uxicons/hdpi/"

    echo ">>> 根据 pkglist 清理 (还原系统图标)..."
    
    # Logic: Read pkglist, remove those folders from module to let system icon show through
    cat "$PKGLIST_FILE" | tr -d '\r' | grep -v '^$' | while read -r pkg; do
        [ -z "$pkg" ] && continue
        
        # Remove from Target A
        if [ -d "$TARGET_A/$pkg" ]; then
            rm -rf "$TARGET_A/$pkg"
            echo "   已还原: $pkg"
        fi
        
        # Remove from Target B
        if [ -d "$TARGET_B/$pkg" ]; then
            rm -rf "$TARGET_B/$pkg"
            echo "   已还原: $pkg"
        fi
    done
    echo ">>> 清理完成。"
}

update() {
    NEW_VERSION="$1"
    
    # 临时缓存目录 (Assume files are already downloaded here by WebUI)
    # ZIP_FILE="$CACHE_ROOT/uxicons.zip"
    # WEBUI_ZIP="$CACHE_ROOT/webui.zip"
    
    echo ">>> 开始更新流程..."
    
    # 1. Unzip Icons
    if [ -f "$CACHE_ROOT/uxicons.zip" ]; then
        echo ">>> 解压图标包..."
        unzip -o "$CACHE_ROOT/uxicons.zip" -d "$CACHE_ROOT" > /dev/null 2>&1
        if [ $? -ne 0 ]; then
            echo "错误: 解压失败"
            rm -f "$CACHE_ROOT/uxicons.zip"
            exit 1
        fi
        
        # Merge Icons
        TARGET_A="${MOD_ROOT}/data/oplus/uxicons/"
        TARGET_B="${MOD_ROOT}/my_product/media/theme/uxicons/hdpi/"
        mkdir -p "$TARGET_A" "$TARGET_B"
        
        if [ -d "$CACHE_ROOT/uxicons" ]; then
             cp -rf "$CACHE_ROOT/uxicons/"* "$TARGET_A"
             cp -rf "$CACHE_ROOT/uxicons/"* "$TARGET_B"
        fi
        
        # 修复由于 umask 导致的 700 权限问题，避免挂载后系统无法读取开机动画等文件
        find "${MOD_ROOT}/my_product" -type d -exec chmod 755 {} + 2>/dev/null
        find "${MOD_ROOT}/data/oplus" -type d -exec chmod 755 {} + 2>/dev/null
        find "${MOD_ROOT}/my_product" -type f -exec chmod 644 {} + 2>/dev/null
        find "${MOD_ROOT}/data/oplus" -type f -exec chmod 644 {} + 2>/dev/null
        
        # 修复 SELinux 上下文标签，这是导致新文件即重启也无法被系统读取的核心原因
        chcon -R u:object_r:system_file:s0 "${MOD_ROOT}/my_product" 2>/dev/null
        chcon -R u:object_r:system_file:s0 "${MOD_ROOT}/data" 2>/dev/null

        # === 核心热更新修复机制 ===
        # 将新增加的文件直接写向真实的合并层挂载环境，越出底层不可见的屏障
        cp -rf "$CACHE_ROOT/uxicons/"* "/data/oplus/uxicons/" 2>/dev/null
        mount -o rw,remount /my_product 2>/dev/null
        cp -rf "$CACHE_ROOT/uxicons/"* "/my_product/media/theme/uxicons/hdpi/" 2>/dev/null
        
        chmod -R 755 "/data/oplus/uxicons" 2>/dev/null
        chmod -R 755 "/my_product/media/theme/uxicons/hdpi/" 2>/dev/null
        chcon -R u:object_r:system_file:s0 "/data/oplus/uxicons" 2>/dev/null
        chcon -R u:object_r:system_file:s0 "/my_product/media/theme/uxicons/hdpi/" 2>/dev/null
    fi
    
    # 2. Unzip WebUI
    if [ -f "$CACHE_ROOT/webui.zip" ]; then
        echo ">>> 更新 WebUI..."
        unzip -o "$CACHE_ROOT/webui.zip" -d "$MOD_ROOT" > /dev/null 2>&1
    fi
    
    # 3. Update Version
    if [ -n "$NEW_VERSION" ]; then
        echo "$NEW_VERSION" > "$VERSION_FILE"
    fi
    
    # 4. Cleanup (Safe)
    echo ">>> 清理临时文件..."
    if [ -d "$CACHE_ROOT" ]; then
        # Delete everything EXCEPT scan result if strictly needed, 
        # BUT since scan result moves to webroot, we can just wipe cache?
        # User said "moneticon_apps" should be in webroot.
        # So it is safe to rm -rf cache root? Yes.
        rm -rf "$CACHE_ROOT"
    fi
    
    # 5. Restore user preferences (Clean ignored icons)
    clean_icon
    
    echo ">>> 全部完成 (Success)"
}


case "$1" in
    "scan_monet")
        scan_monet
        ;;
    "scan_paths")
        scan_paths
        ;;
    "clean_icon")
        clean_icon
        ;;
    "update")
        update "$2"
        ;;
    *)
        echo "Usage: $0 {scan_monet|scan_paths|clean_icon|update <ver>}"
        exit 1
        ;;
esac
