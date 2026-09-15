# Troubleshooting Duo Launcher

Duo Launcher is an experimental Fold beta. Device vendors, Android releases, the Google app, widget providers, and work-profile policy can all affect behavior. These steps preserve the current installation wherever possible.

## Duo is not the Home app

Long press empty Home space, choose **Customize launcher**, then **Set as home app**. You can also use **Help & setup → Set Duo as Home**. Complete the choice in Android's Home app settings.

To switch back, choose **Change home app** in customization or use Android **Settings → Apps → Default apps → Home app**. Installing Duo never changes the Home app automatically.

## An update will not install

Android only updates an app when the new APK has the same application ID and signing key. Duo's public beta and developer/debug builds use different keys, so one cannot update the other directly.

Android's [app-signing documentation](https://source.android.com/docs/security/features/apksigning) explains the package signature checks.

Do not uninstall or clear storage from a configured installation just to test a differently signed APK: that removes its layout, selected launcher photo, and Android widget bindings. Keep the configured install and test the other signer on a separate device or disposable emulator. If you deliberately replace the install, save a layout backup first, but expect provider widgets to require **Reconnect** and the launcher photo to be absent from the backup.

## Discover is missing or has no feed

Discover requires the installed Google app plus device support for Duo's embedding path. Google account, network, app settings, Android, and vendor updates can affect it.

- If a recovery card appears, choose **Retry**.
- Choose **Open Google** to check whether the Google app itself can show content.
- Choose **Back to Home**, press Back, or use the return arrow to leave Discover.
- If **Open Google** is absent, the Google app has no launchable activity available to Duo.
- In **Customize launcher → Gestures & search**, turn off **Show Google Discover page** to remove the page, compass button, and swipe gesture when the feed is not usable.

To capture connection details, open **Customize launcher → Backup → Export diagnostic log** after reproducing the problem. The bounded text log includes Duo's package/version, whether the APK is debuggable, the signing-certificate fingerprint, device/Android identity, Google app version, and Discover connection events. It does not include your installed-app list or Home layout. Review it before sharing; Duo never uploads it automatically.

A recovery screen proves that Duo can return safely; it does not prove that the device supports the live embedded feed. A short swipe that begins inside Google's feed can also rebound because Google owns that gesture. Try a deliberate swipe, the Duo-owned dock or rail, or the return arrow.

## The search button does not open Google

In **Customize launcher → Gestures & search**, check **Search button opens Google**. Duo asks the Google app to open Android's global search screen. If that activity is missing or blocked, Duo falls back to **All apps** with its local **Search apps** field. You can turn the setting off to use local app search every time.

## A widget will not add or finish setup

Android controls widget binding, and providers may require their own configuration or permissions.

- If Duo shows **Widget not added**, acknowledge it and try the provider again.
- If a placeholder says **Finish widget setup**, choose **Finish setup** to resume, or **Cancel** to remove the placeholder.
- If **Widget settings** was interrupted, choose **Resume** or **Cancel**; cancelling keeps the existing widget unchanged.
- If the provider or profile is unavailable, install or enable it in the correct profile, then retry. Otherwise choose **Replace**.

Do not clear Duo, Google, or provider app data as a shortcut. An Android widget ID without its system binding cannot recreate the widget.

## A restored widget says Reconnect

Layout backups store the widget provider and its rectangle, but not a portable live binding. Choose **Reconnect** on the placeholder and approve Android's binding or provider setup. Choose **Replace** when the saved provider/profile is unavailable.

Work widgets depend on administrator cross-profile policy. A backup restored on another installation cannot automatically map work-profile identities. The review screen reports **Profile attention** for affected widgets; use a provider from the current work profile when reconnecting or replacing them.

## A widget will not move or resize

Hold still on the widget until pickup begins, then drag. On a scrollable provider such as a calendar list, vertical movement before the hold is treated as provider scrolling. Tiny held jitter is allowed, but a real move, release, cancellation, or second pointer before the long-press timeout cancels pickup.

Long press and release to open **Widget options**. Use **Resize on Home** or the width/height controls, then **Apply** or **Apply size**. A red or disabled preview means the proposed rectangle overlaps another item, extends beyond the page, or violates the provider's resize limits. Move the widget into the six-row grid first if Duo says that is required.

## Paging, widget scrolling, or shade gestures do the wrong thing

- Start a mostly horizontal swipe to change exactly one page. This works over Home, the dock, and the right rail, including over a widget.
- Start a vertical swipe on a scrollable part of a native widget to scroll the provider. Static widget areas still allow Home's own vertical action.
- Shade gestures work only on Home: left 70% opens Notifications, right 30% opens Quick Settings. A dock that is already vertically scrolled keeps its downward gesture.
- To drag an item between pages, keep holding at the full left or right window edge until the page turns. Ordinary swipes and held edge paging use different timing.

If shade gestures are off, choose **Help & setup → Set up shade gestures**, then **Open settings** and enable Duo Launcher yourself. If the service has just started, follow the on-screen request to swipe again. Duo does not enable Accessibility access automatically.

## An app or work profile is unavailable

Open **All apps** and select **Personal** or **Work**. For a paused managed profile, choose **Turn on work apps**. If Android or the administrator keeps the profile unavailable, Duo cannot launch its apps or bind its widgets. Removed or temporarily unavailable packages can also remain visibly unavailable until Android reports them again.

Android documents the administrator-controlled activity and storage boundaries in its [work-profile guide](https://developer.android.com/work/managed-profiles).

## A background photo did not change

Selecting a photo only stages a preview. Choose **Apply** to commit it; **Cancel** intentionally keeps the previous launcher background. If Duo says the picker was interrupted, choose **Resume** or select the photo again after cancelling.

Layout backups exclude photos and cannot restore a photo deleted from its source. If no committed copy or ready preview remains, choose another photo. **Preview Android wallpaper** opens Android’s separate wallpaper preview and does not apply the selected Duo background.

## Cellular status says unavailable

The lower status dots represent cellular strength. **Cellular signal unavailable** is expected on a device without a SIM, including the reference Fold used for this beta. SIM-equipped no-service, airplane-mode, and signal transitions have not yet been broadly validated. Battery is the surrounding arc; Wi-Fi uses the inner arcs.

## Before reporting a problem

Include the Duo version, phone model, Android version, folded or unfolded state, the page and gesture involved, and exact reproduction steps. Review screenshots and logs before sharing them: widgets, account names, work data, and app lists may be visible. Duo does not upload diagnostics automatically.
