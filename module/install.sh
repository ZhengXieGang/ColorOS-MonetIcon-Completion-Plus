#!/system/bin/sh

TARGET_MODULE_ID="ThemedIconCompletion"
TARGET_MODULE_DIR="/data/adb/modules/${TARGET_MODULE_ID}"

print_modname() {
  ui_print "ColorOS Monet Icon Completion Plus"
}

abort_install() {
  abort "安装失败: $1"
}

extract_package() {
  unzip -o "$ZIPFILE" -d "$MODPATH" >&2 || abort_install "解压伴生包失败"
}

copy_path() {
  src="$1"
  dst="$2"
  [ -e "$src" ] || return 0
  mkdir -p "$(dirname "$dst")"
  cp -af "$src" "$dst" || abort_install "复制 ${src} 失败"
}

on_install() {
  ui_print "- 检查原模块"
  [ -d "$TARGET_MODULE_DIR" ] || abort_install "未找到 ${TARGET_MODULE_DIR}，请先安装原模块"

  ui_print "- 解压 WebUI 和 action.sh"
  extract_package

  ui_print "- 复制到原模块目录"
  mkdir -p "$TARGET_MODULE_DIR/webroot"
  copy_path "$MODPATH/webui/moneticons_plus.sh" "$TARGET_MODULE_DIR/moneticons_plus.sh"
  if [ -d "$MODPATH/webui/webroot" ]; then
    cp -af "$MODPATH/webui/webroot/." "$TARGET_MODULE_DIR/webroot/" \
      || abort_install "复制 webroot 失败"
  fi

  ui_print "- 写入快捷操作脚本"
  copy_path "$MODPATH/action.sh" "$TARGET_MODULE_DIR/action.sh"

  ui_print "- 完成"
}

set_permissions() {
  set_perm_recursive "$TARGET_MODULE_DIR/webroot" 0 0 0755 0644
  set_perm "$TARGET_MODULE_DIR/moneticons_plus.sh" 0 0 0755
  set_perm "$TARGET_MODULE_DIR/action.sh" 0 0 0755

  rm -rf "$MODPATH/action.sh" "$MODPATH/webui" "$MODPATH/install.sh"
}
