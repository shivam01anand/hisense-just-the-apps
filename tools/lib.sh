# Shared by the tools. Your TV's details live in my-tv/tv.env (never committed):
#   TV_HOST=192.168.1.50
# Everything personal (backups, your debloat list, config.json, extra APKs) lives in my-tv/ too.

REPO=${REPO:-$(cd "$(dirname "$0")/.." && pwd)}   # scripts outside tools/ set REPO first
MY="$REPO/my-tv"
[ -f "$MY/tv.env" ] && . "$MY/tv.env"
TV="${TV_HOST:?set TV_HOST in my-tv/tv.env, e.g. TV_HOST=192.168.1.50}:5555"
LAUNCHER=com.gathin.tvhome

# One shell command on the TV. -n: never read the caller's stdin, or a while-read loop loses its lines.
tv() { adb -s "$TV" shell -n "$@" | tr -d '\r'; }

# Connects and waits for the TV to accept this computer. Exits if it cannot.
connect() {
  adb connect "$TV" >/dev/null 2>&1
  state=""
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
    state=$(adb devices | grep "^$TV" | awk '{print $2}')
    [ "$state" = "device" ] && return 0
    [ "$state" = "unauthorized" ] && echo "  On the TV: accept 'Allow USB debugging?' and tick Always allow."
    sleep 5
    adb connect "$TV" >/dev/null 2>&1
  done
  echo "Cannot reach the TV at $TV ($state). Is it on, on the same Wi-Fi, with USB debugging on?" >&2
  exit 1
}

# Package names from a debloat list: one per line, # comments allowed.
list_packages() { grep -vE '^[[:space:]]*(#|$)' "$1" | sed -E 's/[[:space:]]+#.*$//' | awk '{print $1}'; }

home_activity() { tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1; }
