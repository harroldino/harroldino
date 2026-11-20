# Command Centre

A custom home screen for an Amazon Fire TV Stick. It shows a big clock, the weather, live scores, your IPTV app and your other apps (Netflix, YouTube, Stan, ABC iview, SBS and so on), and none of Amazon's ads.

- `launcher/` is the Android app that you install on the Fire Stick.
- `mockups/` holds the design mockups it was built from.

## What it does

| Part of the screen | What it shows |
| --- | --- |
| Clock | Time, date, a greeting, and a thin line showing how much of the day has gone. 12 or 24-hour. |
| Weather | Current temperature and conditions, today's low and tomorrow's high. Select it to see the next few days. Uses Open-Meteo (free, no account). |
| Watch | A large **My TV** card that opens your IPTV app, plus the last three apps you opened. |
| Scores | Live, upcoming and recent matches from leagues you pick: A-League Men and Women, AFL, NRL, Premier League, NBA. Uses ESPN's public scoreboards. |
| System | Wi-Fi speed and free storage. |
| App row | **My TV** plus up to 8 apps you choose, then **All apps** and **Settings**. |

## Using it with the remote

| Button | What happens |
| --- | --- |
| Arrows | Move around |
| OK (centre) | Open the selected item |
| Menu ☰ on an app in the row | Move it left or right, or remove it |
| Menu ☰ in All apps | Add the selected app to the row, or remove it |
| Menu ☰ anywhere else | Open Settings |
| Back | Close whatever is open |

In **Settings** you can switch between 12 and 24-hour time, set the weather city, choose which app **My TV** opens, pick score leagues, set up the Home button, reset the app row, or open Amazon's home screen for 5 minutes.

## Install it on the Fire Stick

This works on Fire TV devices that run Fire OS, which is Android-based. That covers most Fire TV Sticks, including the Lite, 4K and 4K Max. Newer models that run Vega OS, such as the Fire TV Stick 4K Select, can't install Android apps, so it won't work on those.

1. **Turn on developer options.** Go to *Settings → My Fire TV → About* and press OK on your device name 7 times.
2. **Install Downloader.** Search for "Downloader" on the Fire Stick (it's the orange one by AFTVnews) and install it.
3. **Allow Downloader to install apps.** Go to *Settings → My Fire TV → Developer options → Install unknown apps* and turn on Downloader.
4. **Download Command Centre.** Open Downloader and enter:
   `https://github.com/harroldino/harroldino/releases/latest/download/command-centre.apk`
   Then choose **Install**. This link only works if the GitHub repository is public. If yours is private, download `command-centre.apk` from the repository's **Releases** page on a computer and install it with ADB instead: `adb connect <fire-stick-ip>` then `adb install -r command-centre.apk`.
5. **Open it.** It appears in your Fire TV apps as **Command Centre**.

### Or install it from a Windows PC in one go

On the Fire Stick, turn on *Settings → My Fire TV → Developer options → ADB debugging*. Then on a Windows PC on the same Wi-Fi, open PowerShell and run:

```powershell
irm https://raw.githubusercontent.com/harroldino/harroldino/claude/charming-allen-pgwomg/scripts/install-firestick.ps1 | iex
```

It asks for the Fire Stick's IP address, downloads `adb` and the latest build, installs the app, turns on the Home button redirect and opens Command Centre. The script is `scripts/install-firestick.ps1` if you want to read it first.

### Make the Home button open it

Fire OS doesn't let you choose a different home screen. Command Centre gets around this with a small accessibility service that only watches for Amazon's home screen opening and switches straight back to Command Centre.

1. In Command Centre, press Menu ☰, go to **Home button** and choose **Set up**.
2. Turn on **Command Centre Home button**.

Now the Home button takes you to Command Centre, and the Fire Stick opens straight into Command Centre when it's switched on. Amazon's home screen may flash for a moment during start-up, because Fire OS always starts it first. To use Amazon's home screen briefly (for profiles or Amazon's own settings), use **Settings → Amazon home for 5 min**.

Amazon sometimes changes how the Home button works in Fire OS updates. If it stops redirecting, check that the service is still on. As a fallback, the free *Button Mapper* app can map Home to Command Centre.

### Updates

Every change pushed to `launcher/` builds a new APK in GitHub Actions and publishes it as the latest release. To update, run the same Downloader link again and install over the top. Your settings are kept.

## Building it yourself

GitHub Actions builds the app (`.github/workflows/build-apk.yml`). To build locally you need JDK 17, the Android SDK and Gradle 8.7 or newer:

```sh
gradle -p launcher assembleRelease
# output: launcher/app/build/outputs/apk/release/app-release.apk
```

The interface is plain HTML, CSS and JavaScript in `launcher/app/src/main/assets/`. Open `index.html` in a desktop browser to preview it with sample data: arrow keys move, Enter opens, `m` acts as the Menu button and Esc acts as Back.

The signing key in `launcher/keystore/` is committed on purpose so that every build installs over the previous one. It's fine for a personal app you sideload, but don't reuse it for anything published.

## Credits

Fonts are bundled for offline use under the SIL Open Font License: Outfit, Space Grotesk and JetBrains Mono. Weather data comes from [Open-Meteo](https://open-meteo.com). Scores come from ESPN's public scoreboard feeds, which are unofficial and may change.
