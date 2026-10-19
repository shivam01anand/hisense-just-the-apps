#!/bin/sh
# Puts your setup back after a firmware update or a factory reset. Safe to run any time: it checks each
# item and changes only what has drifted, then says what it did.
#
#   tools/reapply.sh            fix whatever has drifted
#   tools/reapply.sh --check    report only, change nothing
#
# It reads, all from my-tv/:
#   debloat.txt     packages to keep switched off
#   settings.txt    lines of "namespace key value", e.g. "global private_dns_mode hostname"
#   config.json     the home screen's dock, watchlist and buttons
#   apks/<package>.apk   apps to reinstall if missing (e.g. apks/com.stremio.one.apk)
#   remove.txt      apps to uninstall if they come back
. "$(dirname "$0")/lib.sh"
CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1
DRIFT=0
FIXED=0

drift() {
  what=$1
  shift
  DRIFT=$((DRIFT + 1))
  if [ "$CHECK" = 1 ]; then echo "  DRIFT  $what"
  elif "$@" >/dev/null 2>&1; then FIXED=$((FIXED + 1)); echo "  fixed  $what"
  else echo "  FAILED $what"
  fi
}
ok() { echo "  ok     $1"; }

connect
device=$(tv getprop ro.product.device)
if [ -n "${DEVICE:-}" ] && [ "$device" != "$DEVICE" ]; then
  echo "This TV is '$device', but my-tv/tv.env is for '$DEVICE'. Stopping." >&2
  exit 1
fi
echo "== $(tv getprop ro.product.manufacturer) $device, firmware $(tv getprop ro.build.version.incremental)"

installed=$(tv pm list packages)
has() { printf '%s\n' "$installed" | grep -qx "package:$1"; }

echo "== apps"
APK="$REPO/launcher/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  want=$(shasum -a 256 "$APK" | cut -c1-64)
  have=""
  has $LAUNCHER && have=$(tv "sha256sum \$(pm path $LAUNCHER | sed s/package://)" | cut -c1-64)
  if [ "$want" = "$have" ]; then ok "home screen app is the current build"
  else drift "home screen app missing or out of date" adb -s "$TV" install -r "$APK"; fi
elif ! has $LAUNCHER; then
  echo "  The home screen app is not installed and not built yet: run tools/install-home.sh"
fi
if [ -d "$MY/apks" ]; then
  for f in "$MY"/apks/*.apk; do
    [ -f "$f" ] || continue
    p=$(basename "$f" .apk)
    if has "$p"; then ok "$p installed"; else drift "$p missing" adb -s "$TV" install "$f"; fi
  done
fi
if [ -f "$MY/remove.txt" ]; then
  for p in $(list_packages "$MY/remove.txt"); do
    has "$p" && drift "$p came back" adb -s "$TV" uninstall "$p"
  done
fi
installed=$(tv pm list packages)

if has $LAUNCHER; then
  # The new home must be running before the stock one is switched off.
  [ "$CHECK" = 1 ] || tv am start -W -n $LAUNCHER/.MainActivity >/dev/null
  tv pm grant $LAUNCHER android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1
fi

if [ -f "$MY/debloat.txt" ]; then
  echo "== switched-off packages"
  off=$(tv pm list packages -d)
  n=0
  for p in $(list_packages "$MY/debloat.txt"); do
    has "$p" || continue
    if printf '%s\n' "$off" | grep -qx "package:$p"; then n=$((n + 1)); continue; fi
    case "$p" in com.google.android.tvlauncher|com.google.android.apps.tv.launcherx)
      has $LAUNCHER || { echo "  SKIP   $p stays on until the new home screen is installed"; continue; } ;;
    esac
    drift "$p is switched on again" tv pm disable-user --user 0 "$p"
  done
  ok "$n already switched off"
fi

if has $LAUNCHER; then
  home=$(home_activity)
  case "$home" in
    $LAUNCHER/*) ok "Home opens the new home screen" ;;
    *) drift "Home opens $home" tv cmd package set-home-activity --user 0 $LAUNCHER/.MainActivity
       if [ "$CHECK" = 0 ]; then
         case "$(home_activity)" in
           $LAUNCHER/*) ;;
           *) tv pm enable --user 0 com.google.android.tvlauncher >/dev/null 2>&1
              echo "  Home still not on the new home screen: stock home switched back on so the TV stays usable." ;;
         esac
       fi ;;
  esac
fi

if [ -f "$MY/settings.txt" ]; then
  echo "== settings"
  # Read with a redirect, not a pipe: a piped loop runs in a subshell and its drift count would be lost.
  while read -r ns key value _; do
    case "$ns" in ''|\#*) continue ;; esac
    cur=$(tv settings get "$ns" "$key")
    if [ "$cur" = "$value" ]; then ok "$key = $value"
    else drift "$key is $cur, want $value" tv settings put "$ns" "$key" "$value"; fi
  done < "$MY/settings.txt"
fi

if [ -f "$MY/config.json" ] && has $LAUNCHER; then
  echo "== home screen settings"
  if [ "$(tv cat /sdcard/Android/data/$LAUNCHER/files/config.json 2>/dev/null)" = "$(cat "$MY/config.json")" ]; then
    ok "config.json on the TV matches my-tv/config.json"
  else
    drift "config.json missing or different" sh -c "adb -s '$TV' push '$MY/config.json' /sdcard/Android/data/$LAUNCHER/files/config.json && adb -s '$TV' shell -n am force-stop $LAUNCHER"
  fi
fi

echo
if [ "$DRIFT" = 0 ]; then echo "All good: nothing has drifted (settings are listed above)."
elif [ "$CHECK" = 1 ]; then echo "$DRIFT item(s) have drifted. Run without --check to fix them."
else echo "$DRIFT item(s) had drifted, $FIXED fixed. Press Home on the remote."; fi
