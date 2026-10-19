# The prompt

Paste this into [Claude Code](https://claude.com/claude-code) (or another assistant that can run commands on
your laptop), opened inside this folder. The laptop must be on the same Wi-Fi as the TV.

**On the TV first, two minutes:**

1. Settings → Device Preferences → About. Check it says Android TV. (If it says VIDAA, this won't work.)
2. Press OK on **Build** seven times until it says you're a developer.
3. Back → Developer options → turn on **USB debugging**.
4. Settings → Network & Internet → your Wi-Fi: note the IP address, something like 192.168.1.20.

Then fill in the three lines at the top and paste everything below the line.

---

My TV's IP address: <192.168.1.20>
The apps I actually use: <for example Netflix, YouTube, Prime Video, Hotstar, Stremio>
My IMDb watchlist link (optional): <paste it, or leave blank>

I have a Hisense Android TV. I want it to start and move faster, with no ads on the home screen and only my
apps. You are in a clone of hisense-just-the-apps: use its `tools/`, `launcher/` and `devices/` rather than
writing your own. Work on the real TV one step at a time, check each step (screenshots from the TV with
`adb exec-out screencap -p`, or `uiautomator dump` when an app blocks screenshots), and ask me before
anything that depends on how I use the TV. Keep everything about my TV in `my-tv/`.

**1. Connect**
- Install adb if it's missing (macOS: `brew install --cask android-platform-tools`).
- Write `my-tv/tv.env` with `TV_HOST=<ip>` and `DEVICE=` the value of `getprop ro.product.device`.
- Connect on port 5555 and tell me the moment to accept "Allow USB debugging?" on the TV (Always allow).
  If the prompt never shows, ask me to switch USB debugging off and on again.

**2. Back up** with `tools/backup.sh`. Change nothing until it has succeeded.

**3. Make my list**
- If `devices/<my device>/debloat.txt` exists, start from it and read its `NOTES.md`. If not, sort
  `pm list packages -f` into telemetry, ad and recommendation feeds, shop demos, silent partner installers,
  unused preloads and background services. Check every unfamiliar Hisense, MediaTek or MStar package with
  `dumpsys package` (its services and receivers) before including it; ask me if it's still unclear.
- Never include: `com.hisense.tv.smartsense`, `com.hisense.tv.smartservice`, `com.vt.tv.smartsense.ui`
  (this family sent a Hisense into an Android Recovery loop), `com.hisense.tv.hitvinput`, any `com.mediatek.*`,
  `com.hisense.kpad`, `com.mstar.netflixobserver`, `com.hisense.tv.customerlauncher`,
  `com.hisense.remoteupgrade`, Play Services, Play Store, the keyboard, voice search or WebView.
- Protect what my apps depend on. For each app I named, check how it was installed
  (`installerPackageName`; a sideloaded app needs its installer app kept) and what it uses (WebView, Play
  Services, a browser for sign-in pages). Keep all of those.
- Ask me with one checklist before touching: streaming apps I didn't name, Chromecast, the voice assistant,
  screen mirroring, DLNA, the USB media player and firmware updates.
- Show me the final list in groups with a one-line reason each, and wait for my OK. Save it as
  `my-tv/debloat.txt` with the same `# == groupN:` markers.

**4. Measure, then switch off in groups**
- Before changing anything: on the home screen, run `dumpsys gfxinfo <home package> reset`, send the same
  25 D-pad presses, and read the janky-frame percentage. Save `dumpsys meminfo` too.
- Run `tools/disable-group.sh my-tv/debloat.txt group1`, then group2, group3 and so on. It restarts the TV
  and checks it after each group. If anything looks wrong, stop and undo with `tools/undo-list.sh`.
- Leave the stock home screen and Ambient Mode (group 4 in the tested list) for step 5.

**5. New home screen**
- Write `my-tv/config.json`: `dock_apps` (ask me which four, in order), `show_inputs_pill` (ask whether I
  use a set-top box or anything else on HDMI), `show_network_pill: false`. If I gave an IMDb link, run
  `tools/watchlist-preview.py "<link>"`, show me the summary, and add `watchlist_imdb_user` and
  `watchlist_country`. If I don't use Stremio, say that titles on none of my apps won't open anywhere.
- Install JDK 17 and the Android SDK if needed (see the top of `tools/install-home.sh`), then run
  `tools/install-home.sh`. If your safety rules stop you installing an app you built, give me the exact
  command to run myself.
- Then switch off the stock home and Ambient Mode, and any optional groups I agreed to. Press Home, open
  each dock app, try the Inputs menu if it's on, and focus a watchlist poster and open it.

**6. Extras, ask me first**
- Ad-blocking DNS: `settings put global private_dns_specifier dns.adguard-dns.com`, then
  `settings put global private_dns_mode hostname`. Tell me it doesn't block YouTube ads and that AdGuard sees
  every address the TV looks up. Afterwards, check my apps' main domains still resolve.
- Faster menus: `window_animation_scale`, `transition_animation_scale` and `animator_duration_scale` to 0.5.
- If I use Stremio: in India it isn't on the Play Store. Version 1.2.x can't open titles from the
  watchlist; offer the Android TV build from stremio.com/downloads (check the package and signer with
  `apksigner`), and keep the old one until I've logged in and checked my add-ons. Copy the APK to
  `my-tv/apks/com.stremio.one.apk`.
- Write every setting you change into `my-tv/settings.txt` (`namespace key value` per line), and any app
  I want gone for good into `my-tv/remove.txt`.

**7. Finish**
- Measure again the same way and tell me the before and after numbers.
- Run `tools/reapply.sh --check` and show me it's clean. Tell me that after a firmware update or factory
  reset, `sh tools/reapply.sh` puts everything back, and that I should back up `my-tv/`.
- Remind me I can switch USB debugging off; I'll need it back on to run `reapply.sh`.
