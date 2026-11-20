# Notes for Claude

- Run cloud sessions for this repository on the **Fire Stick** environment. It allows `dl.google.com` (Android SDK and Google Maven) and its setup script installs the Android SDK at `/opt/android-sdk`, so both apps build. The Default environment can't build them. If a session starts somewhere else, move it to Fire Stick before building.
- Build and test: `gradle -p launcher assembleRelease` (Command Centre) and `gradle -p iptv testReleaseUnitTest assembleRelease` (My TV). Set `ANDROID_HOME=/opt/android-sdk` if it isn't set. Maven Central sometimes answers 429; retry after a short wait.
- Both screens are HTML/CSS/JS in `*/app/src/main/assets/` and can be previewed in a desktop browser with sample data.
- Never commit IPTV logins (server, username, password or M3U links with credentials). They're typed into the app or the install script and stay on the Fire Stick.
