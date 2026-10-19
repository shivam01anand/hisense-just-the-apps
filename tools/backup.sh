#!/bin/sh
# Saves what is on the TV before anything changes: every package, which are already switched off, device
# details and all settings. Also writes undo.sh, which switches back on everything this setup turns off.
# Usage: tools/backup.sh
. "$(dirname "$0")/lib.sh"
connect
OUT="$MY/backup-$(date +%Y%m%d-%H%M)"
mkdir -p "$OUT"
tv pm list packages -f > "$OUT/packages_all.txt"
tv pm list packages -s > "$OUT/packages_system.txt"
tv pm list packages -3 > "$OUT/packages_installed_by_you.txt"
tv pm list packages -d > "$OUT/packages_off_before.txt"
tv pm list packages -e > "$OUT/packages_on_before.txt"
for p in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release \
         ro.build.version.incremental ro.build.version.security_patch ro.build.date; do
  echo "$p=$(tv getprop $p)"
done > "$OUT/device.txt"
for ns in global secure system; do tv settings list $ns > "$OUT/settings_$ns.txt"; done
cat > "$OUT/undo.sh" <<'UNDO'
#!/bin/sh
# Switches back on every package that was on when this backup was taken.
REPO=$(cd "$(dirname "$0")/../.." && pwd)
. "$REPO/tools/lib.sh"
connect
off=$(tv pm list packages -d)
sed 's/^package://' "$(dirname "$0")/packages_on_before.txt" | while read -r p; do
  printf '%s\n' "$off" | grep -qx "package:$p" && tv pm enable --user 0 "$p" >/dev/null && echo "switched on $p"
done
echo "Done. If the TV asks which home screen to use, pick the one you want."
UNDO
chmod +x "$OUT/undo.sh"
echo "Saved to $OUT"
cat "$OUT/device.txt"
echo "$(wc -l < "$OUT/packages_all.txt" | tr -d ' ') packages, $(wc -l < "$OUT/packages_installed_by_you.txt" | tr -d ' ') installed by you"
