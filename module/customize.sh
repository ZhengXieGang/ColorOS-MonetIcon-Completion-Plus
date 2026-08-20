#!/system/bin/sh

[ -f "$MODPATH/install.sh" ] \
  || abort "未找到安装脚本"
. "$MODPATH/install.sh"
