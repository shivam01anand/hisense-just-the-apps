# Hisense HiSmartTV A4 (55U6G), what we found

Android TV 9, MStar m7322, 1.8 GB RAM, firmware 01.00.00.07 (built December 2021, security patch June 2021).

## Measured on one 55U6G

| | Before | After |
|---|---|---|
| Janky frames while moving around the home screen | 24% | 3.3% |
| Slowest 1% of frames | 65 ms | 18 ms |
| Home screen app in memory | 117 MB | 26 MB |
| Data Saver in memory | 67 MB | 4 MB |

Same remote presses both times, read with `dumpsys gfxinfo`. A cold boot still takes about 25 seconds to the
first picture; that is the firmware and does not change.

## Things that bit us

- **USB debugging opens network ADB on port 5555.** There is no separate wireless debugging switch.
- **No screensaver means sleep.** With Ambient Mode switched off and nothing else set, the TV goes to standby
  after 5 idle minutes. The new home screen's clock screensaver avoids it.
- **Instant power-on is locked.** The firmware has a warm-boot mode (`persist.sys.pm.warmboot`) but only
  system apps can set it and the menu entry is hidden. Waking from standby is already a resume, not a reboot;
  switching off at the wall is what causes the 25-second cold boot.
- **adb cannot switch off part of an app** (`Shell cannot change component state`), so apps that start
  themselves at boot can only be stopped by switching off the whole app.
- **Fully compiling apps did not make them open faster** on this TV's storage. Leave Android's default.
- **Stremio:** not on the Play Store in India. Version 1.2.x cannot open a title from a link (every
  `stremio://` link opens its add-on installer); 1.10 from stremio.com/downloads can. It ignores a link while
  already open, so the home screen restarts it for each title.
- **Prime Video** asks who's watching on a fresh start, then continues to the title.
- **SonyLIV** ignores JustWatch's `sony://asset/...` link (it lands on its home screen) but plays the title
  straight away from its `https://www.sonyliv.com/movies/...` web link, so that's what the home screen uses.
