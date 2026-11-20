# Installs Command Centre on a Fire TV Stick from a Windows PC, over Wi-Fi (ADB).
# Before running: on the Fire Stick turn on Settings > My Fire TV > Developer options > ADB debugging.
# The PC and the Fire Stick must be on the same network.
$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"

Write-Host ""
Write-Host "Find the Fire Stick's IP address in: Settings > My Fire TV > About > Network" -ForegroundColor Green
$ip = (Read-Host "Fire Stick IP address (e.g. 192.168.1.42)").Trim()
$target = "${ip}:5555"

$dir = Join-Path $env:USERPROFILE "firestick-command-centre"
New-Item -ItemType Directory -Force $dir | Out-Null
Set-Location $dir

Write-Host "Downloading Android platform tools (adb)..."
Invoke-WebRequest -ErrorAction Stop "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile "platform-tools.zip"
Expand-Archive "platform-tools.zip" -DestinationPath . -Force
$adb = Join-Path $dir "platform-tools\adb.exe"

Write-Host "Downloading the latest Command Centre build..."
Invoke-WebRequest -ErrorAction Stop "https://github.com/harroldino/harroldino/releases/latest/download/command-centre.apk" -OutFile "command-centre.apk"

Write-Host "Connecting to $target ..."
& $adb connect $target | Write-Host
Write-Host ""
Write-Host "Look at the TV. If it asks 'Allow USB debugging?', tick 'Always allow from this computer' and choose OK." -ForegroundColor Yellow
Read-Host "Then press Enter here"
& $adb connect $target | Write-Host

$state = (& $adb -s $target get-state 2>$null | Out-String).Trim()
if ($state -ne "device") {
    Write-Host "Couldn't connect to the Fire Stick. Check the IP address, that ADB debugging is on, and that you allowed this PC on the TV, then run this again." -ForegroundColor Red
    return
}

Write-Host "Installing Command Centre..."
& $adb -s $target install -r "command-centre.apk" | Write-Host

Write-Host "Turning on the Home button redirect..."
$svc = "com.harroldino.home/com.harroldino.home.HomeButtonService"
$cur = (& $adb -s $target shell settings get secure enabled_accessibility_services | Out-String).Trim()
if (-not $cur -or $cur -eq "null") { $new = $svc }
elseif ($cur -like "*$svc*") { $new = $cur }
else { $new = "${cur}:${svc}" }
& $adb -s $target shell settings put secure enabled_accessibility_services $new
& $adb -s $target shell settings put secure accessibility_enabled 1

Write-Host "Opening Command Centre on the TV..."
& $adb -s $target shell monkey -p com.harroldino.home -c android.intent.category.LEANBACK_LAUNCHER 1 | Out-Null

Write-Host ""
Write-Host "Done. Command Centre is installed and the Home button now opens it." -ForegroundColor Green
Write-Host "To update later, just run this script again."
