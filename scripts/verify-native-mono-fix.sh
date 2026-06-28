#!/usr/bin/env bash
set -euo pipefail

MODULE_PKG="com.oplusmonet.nativemonofix"
DEFAULT_LAUNCHER="com.android.launcher"
CHECK_PACKAGES="${CHECK_PACKAGES:-com.resukisu.resukisu com.limelight}"
WORK_DIR="${WORK_DIR:-/tmp/oplusmonet-native-mono-check}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing command: $1" >&2
    exit 1
  fi
}

adb_su() {
  adb shell "su -c '$*'"
}

adb_su_out() {
  adb exec-out su -c "$*"
}

section() {
  printf '\n== %s ==\n' "$1"
}

quote_sql_like() {
  printf "%s" "$1" | sed "s/'/''/g"
}

require_cmd adb
require_cmd sqlite3
require_cmd unzip

mkdir -p "$WORK_DIR"

section "Device"
adb get-state
adb_su "id; getenforce; cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1"

section "Installed hook APK"
PM_PATH="$(adb shell "pm path $MODULE_PKG 2>/dev/null" | tr -d '\r' | sed 's/^package://' | head -n 1 || true)"
MODULE_APK="$WORK_DIR/native-mono-fix.apk"
STATIC_SCOPE="unknown"
STATIC_SCOPE_LAUNCHER="unknown"
if [ -z "$PM_PATH" ]; then
  echo "not installed: $MODULE_PKG"
else
  echo "pm_path=$PM_PATH"
  adb_su "dumpsys package $MODULE_PKG | grep -E \"versionCode|versionName|lastUpdateTime\" -A2"
  if adb_su_out "cat '$PM_PATH'" > "$MODULE_APK" 2>/dev/null; then
    MODULE_PROP="$(unzip -p "$MODULE_APK" META-INF/xposed/module.prop 2>/dev/null || true)"
    SCOPE_LIST="$(unzip -p "$MODULE_APK" META-INF/xposed/scope.list 2>/dev/null || true)"
    printf '%s\n' "$MODULE_PROP" | sed '/^$/d'
    printf '%s\n' "$SCOPE_LIST" | sed 's/^/scope.list: /'
    if printf '%s\n' "$MODULE_PROP" | grep -qx 'staticScope=true'; then
      STATIC_SCOPE="1"
    else
      STATIC_SCOPE="0"
    fi
  fi
fi

section "LSPosed database"
LSPD_DB="$WORK_DIR/modules_config.db"
if adb_su_out "cat /data/adb/lspd/config/modules_config.db" > "$LSPD_DB" 2>/dev/null; then
  sqlite3 "$LSPD_DB" \
    "select 'module', module_pkg_name, apk_path from modules where module_pkg_name='$MODULE_PKG';
     select 'state', module_pkg_name, user_id, enabled, scope_request_blocked from modules_state where module_pkg_name='$MODULE_PKG';
     select 'scope', module_pkg_name, app_pkg_name, user_id from scope where module_pkg_name='$MODULE_PKG' order by app_pkg_name;"

  STORED_PATH="$(sqlite3 "$LSPD_DB" "select apk_path from modules where module_pkg_name='$MODULE_PKG';" | head -n 1 || true)"
  if [ -n "$PM_PATH" ] && [ -n "$STORED_PATH" ] && [ "$PM_PATH" != "$STORED_PATH" ]; then
    echo "warning: LSPosed still records a stale APK path."
    echo "current_path=$PM_PATH"
    echo "stored_path=$STORED_PATH"
  fi

  LAUNCHER="$(adb shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1" | tr -d '\r' | sed 's#/.*##')"
  [ -z "$LAUNCHER" ] && LAUNCHER="$DEFAULT_LAUNCHER"
  if [ "$STATIC_SCOPE" = "1" ] && printf '%s\n' "${SCOPE_LIST:-}" | grep -qx "$LAUNCHER"; then
    STATIC_SCOPE_LAUNCHER="1"
  elif [ "$STATIC_SCOPE" = "1" ]; then
    STATIC_SCOPE_LAUNCHER="0"
  fi
  SCOPE_COUNT="$(sqlite3 "$LSPD_DB" "select count(*) from scope where module_pkg_name='$MODULE_PKG' and app_pkg_name='$LAUNCHER' and user_id=0;")"
  if [ "$SCOPE_COUNT" = "0" ] && [ "$STATIC_SCOPE_LAUNCHER" = "1" ]; then
    echo "launcher_static_scope=$LAUNCHER"
  elif [ "$SCOPE_COUNT" = "0" ]; then
    echo "warning: launcher scope is not saved for $LAUNCHER."
  else
    echo "launcher_scope_saved=$LAUNCHER"
  fi
else
  echo "could not read /data/adb/lspd/config/modules_config.db"
fi

section "Recent hook logs"
adb_su "grep -R -a -i \"OplusNativeMonoFix\|native APK mono\|native APK monochrome\|suppressed ColorOS\|disabled ColorOS\|forced native themed\|bypassed local special\|hooked .*#\|loading .*uxicon\" /data/adb/lspd/log 2>/dev/null | tail -n 120" || true
adb_su "logcat -d -t 30000 | grep -iE \"OplusNativeMonoFix|native APK mono|native APK monochrome|suppressed ColorOS|disabled ColorOS|forced native themed|bypassed local special|hooked .*#|loading .*uxicon\" | tail -n 120" || true

section "Launcher icon database"
LAUNCHER_DB="$WORK_DIR/app_icons.db"
if adb_su_out "cat /data/user_de/0/$DEFAULT_LAUNCHER/databases/app_icons.db" > "$LAUNCHER_DB" 2>/dev/null; then
  WHERE=""
  for pkg in $CHECK_PACKAGES; do
    pattern="$(quote_sql_like "$pkg")"
    if [ -z "$WHERE" ]; then
      WHERE="componentName like '%$pattern%'"
    else
      WHERE="$WHERE or componentName like '%$pattern%'"
    fi
  done
  sqlite3 "$LAUNCHER_DB" \
    "select componentName, length(icon), length(mono_icon), label from icons where $WHERE order by componentName;
     select count(*) total, sum(case when mono_icon is not null then 1 else 0 end) with_mono from icons;"
else
  echo "could not read launcher app_icons.db"
fi

section "Expected next step"
echo "If no hook logs appear, open LSPosed, enable ColorOS Native Mono Fix, keep the static recommended system icon scopes, then restart launcher or reboot."
