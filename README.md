# Haven

A fast, minimal home screen for Google TV and Android TV, in the spirit of Apple TV: a clean row
layout, a dock of your favourite apps and full-screen nature photos that rotate in the background.

No ads, no tracking, no account, no root. Written in plain Java with no libraries; the APK is
mostly wallpapers.

![Haven home screen](docs/home.jpg)

| | |
|---|---|
| ![Continue watching, Top picks and Favourites](docs/rows.jpg) | ![Search](docs/search.jpg) |
| *Continue watching, Top picks, Favourites* | *Search: apps as you type, plus Google and YouTube search* |
| ![All apps](docs/apps.jpg) | ![Edit mode](docs/edit.jpg) |
| *All apps* | *Edit mode: hold OK, then move tiles with the arrows* |
| ![Tile options](docs/options.jpg) | |
| *Tile options: dock, folders, rename, hide* | |

## Features

- **Home rows**, top to bottom:
  - **Recent**: your recently used apps.
  - **Continue watching**: the Watch Next rows your apps publish, newest first.
  - **Top picks for you**: a few programs from each app's home-screen channels, mixed so no
    single app takes over, plus what's trending on Netflix in your country.
  - **Favourites**: the dock.
  - **Inputs**: Live TV and HDMI.
  - **Apps**: every app, which you can sort, put in folders, rename or hide.
- **Top bar** (press Up): account, search, Home, Live TV and the maker's channels app when the
  TV has them, Inputs and TV Settings.
- **Search**: a full-screen search bar that finds apps as you type, with "Search all your apps"
  (Google's search) and "Search YouTube". The mic works like the remote's mic button.
- **Wallpapers**: 26 Wikimedia Commons featured photos, with the place and photographer shown
  in the corner. The photo blurs while you use the remote and turns sharp again when you stop.
  The focus colour comes from the photo. You can add your own photos (see below).
- **Clock, date and weather** for wherever the TV is (Open-Meteo; °F in the US).
- **Edit mode**: hold OK until the tiles wiggle, then move apps between the dock and the grid,
  make folders, rename or hide apps.
- **Screensaver**: "Haven nature photos" uses the same wallpapers.
- **Home button**: an accessibility service opens Haven straight away when you press Home, with no
  flash of the stock launcher. Google TV doesn't let a third-party launcher take over Home any
  other way.
- **Survives "cleaner" apps** (e.g. TCL's Safety Guard): it keeps a backup of your layout, starts
  its Home-button service again if something switches it off, and gets past boot managers that
  block third-party apps from starting at boot.
- Up/Down moves exactly one row and Left/Right stays inside the row, so the remote never jumps
  somewhere unexpected.

## Install

You need a computer with [adb](https://developer.android.com/tools/releases/platform-tools) and
the TV and computer on the same network.

1. On the TV, turn on **Developer options**: Settings > System > About, then press OK on
   *Android TV OS build* seven times. Then turn on **USB debugging** and **Network debugging**
   under Settings > System > Developer options. Note the TV's IP address (Settings > Network).
2. Get `haven.apk` from [Releases](../../releases), or build it yourself (below).
3. Run the setup script. The first time, the TV asks you to allow debugging from your computer.

   ```sh
   sh setup.sh 192.168.1.50 haven.apk
   ```
   On Windows:
   ```powershell
   powershell -ExecutionPolicy Bypass -File setup.ps1 -Tv 192.168.1.50 -Apk haven.apk
   ```
4. Press Home on the remote.

The script grants permissions that only adb can grant (see below). Run it again with no APK if
something ever resets Haven. Turn off Network debugging afterwards if you like; Haven doesn't
need it.

**Remove:** `sh setup.sh <ip> --remove` (or `setup.ps1 -Tv <ip> -Remove`) uninstalls Haven and
gives the Home button back to the stock launcher.

## Permissions

| Permission | Why |
|---|---|
| Accessibility service "Haven Home button" | Opens Haven when you press Home. It only listens for the Home key and for the Google TV home screen opening. It can't read what's on screen and logs nothing. |
| `READ_TV_LISTINGS` (adb) | Reads the Continue watching and channel rows your apps publish, as the stock launcher does. |
| `WRITE_SECURE_SETTINGS` (adb) | Starts the Home-button service again if something turns it off, sets animation speed to 0.5×, and keeps a backup of the layout (which apps are where, nothing personal). |
| Usage access (adb) | Lists recent apps in the Recent row, including ones not opened from Haven. Optional. |
| `ACCESS_COARSE_LOCATION` (adb) | Gets the weather for where you are. Rounded to about 1 km and stored only on the TV. Optional. |
| `INTERNET` | Weather (api.open-meteo.com) and Netflix trending titles (JustWatch's public API). Nothing else is sent anywhere. |

## Tips

- **Your own wallpapers:** copy JPEGs to the TV, and Haven uses them the next time it starts:
  `adb push photo.jpg /sdcard/Android/data/home.haven.tv/files/wallpapers/`
- **Haven Settings** is the last tile in Apps. It covers focus sounds, the Home button, wallpaper
  brightness and blur, colours from the wallpaper, hidden apps, the account badge letter and
  resetting the layout.
- **Screensaver:** Settings > System > Ambient mode > Screensaver > Haven nature photos.
- Keys sent over adb skip the accessibility service, so `adb shell input keyevent HOME` still
  shows the stock launcher briefly. The real remote doesn't.

## Build

There's no Gradle, only the SDK's command-line tools. You need JDK 11 or later, plus Android SDK
build-tools and `platforms;android-34`.

```sh
ANDROID_HOME=/path/to/android-sdk sh build.sh
```

The output is `build/haven.apk`. Builds on Linux, macOS and Windows (Git Bash). The first build
creates your own signing key, `haven.keystore`. Keep it: updates must be signed with the same key,
and reinstalling loses the adb-granted permissions. Set `KS_PASS` to choose its password.

## Tested on

TCL Google TV (Android TV 12). The code checks for each feature before using it, so it should work
on other Google TV and Android TV devices (Android 8+), but they haven't been tested.
Reports are welcome.

## Notes

- Netflix doesn't publish its rows to the TV, so its titles come from
  [JustWatch](https://www.justwatch.com)'s unofficial public API and open in Google's search for
  that title. If JustWatch changes its API, they simply stop appearing.
- Haven isn't affiliated with Apple, Google, TCL or any streaming service.

## Licence

The code is under the MIT licence ([LICENSE](LICENSE)). The photos and the Inter font have their
own licences, listed in [NOTICE.md](NOTICE.md).
