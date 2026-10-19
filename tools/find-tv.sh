#!/bin/sh
# Finds Android TVs (and other Cast devices) on this Wi-Fi, so nobody has to look up an IP address.
#
# Two ways in, both on the local network only:
#   - port 8008, which Android TVs with Chromecast built-in answer even before USB debugging is on; it also
#     gives the TV's name
#   - port 5555, which opens once USB debugging is on (network ADB)
# Prints one line per TV: IP, whether ADB is open, and the TV's name when it has one.
# Usage: tools/find-tv.sh

case "$(uname)" in
  Darwin) gw=$(route -n get default 2>/dev/null | awk '/gateway/{print $2}') ;;
  *) gw=$(ip route 2>/dev/null | awk '/^default/{print $3; exit}') ;;
esac
case "$gw" in
  *.*.*.*) prefix=${gw%.*} ;;
  *) echo "Could not work out this network (no default gateway). Are you on Wi-Fi?" >&2; exit 1 ;;
esac

probe() {  # ip port -> exit 0 when open
  if [ "$(uname)" = Darwin ]; then nc -z -G 1 "$1" "$2" >/dev/null 2>&1; else nc -z -w 1 "$1" "$2" >/dev/null 2>&1; fi
}

tmp=$(mktemp -d)
echo "Looking for TVs on $prefix.0/24 ..." >&2
i=1
while [ $i -le 254 ]; do
  ip="$prefix.$i"
  ( probe "$ip" 8008 && touch "$tmp/$ip.cast"; probe "$ip" 5555 && touch "$tmp/$ip.adb" ) &
  # a few dozen at a time is quick and gentle on home routers
  [ $((i % 48)) -eq 0 ] && wait
  i=$((i + 1))
done
wait

found=0
for ip in $(ls "$tmp" 2>/dev/null | sed -E 's/\.(cast|adb)$//' | sort -u -t. -k4,4n); do
  name=""
  if [ -f "$tmp/$ip.cast" ]; then
    name=$(curl -s -m 3 "http://$ip:8008/setup/eureka_info?params=name" | sed -nE 's/.*"name": *"([^"]*)".*/\1/p')
  fi
  adb_state="adb closed (USB debugging off)"
  [ -f "$tmp/$ip.adb" ] && adb_state="adb open"
  printf '%s\t%s\t%s\n' "$ip" "$adb_state" "${name:-unknown name}"
  found=$((found + 1))
done
rm -rf "$tmp"
[ "$found" -gt 0 ] || { echo "No Android TV found. Is the TV on and on the same Wi-Fi as this laptop?" >&2; exit 1; }
