#!/bin/sh
# Switches back on the packages in a debloat list, or in one group of it.
# Usage: tools/undo-list.sh my-tv/debloat.txt [group]
. "$(dirname "$0")/lib.sh"
LIST=${1:?usage: undo-list.sh <list> [group]}
connect
if [ -n "${2:-}" ]; then
  pkgs=$(awk -v g="== $2:" 'index($0,g){f=1;next} /^# ==/{f=0} f && !/^#/ && NF{print $1}' "$LIST")
else
  pkgs=$(list_packages "$LIST")
fi
for p in $pkgs; do echo "  $p: $(tv pm enable --user 0 "$p" 2>&1 | tail -n 1)"; done
