#!/system/bin/sh

[ -f "$TMPDIR/companion-installer.sh" ] \
  || abort "未找到安装脚本"
. "$TMPDIR/companion-installer.sh"
