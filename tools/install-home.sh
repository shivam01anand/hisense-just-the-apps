#!/bin/sh
# Builds the home screen app and makes it the TV's home, the safe way round: install it, start it, only then
# switch off the stock home, set the default, and switch the stock home back on if Home does not land on the
# new one. Also pushes my-tv/config.json (dock, watchlist, buttons) and sets the clock screensaver.
#
# Needs JDK 17 and the Android SDK platform 34. On a Mac:
#   brew install openjdk@17 android-commandlinetools
#   sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-34" "build-tools;34.0.0"
# Usage: tools/install-home.sh
. "$(dirname "$0")/lib.sh"
connect

cd "$REPO/launcher" || exit 1
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || { [ -d /opt/homebrew/opt/openjdk@17 ] && echo /opt/homebrew/opt/openjdk@17; })}"
export ANDROID_HOME="${ANDROID_HOME:-$(for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools; do [ -d "$d/platforms" ] && echo "$d" && break; done)}"
[ -n "$ANDROID_HOME" ] || { echo "Android SDK not found; see the top of this script." >&2; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "== building"
./gradlew assembleRelease -q --console=plain || exit 1
APK="$REPO/launcher/app/build/outputs/apk/release/app-release.apk"

echo "== installing"
out=$(adb -s "$TV" install -r "$APK" 2>&1 | tail -n 1)
echo "  $out"
case "$out" in *Success*) ;; *) echo "Install failed; nothing else was changed." >&2; exit 1 ;; esac
tv pm grant $LAUNCHER android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1
if [ -f "$MY/config.json" ]; then
  adb -s "$TV" push "$MY/config.json" /sdcard/Android/data/$LAUNCHER/files/config.json >/dev/null && echo "  pushed my-tv/config.json"
  tv am force-stop $LAUNCHER
fi
tv cmd package compile -m speed -f $LAUNCHER >/dev/null
tv am start -W -n $LAUNCHER/.MainActivity | grep -E '^(Status|Error)'

echo "== making it Home"
stock=$(home_activity | cut -d/ -f1)
case "$stock" in
  $LAUNCHER) echo "  already Home" ;;
  com.google.android.tvlauncher|com.google.android.apps.tv.launcherx|com.google.android.leanbacklauncher)
    echo "  switching off the stock home ($stock)"
    tv pm disable-user --user 0 "$stock" >/dev/null ;;
  *) echo "  Home currently resolves to '$stock'; leaving it switched on. Pick the new home on the TV if asked."
     stock="" ;;
esac
tv cmd package set-home-activity --user 0 $LAUNCHER/.MainActivity >/dev/null
now=$(home_activity)
case "$now" in
  $LAUNCHER/*) echo "  Home is now the new home screen" ;;
  *) [ -n "$stock" ] && tv pm enable --user 0 "$stock" >/dev/null
     echo "  Home went to '$now', so the stock home was switched back on. Nothing is broken." >&2; exit 1 ;;
esac

# Without any screensaver this firmware puts the TV to sleep after 5 idle minutes instead.
tv settings put secure screensaver_components $LAUNCHER/.ClockDream
tv settings put secure screensaver_enabled 1
tv input keyevent KEYCODE_HOME
echo "Done. Press Home on the remote."
