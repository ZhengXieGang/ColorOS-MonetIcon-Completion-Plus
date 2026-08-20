#!/system/bin/sh

[ -f "$MODPATH/common/companion-installer.sh" ] \
  || abort "未找到安装脚本"
. "$MODPATH/common/companion-installer.sh"
