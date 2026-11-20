# Installs My TV (the IPTV app) on a Fire TV Stick from a Windows PC over Wi-Fi (ADB), and signs it in.
# Before running: on the Fire Stick turn on Settings > My Fire TV > Developer options > ADB debugging.
# The PC and the Fire Stick must be on the same network.
# Your IPTV login is typed here and sent straight to the Fire Stick. It isn't saved on this PC.
$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"

Write-Host ""
Write-Host "Find the Fire Stick's IP address in: Settings > My Fire TV > About > Network" -ForegroundColor Green
$ip = (Read-Host "Fire Stick IP address (e.g. 192.168.1.42)").Trim()
$target = "${ip}:5555"

$dir = Join-Path $env:USERPROFILE "firestick-command-centre"
New-Item -ItemType Directory -Force $dir | Out-Null
Set-Location $dir

$adb = Join-Path $dir "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Host "Downloading Android platform tools (adb)..."
    Invoke-WebRequest -ErrorAction Stop "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile "platform-tools.zip"
    Expand-Archive "platform-tools.zip" -DestinationPath . -Force
}

Write-Host "Downloading the latest My TV build..."
Invoke-WebRequest -ErrorAction Stop "https://github.com/harroldino/harroldino/releases/download/mytv/my-tv.apk" -OutFile "my-tv.apk"

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

Write-Host "Installing My TV..."
& $adb -s $target install -r "my-tv.apk" | Write-Host

Write-Host ""
Write-Host "Now your IPTV login, from your provider's welcome email." -ForegroundColor Green
Write-Host "Leave Server empty to skip this and sign in on the TV instead."
$server = (Read-Host "Server (e.g. http://line.example.com)").Trim()
if ($server) {
    $user = (Read-Host "Username").Trim()
    $pass = (Read-Host "Password").Trim()
    Write-Host "Opening My TV and signing in..."
    & $adb -s $target shell am start -n com.harroldino.mytv/.MainActivity --es server "'$server'" --es username "'$user'" --es password "'$pass'" | Out-Null
} else {
    Write-Host "Opening My TV..."
    & $adb -s $target shell am start -n com.harroldino.mytv/.MainActivity | Out-Null
}

Write-Host ""
Write-Host "Done. My TV is open on the TV." -ForegroundColor Green
Write-Host "If it says the server can't be reached, press Menu on the remote, choose Sign out, and try your provider's backup server."
Write-Host "To update later, just run this script again."
