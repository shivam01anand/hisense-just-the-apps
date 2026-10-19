#!/bin/sh
# Switches off one group of packages from a debloat list, restarts the TV and checks it came back healthy:
# booted, still switched off, Home works, HDMI/AV/tuner inputs still listed, keyboard set.
# Groups are marked in the list with a line like "# == group1: telemetry".
# Usage: tools/disable-group.sh my-tv/debloat.txt group1
. "$(dirname "$0")/lib.sh"
LIST=${1:?usage: disable-group.sh <list> <group>}
GROUP=${2:?usage: disable-group.sh <list> <group>}
connect
pkgs=$(awk -v g="== $GROUP:" 'index($0,g){f=1;next} /^# ==/{f=0} f && !/^#/ && NF{print $1}' "$LIST")
[ -n "$pkgs" ] || { echo "no packages under '# == $GROUP:' in $LIST" >&2; exit 1; }
inputs_before=$(tv dumpsys tv_input | grep -oE 'TvInputService/HW[0-9]+' | sort -u | wc -l | tr -d ' ')
for p in $pkgs; do echo "  $p: $(tv pm disable-user --user 0 "$p")"; done
sleep 15   # a reboot straight after pm disable-user can lose the change on Hisense firmware
adb -s "$TV" reboot
echo "  restarting the TV..."
sleep 30
booted=""
for _ in $(seq 1 60); do
  adb connect "$TV" >/dev/null 2>&1
  booted=$(adb -s "$TV" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$booted" = "1" ] && break
  sleep 5
done
[ "$booted" = "1" ] || { echo "THE TV DID NOT COME BACK. If it shows Android Recovery, pick 'Try again', never 'Factory data reset'."; exit 1; }
sleep 25
echo "  booted"
off=$(tv pm list packages -d)
for p in $pkgs; do printf '%s\n' "$off" | grep -qx "package:$p" && echo "  ok    $p off" || echo "  !!    $p is ON again"; done
echo "  home: $(home_activity)"
inputs=$(tv dumpsys tv_input | grep -oE 'TvInputService/HW[0-9]+' | sort -u | wc -l | tr -d ' ')
echo "  inputs: $inputs (before: $inputs_before)"
echo "  keyboard: $(tv settings get secure default_input_method)"
[ "$inputs" -lt "$inputs_before" ] && echo "  !! fewer inputs than before: undo this group with tools/undo-list.sh $LIST $GROUP"
exit 0
