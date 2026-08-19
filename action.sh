#!/system/bin/sh

MODDIR="${0%/*}"
SCRIPT_FILE="${MODDIR}/moneticons_plus.sh"
LOG_DIR="/data/adb/moneticon_tmp"
LOG_FILE="${LOG_DIR}/action_icon_cache_refresh.log"

mkdir -p "$LOG_DIR" 2>/dev/null

log() {
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG_FILE"
}

log "manual icon cache refresh start"

if [ ! -f "$SCRIPT_FILE" ]; then
    log "script missing: $SCRIPT_FILE"
    echo "错误: 未找到 ${SCRIPT_FILE}"
    exit 1
fi

TMP_LOG="${LOG_DIR}/action_icon_cache_refresh.tmp"
sh "$SCRIPT_FILE" clear_launcher_icon_cache > "$TMP_LOG" 2>&1
rc=$?
cat "$TMP_LOG"
cat "$TMP_LOG" >> "$LOG_FILE"
rm -f "$TMP_LOG"

if [ "$rc" -eq 0 ]; then
    log "manual icon cache refresh finished"
    echo ">>> 图标缓存刷新完成。"
else
    log "manual icon cache refresh failed rc=$rc"
    echo "错误: 图标缓存刷新失败。"
fi

exit "$rc"
