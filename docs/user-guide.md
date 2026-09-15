# Duo Launcher user guide

Duo Launcher is an experimental Android launcher designed around a foldable phone, a four-column Home grid, and a four-position dock on the right. The cover shows one Home page at a time. Unfolding adds an editable workspace on the left: the first view pairs that workspace with Home 1, followed by Home 1 + Home 2, Home 2 + Home 3, and so on.

## Start and switch launchers

On a fresh install, **Welcome to Duo** offers **Choose Home app**, **Add a widget**, **Explore Home**, and **Not now**. Choosing or skipping setup does not prevent later changes.

To make Duo the launcher, choose **Set as home app** in customization, or open **Help & setup** and choose **Set Duo as Home**. Android owns the final Home-app chooser. To switch away later, choose **Change home app**, or use Android **Settings → Apps → Default apps → Home app**. The exact Android path may vary by device.

## Move around Home

- Swipe horizontally across Home, the dock, or the right rail to move one page per gesture.
- Swipe right from Home 1 for Discover. Swipe left, press Back, or use its right-pointing arrow to return.
- Swipe past the last Home page for **All apps**. Its **Search apps** field always searches installed apps locally.
- The dock and its search control stay on the right. The page controls also open Discover or All apps.
- Pressing the system Home control from an app returns to the Home page or unfolded pair you last had visible. From All apps, search, or Discover it returns to the last Home view.

## Customize Home

Long press an empty Home cell to open **Add to Home**, then choose **Widgets**, **Wallpaper**, or **Customize launcher**. If every cell is occupied, long press the slim wallpaper margin at the left edge of the grid. In **Make it yours** you can open:

- **Wallpaper & appearance** for launcher photos, Android wallpaper, and color mode.
- **Home layout** for icon size, row spacing, dock geometry, Home apps, and widgets on the visible page.
- **Gestures & search** for app names, the upper-right status display, Discover, and Google search behavior.
- **Backup** to save or restore the layout, or export a diagnostic log.
- **Help & setup** for Home selection, widgets, shade gestures, and Discover.

After a layout edit, **Undo last layout change** appears in customization. It covers the latest supported layout change, so use it before making another edit.

## Apps, folders, and the dock

Hold an app, then drag it to an empty cell, another page, or a vacant dock position. Neighboring Home icons move aside when possible. Pause at the left or right screen edge while holding to turn a page; dragging at the end can create another Home page.

Dragging between Home and the dock moves the shortcut instead of duplicating it. The dock holds four apps. When it is full, Duo shows **Dock full • Move an app out first** and rejects a new arrival; it never evicts an app automatically. Existing dock apps can still be reordered. Drag a Home or dock shortcut to **Remove** to remove the shortcut without uninstalling the app.

Long press and release an app for options such as **Move on Home**, **Create folder**, **App info**, or **Remove from Home**. **All apps** remains the complete installed-app catalog even when a shortcut is removed.

If Android exposes a managed profile, **All apps** shows **Personal** and **Work** filters. A paused profile shows **Work apps are paused** and **Turn on work apps**. Availability and cross-profile widget access remain controlled by the profile administrator.

## Widgets

Open **Widgets** from an empty-space menu, **Add widget to this page** in customization, or **Add a widget** during setup. Search the catalog, select **Personal** or **Work** when those choices exist, then tap a preview to place it or hold it to drag. Android may ask you to allow the binding, and some providers open their own setup screen.

Hold an existing widget to pick it up, then drag it across cells or pages. A small amount of held finger jitter is allowed. Move into the lower-right **Remove** target to delete it from Home. Long press and release without dragging to open **Widget options**, which can include **Widget settings**, **Resize on Home**, page moves, **Replace**, and **Remove**.

For **Resize on Home**, drag the resize handle and choose **Apply**, or choose **Cancel**. The alternate size controls end with **Apply size**. Duo rejects sizes or moves that overlap another item, exceed the four-column by six-row grid, or violate the provider's allowed sizes.

Scrollable Android widgets keep their native vertical scrolling when the touch begins on scrollable provider content. A horizontal swipe can still change Home pages. Hold still before moving when you intend to pick up the widget.

## Background and appearance

In **Wallpaper & appearance**, **Choose a photo** creates a private preview. It does not replace the current launcher background until you choose **Apply**; **Cancel** keeps the committed background. If selection is interrupted, choose **Resume** or **Cancel**. Recovery has been checked for activity recreation and a completed private preview file, but an interruption during the earlier decode step may require selecting the photo again.

**Preview Android wallpaper** opens Android's separate wallpaper preview. It does not change Duo's **Launcher background**. **Reset to Duo dunes** removes the selected launcher background.

Appearance choices are **Light**, **Dark**, **Follow system**, and **Sunrise / sunset**. Sunrise/sunset accepts coordinates through **Use this place**, or requests approximate location only when you choose **Use device location**. If location is unavailable, Duo visibly falls back to the system theme. **Clear location** removes saved coordinates; Duo does not request location in the background.

## Optional shade gestures

On Home, swipe down from the left 70% to open Notifications or from the right 30% to open Quick Settings. The first attempt offers **Turn on shade gestures** because Android requires you to enable Duo Launcher in Accessibility settings. This is optional and must be enabled by you; **Not now** leaves it off. The service only requests the system panel actions.

## Layout backup

Open **Backup**, choose **Save**, and select a document destination. Choose **Restore** to select a backup, inspect **Review restored layout**, then choose **Restore** again. **Cancel** leaves Home unchanged.

Backups contain Home and dock positions, folders, widget descriptions and spaces, layout presets, labels, search behavior, and status settings. They include the unfolded-only workspace. They do not include the selected background photo or live Android widget bindings. After restore, provider widgets keep their saved space but require **Reconnect**; unavailable apps leave empty positions, and work-profile entries may need manual placement. Review a backup before sharing because it can expose app names, folder names, and profile metadata.
