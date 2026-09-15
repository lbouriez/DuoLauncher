# Duo Launcher

A native Android launcher built around a right-side dock and a home screen that makes room when you unfold your phone.

**Experimental Fold beta · Android 12 or later.** The primary physical test device is a Galaxy Fold8 running Android 17. Emulator coverage supplements that device; it does not establish compatibility with every foldable. Google Discover depends on the installed Google app and device support for activity embedding. See the [tested environments and remaining checks](docs/public-release.md#beta-0150-beta01-validation).

<p>
  <img src="docs/images/duo-launcher-cover-home.png" width="240" alt="Duo Home on a cover-sized emulator, with its right-side dock">
  <img src="docs/images/duo-launcher-inner-home.png" width="500" alt="Duo Home unfolded, with an extra workspace on the left">
</p>

Screenshots use sample data on an emulator sized to the reference Fold. [Fresh-install welcome](docs/images/duo-launcher-welcome.png).

**Start here:** [User guide](docs/user-guide.md) · [Troubleshooting](docs/troubleshooting.md) · [Beta release notes](docs/releases/0.15.0-beta01.md)

## Features

- A persistent right-side dock and vertical Home status indicators.
- Overlapping unfolded page pairs: an extra workspace beside Home 1, then Home 1 beside Home 2, and so on.
- Android widgets, visual widget selection, resizing, native scrolling, and drag-and-drop between pages.
- App dragging, pages created during an edge drag, Home folders, and separate personal/work catalogs where device policy permits.
- Alphabetical All apps, Google search with a local app-search fallback, and live Discover on compatible devices.
- Local photo wallpapers, light/dark/system or sunrise/sunset appearance, and layout export/import.

Android still controls the lock screen, notification panels, recents, and system app transitions.

## Install and try it

1. Download the signed APK from this repository's Releases section. Read its tested-device notes and known issues.
2. Open the APK, allow installation from that source if Android asks, and open **Duo Launcher**.
3. Try the layout before choosing **Set as home app**. Select Duo Launcher in Android's Home app settings when ready.
4. Long press an empty Home cell or the narrow wallpaper margin beside a full grid to add widgets or **Customize launcher**. Help is available from customization.

To switch back, open Android **Settings → Apps → Default apps → Home app** and select your previous launcher. Vendor labels may differ. Installing Duo does not automatically select it as Home.

Normal beta updates install over the existing beta with the same signing key. Uninstalling or clearing storage removes the saved layout and widget bindings. A differently signed developer/debug build cannot be updated directly by the public APK; see [release and update notes](docs/public-release.md).

## Everyday controls

| Action | Gesture or control |
| --- | --- |
| Change pages | Swipe horizontally across Home, the dock, or right rail; one page per gesture |
| All apps | Swipe past the last Home page or tap its page control |
| Discover | Swipe right from the first Home page or tap the compass |
| Return from Discover | Swipe left, use the right-pointing arrow, or press Back |
| Rearrange apps/widgets | Hold, then drag; pause at the screen edge to change or create a page |
| Add to the dock | Drag into a vacancy; move an app out first when the dock is full |
| Scroll a widget | Swipe vertically inside its content; hold still to pick it up |
| Customize | Long press empty Home space or the wallpaper margin beside the grid |
| Notifications / Quick Settings | Swipe down from Home's left 70% / right 30%, after enabling optional shade gestures |

The surrounding status ring shows battery, the inner arcs show Wi-Fi strength, and the lower dots show cellular strength. Unknown readings are not displayed as full signal. This rail applies to Home only.

## Optional access and privacy

No launcher account, server, advertising, analytics, or automatic crash-upload service is used. Layouts and selected backgrounds stay on the device unless explicitly exported or shared.

- **Widgets:** Android asks to allow binding; providers may have their own setup.
- **Shade gestures:** the optional accessibility service opens notifications and Quick Settings. It cannot read window contents or inject gestures.
- **Sunrise/sunset:** manually enter coordinates or explicitly request approximate location. There is no background location request.
- **Photos:** the system picker grants access to chosen images, without whole-library access.
- **Google features:** the installed Google app's account, network, and privacy settings apply.

Read [data and permissions](PRIVACY.md) before sharing backups or diagnostics.

## Known limits

- Discover can differ across Google, Android, and vendor updates. Its smooth embedding transition includes a version-scoped compatibility workaround; it is not a portable SystemUI API. Recovery controls let you return Home when unavailable.
- Work apps/widgets remain subject to administrator policy. Private Space is not supported.
- Icon packs and notification dots are not implemented. Folders cannot nest or occupy dock slots.
- Imported Android widgets require binding again. Cross-installation work entries may require manual placement. Backups exclude photo backgrounds and system widget capabilities.
- Secure lock-screen replacement and hinge-driven cross-display animation are outside this beta.

## Build

Use JDK 17 or Android Studio's bundled JDK, Android SDK 36, and the included Gradle wrapper. Set `ANDROID_HOME` or a local `sdk.dir` in `local.properties`.

```sh
./scripts/gradle.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Release builds use R8 and resource shrinking; private signing material stays outside the repository. Follow the beginner-friendly [CI and signed-release instructions](docs/public-release.md) for public-source export, fork identity, GitHub Actions signing, and safe updates.

The project uses Kotlin, Jetpack Compose, AndroidX Window, and native widget hosting. Instrumentation runs on disposable emulators. Some integration fixtures require Google, Clock, Chrome, and a configured emulator; they are not commands for your everyday phone.

The [contributor code map](docs/architecture.md) explains the main components, data ownership and gesture/widget constraints.

## Feedback and contributions

Use issue templates with version, phone model, Android version, folded/unfolded state, and reproduction steps. Review screenshots and logs for personal/work information. See [contributing](CONTRIBUTING.md) and [changes](CHANGELOG.md).

Source is under the [MIT license](LICENSE); dependency notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). This independent project is unaffiliated with Apple, Google, or Samsung. The default wallpaper is drawn locally; app icons come from installed apps. Apple research media and Google application code are excluded from the public source and APK.
