# Harbor launcher

A minimal home screen for Android TV / Google TV with a desktop-style layout:

- Translucent **menu bar** at the top: Apps, Inputs, Wallpaper, Settings, plus the date and clock.
- Large thin **clock and date** over one of **25 original illustrated race-circuit wallpapers**. By default the wallpaper changes every 10 minutes with a slow crossfade. Turn that off or pick one under **Wallpaper** in the menu bar.
- A frosted **dock** at the bottom. The focused tile grows, its neighbours grow a little, and its name pops up above it.
- An **Apps** grid with every installed app (plus Live TV).

All artwork is drawn by the app itself. No third-party icons, fonts or images are used.

## Remote controls

| Where | Button | What happens |
|---|---|---|
| Dock | OK | Open the app |
| Dock | Hold OK, or Menu | Open / Move left / Move right / Remove from Dock |
| Apps grid | OK | Open the app |
| Apps grid | Hold OK, or Menu | Add to or remove from the Dock (a blue check means it's in the Dock) |
| Anywhere | Up / Down | Move between the menu bar and the dock |
| Anywhere | Back | Close the open menu or grid |
| Anywhere | Home | Return to the dock |

The dock starts with Apps, Netflix, Prime Video, YouTube and Live TV, then Inputs and Settings.

**Inputs** lists the TV's inputs (HDMI 1-4, antenna, and so on) as the system reports them, and switches to the one you pick.
**Live TV** opens the TV's antenna tuner if it exposes one, or else a known Live TV app. If it can't find one, it opens the Inputs menu. You can also pin your TV's own Live TV app from the Apps grid.

## Install (from a Mac with adb)

```sh
adb connect 192.168.0.146:5555
adb install -r Harbor.apk
```

### Making the Home button open Harbor

On Google TV the built-in home screen is a system app that always wins the Home role, and disabling it breaks
the remote's Netflix/Prime/YouTube buttons. So Harbor includes a small accessibility service,
**Harbor Home button**, that catches the Home key and opens Harbor. Projectivy uses the same approach.
It reads nothing on screen.

Turn it on either from Harbor's menu bar (**Set up Home button**, shown only while it's off), under
Settings > Accessibility, or over adb:

```sh
adb shell settings put secure enabled_accessibility_services dev.harbor.launcher/dev.harbor.launcher.HomeButtonService
adb shell settings put secure accessibility_enabled 1
```

(That replaces the list of enabled services. If Projectivy's override is still on, turn it off first so the two don't fight.)

To go back, turn the service off and switch Projectivy's override back on. `adb uninstall dev.harbor.launcher` removes Harbor completely.

## Build

The app doesn't need Gradle or Android Studio. On Debian/Ubuntu:

```sh
sudo apt-get install android-sdk-platform-23 android-sdk-build-tools dalvik-exchange apksigner zipalign openjdk-17-jdk
./build.sh            # writes build/Harbor.apk
```

`keystore/harbor.jks` (password `harbor`) is the signing key. Keep it: an update installs over the old version only when it's signed with the same key.

## Source layout

- `src/dev/harbor/launcher/MainActivity.java`: the screen, the dock, the menus, the Apps grid and app launching
- `WallpaperView.java`: loads the bundled wallpapers (`assets/wallpapers`) and crossfades between them
- `tools/make_wallpapers.py`: draws the 25 wallpapers from scratch (needs Pillow), so there are no image licences to worry about
- `TileView.java`: rounded app tiles and the drawn icons for Apps, Inputs, Settings and Live TV
- `tools/make_icons.py`: generates the launcher icon and TV banner (needs Pillow)

## Download

The latest signed build is [`releases/Harbor.apk`](releases/Harbor.apk) (version 1.2).
The signing key isn't in this repository. It's kept privately, so a fresh clone builds with a new key, and
that build can't install over the copy on the TV without uninstalling first.
