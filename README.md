# Pocket Overlay

Pocket Overlay is a standalone Android app for a controller with digital Select and shoulder buttons. Hold Select and tap R1/L1 for media volume or R2/L2 for brightness. The overlay shows a segmented meter for the level you changed, alongside the time and battery status. It closes automatically.

The app requires Android 9 or later and was tested on a Retroid Pocket 6 running Android 13. It uses Android Accessibility to detect shortcuts and the Modify system settings permission to adjust brightness. It has no network permission and does not read screen content.

## Set up on the Pocket 6

1. Install the APK and open Pocket Overlay.
2. Tap **Enable controller shortcuts** and turn on **Pocket Overlay** in Android Accessibility settings. If Android blocks the toggle for a sideloaded app, open **Settings → Apps → Pocket Overlay**, then choose **Allow restricted settings** in the overflow menu.
3. Return to Pocket Overlay and tap **Allow brightness changes** to grant the system settings permission.
4. Test the four shortcuts over an emulator. If L2/R2 only send analog input, set the handheld's L2/R2 mode to **Both** or **Digital**. Disable overlapping shortcuts in other key mappers or RetroHrai while testing.

Volume adjusts Android's shared media stream. Select is passed through to the game, and the shoulder button is intercepted only when its shortcut runs. If adaptive brightness changes the level back, disable it in Display settings to use manual brightness.

## Build from source

Open this folder in Android Studio, or install JDK 17 and Android SDK Platform 35 with Build Tools 34.0.0 and run the Gradle wrapper.

```sh
./gradlew :app:assembleDebug
```

On Windows, run `gradlew.bat :app:assembleDebug`. Gradle writes the debug APK to `app/build/outputs/apk/debug/app-debug.apk`.

## License

[MIT](LICENSE)
