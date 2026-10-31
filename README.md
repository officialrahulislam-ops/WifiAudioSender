# Wi-Fi Audio Sender (Android)

Captures what your phone is **playing** (Android `AudioPlaybackCapture`, *not* the microphone),
encodes it as Opus (48 kHz stereo, 128 kbps, 20 ms frames) and streams it over local Wi-Fi to
**WifiAudioReceiver** (separate Windows project). Kotlin, no third-party libraries beyond AndroidX/Material.

Requires Android 10+ (API 29). Tested target: Android 14.

## Build (Windows)

### Option A – Android Studio (easiest)
1. Install Android Studio (https://developer.android.com/studio).
2. *File → Open* → select the `WifiAudioSender` folder; wait for Gradle sync (downloads Gradle 8.7 + SDK 34 on request).
3. Phone: enable *Developer options → USB debugging*, plug in, accept the prompt.
4. Press **Run ▶** to install and start it, or *Build → Build APK(s)*.

### Option B – command line
```bat
winget install Gradle.Gradle
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
cd WifiAudioSender
gradle assembleRelease
copy app\build\outputs\apk\release\WifiAudioSender-release.apk WifiAudioSender.apk
adb install -r WifiAudioSender.apk
```
(Needs JDK 17 and the Android SDK; Android Studio installs both. `gradle assembleDebug` also works.
Run `gradle wrapper --gradle-version 8.7` once if you want a `gradlew.bat`.)
The release APK is signed with the debug key so it installs without creating a keystore; use your own
signing config before publishing anywhere.

## Using it
1. Start the Windows receiver first (note its 4-digit **pairing code**).
2. Open the app. The PC appears under *Receiver* (or use *Can't find your PC?* and type its IP).
3. Enter the pairing code → **Connect**. You can also click **Connect** next to the phone in the PC app.
4. Accept the microphone-permission explanation and Android's *screen capture* prompt
   (that prompt is Android's only gate for system-audio capture; no screen/mic is recorded).
5. Streaming continues with the screen off / other apps open. Notification has a **Disconnect** button.

## Behaviour notes / limitations
* **Phone speaker:** Android has no API to re-route other apps' audio. While streaming the app sets the
  *media volume to 0* and restores your previous level on disconnect (also restored on next launch if the
  app was killed). Disable with *Silence phone speaker*. **If the PC plays silence when this is on**, your
  device applies volume before capture: turn the switch off and lower the volume by hand.
* **Apps that block capture** (some streaming/DRM apps, apps targeting Android 9 or lower, apps with
  `allowAudioPlaybackCapture=false`) produce silence. Android enforces this; it is not bypassed.
  Calls and notification sounds are never captured.
* **Reconnect:** after a Wi-Fi drop the app retries every 2 s to the same IP while *Automatically reconnect*
  is on. Give your PC a DHCP reservation so its IP doesn't change.
* **Opus encoder:** uses Android's built-in `audio/opus` MediaCodec encoder (present on Android 10+ builds
  from Google/most OEMs). If a device lacks it the app says so.
* **Wi-Fi power:** a low-latency Wi-Fi lock and CPU wake lock are held while streaming. Some vendors'
  battery savers still kill foreground services — set the app to *Unrestricted* battery use if audio stops
  with the screen off.

## Files
| File | Purpose |
|---|---|
| `MainActivity.kt` | UI, permission → capture-consent → service flow |
| `StreamService.kt` | foreground service (type `mediaProjection`), notification, locks, volume guard |
| `OpusCapture.kt` | AudioPlaybackCapture → 20 ms PCM → MediaCodec Opus |
| `SenderConnection.kt` | TCP, handshake, send loop, keep-alive, auto-reconnect |
| `Discovery.kt` | UDP broadcast discovery + invites |
| `Protocol.kt` | constants shared with the Windows app |
