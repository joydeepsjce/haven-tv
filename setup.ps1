<#
  Windows version of setup.sh: installs Haven on a Google TV / Android TV over adb and grants what
  only adb can grant. Run it again any time something resets Haven.

    powershell -ExecutionPolicy Bypass -File setup.ps1 -Tv 192.168.1.50 [-Apk haven.apk] [-Remove]

  adb must be on PATH, or pass -Adb C:\path\to\adb.exe.
#>
param(
    [Parameter(Mandatory = $true)][string]$Tv,
    [string]$Apk,
    [switch]$Remove,
    [string]$Adb = "adb"
)
$ErrorActionPreference = "Stop"
if ($Tv -notmatch ':') { $Tv = "${Tv}:5555" }
$pkg = "home.haven.tv"
$svc = "$pkg/$pkg.HomeKeyService"
function Sh([string]$cmd) { & $Adb -s $Tv shell $cmd }

& $Adb connect $Tv | Out-Null
& $Adb -s $Tv wait-for-device

if ($Remove) {
    Sh "settings delete secure enabled_accessibility_services" | Out-Null
    Sh "settings delete global haven_layout_backup" | Out-Null
    & $Adb -s $Tv uninstall $pkg
    Write-Host "Haven removed. Press Home for the stock launcher."
    return
}
if ($Apk) { & $Adb -s $Tv install -r $Apk }

Sh "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
Sh "pm grant $pkg android.permission.READ_TV_LISTINGS"
Sh "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION"
Sh "appops set $pkg GET_USAGE_STATS allow"
Sh "pm grant $pkg android.permission.READ_MEDIA_IMAGES"
Sh "cmd package set-home-activity $pkg/.HomeActivity" | Out-Null

Sh "am start -n $pkg/.HomeActivity" | Out-Null
Start-Sleep 3
$old = (Sh "settings get secure enabled_accessibility_services").Trim()
if ($old -eq "null" -or $old -eq "") { $new = $svc } elseif ($old -like "*$svc*") { $new = $old } else { $new = "${old}:$svc" }
Sh "settings put secure enabled_accessibility_services $new"
Sh "settings put secure accessibility_enabled 1"
Start-Sleep 3
if ((Sh "dumpsys accessibility") -match "Haven") {
    Write-Host "Done: the Home button now opens Haven."
} else {
    Write-Host "Haven is installed, but the Home-button service didn't start. Enable 'Haven Home button'"
    Write-Host "in Settings > System > Accessibility, or run this script again."
}
