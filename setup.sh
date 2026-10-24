#!/bin/sh
# Installs Haven on a Google TV / Android TV over adb and grants what only adb can grant.
# Run it again any time something resets Haven (a "cleaner" app, a factory reset of settings).
#
#   sh setup.sh 192.168.1.50            # TV's IP (Settings > Network); network debugging on
#   sh setup.sh 192.168.1.50 haven.apk  # also install/update the APK first
#
# Undo: sh setup.sh <ip> --remove
set -e
TV="${1:?Usage: sh setup.sh <tv-ip> [haven.apk | --remove]}"
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
ADB="${ADB:-adb}"
PKG=home.haven.tv
SVC="$PKG/$PKG.HomeKeyService"
sh_tv() { "$ADB" -s "$TV" shell "$@"; }

"$ADB" connect "$TV" >/dev/null
"$ADB" -s "$TV" wait-for-device

if [ "$2" = "--remove" ]; then
  sh_tv settings delete secure enabled_accessibility_services || true
  sh_tv settings delete global haven_layout_backup || true
  "$ADB" -s "$TV" uninstall "$PKG" || true
  echo "Haven removed. Press Home for the stock launcher."
  exit 0
fi
[ -n "$2" ] && "$ADB" -s "$TV" install -r "$2"

# Restart the Home-button service and keep the layout backup if something resets settings.
sh_tv pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
# Continue watching / Top picks: read the system TV provider, like the stock launcher does.
sh_tv pm grant $PKG android.permission.READ_TV_LISTINGS
# Weather for where the TV is (network location, rounded to ~1 km).
sh_tv pm grant $PKG android.permission.ACCESS_COARSE_LOCATION || true
# Recent row: recently used apps, including ones not opened from Haven.
sh_tv appops set $PKG GET_USAGE_STATS allow
# Add your own photos > From a USB drive: read photos on the drive.
sh_tv pm grant $PKG android.permission.READ_MEDIA_IMAGES || true
# Makes Haven the default home where the system allows it (Google TV ignores this for the Home key,
# which is why the accessibility service below exists).
sh_tv cmd package set-home-activity $PKG/.HomeActivity || true

# The Home-button service. Some makers only allow the bind while Haven is in front, so open it first.
sh_tv am start -n $PKG/.HomeActivity >/dev/null
sleep 3
OLD="$(sh_tv settings get secure enabled_accessibility_services | tr -d '\r')"
case "$OLD" in null|"") NEW="$SVC" ;; *"$SVC"*) NEW="$OLD" ;; *) NEW="$OLD:$SVC" ;; esac
sh_tv settings put secure enabled_accessibility_services "$NEW"
sh_tv settings put secure accessibility_enabled 1
sleep 3
if sh_tv dumpsys accessibility | grep -q "Haven"; then
  echo "Done: the Home button now opens Haven."
else
  echo "Haven is installed, but the Home-button service didn't start. Enable 'Haven Home button'"
  echo "in Settings > System > Accessibility, or run this script again."
fi
