# Duo Open

The iPhone "Duo" frosted-glass fold, playing system-wide on a book-style
foldable as you open and close it. Driven by the real hinge angle — no root.
Built and tested on the OnePlus Open; should work on other Android 13+
foldables with a hinge sensor (Pixel Fold, Galaxy Z Fold, OPPO Find N…) but
those are untested — reports welcome.

Based on the AGSL shader from
[Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation).

## What it does

Each half of the screen acts as a pane of frosted glass hinged at the crease.
While the phone is partly folded the moving half is blurred and darkened by
how far it is from flat; as the hinge reaches 180° the picture settles into
focus. Both panels take part: the cover screen frosts in over the first ~20°
of an open, then the inner screen picks up frosted and clears. Closing plays
it in reverse.

It works over *everything* — your own wallpaper, icons, widgets, the lock
screen, whatever app is open — because it runs as an accessibility service
that takes one screenshot per fold phase and draws it through the shader in a
touch-transparent overlay tracking the hinge. There's also a plain live
wallpaper mode if you'd rather not enable an accessibility service.

## Install

1. Download `DuoOpen-<version>.apk` from
   [Releases](../../releases) and install it.
2. Open **Duo Open** → **Tune** → **Turn on in Accessibility** → enable
   *Duo Open full-screen fold*.
   - Android 13+ blocks accessibility for sideloaded apps until you allow
     it: if the toggle is greyed out, go to *Settings → Apps → Duo Open → ⋮
     (top right) → Allow restricted settings*, then try again.
3. Fold the phone partway and open it. **Tune → Test it now** replays the
   effect without folding.

The **Tune** sheet has strength, frost, darkening, eye distance, which half
moves (left/right/both), which edge the cover-screen frost comes from, and
a hinge simulator.

Wallpaper-only mode: **Set live wallpaper** in the app (home + lock screen).
Only the wallpaper folds in that mode; icons stay sharp.

## Privacy

The accessibility service takes a screenshot of the display each time a fold
phase starts and keeps it in memory only while the overlay is on screen.
Nothing is stored, logged or sent anywhere; the app has no network
permission. Screens the system marks secure (banking apps, DRM video) can't
be captured and the effect simply doesn't play there.

## Known limits

- Android allows one screenshot every ~333 ms, and a freshly-lit panel shows
  the system's own black-to-reveal for ~0.4 s first. On a fast flick the
  second phase (inner screen on open) may not have time to appear; you'll get
  the cover-screen phase only. Normal-speed folds get both.
- If you stop partway (tent mode) the overlay fades out after ~0.7 s so the
  live screen isn't hidden.
- Reinstalling the app turns the accessibility service off again.

## Build

```
./gradlew assembleDebug        # debug-signed
./gradlew assembleRelease      # signed with keystore.properties if present
```
Release signing reads `keystore.properties` in the project root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it the
release build uses the debug key.

Handy adb bits: enable the service with
`adb shell settings put secure enabled_accessibility_services com.duoopen/com.duoopen.overlay.FoldOverlayService`,
replay the effect with `adb shell am broadcast -a com.duoopen.DEMO`,
watch it with `adb logcat -s DuoOverlay`.

## Layout

```
app/src/main/res/raw/duo_unfold.agsl      fold shader (hinge line, moving side, eye)
app/src/main/res/raw/tri_unfold.agsl       three-pane tri-fold shader (left/center/right)
fold/DuoShader.kt                         uniforms, hinge→tilt mapping, fold placement
fold/TriShader.kt                         tri-fold uniforms, hinge→tilt, thirds geometry
fold/HingeAngleSource.kt                  TYPE_HINGE_ANGLE (wake-up fallback, vendor fallback)
fold/DualHingeSource.kt                   two-hinge discovery (H1/H2) for the Z TriFold
fold/TiltFollower.kt                      per-vsync ease that hides the sensor's 1° steps
fold/Panels.kt                            inner vs cover panel from the display mode
overlay/FoldOverlayService.kt             accessibility service: screenshot + overlay (book + tri)
overlay/FoldOverlayView.kt                draws the snapshot through the shader (half-res layer)
overlay/TriFoldOverlayView.kt             two-tilt overlay view for the tri-fold
wallpaper/DuoWallpaperService.kt          live wallpaper engine (book + tri)
wallpaper/WallpaperImage.kt               picked image / generated default
ui/                                       Compose app: preview, Tune sheet (book + tri)
settings/DuoSettings.kt                   shared tuning (SharedPreferences + StateFlow)
```

## OnePlus Open notes

- Inner panel 2268×2440, fold splits the short side; display 0 swaps between
  the cover (1116×2484) and inner panels at ~10–30° depending on speed.
- The hinge sensor is wake-up only and sends nothing on registration, goes
  quiet at ~30° during a close, and idles anywhere from 0–5° when shut. The
  service compensates for all three.

## Samsung Galaxy Z TriFold notes

> **Status: does not work on a real Z TriFold.** The two per-hinge angle
> sensors require `com.samsung.permission.SSENSOR` (signature|privileged),
> and the public `hinge_angle` sensor only reports 0 or 180. See
> [TRIFOLD_FINDINGS.md](TRIFOLD_FINDINGS.md) for the full investigation.
> The code below is complete and should work if that permission ever becomes
> obtainable (root / priv-app).

The tri-fold has a U-shaped fold: two hinges (left H1, right H2) and three
panels (left / center / right). The app keeps the original book-fold path and
adds a tri-fold path selected at runtime by hinge-sensor count — two or more
hinge sensors run the three-pane shader; one or none falls back to the
two-pane behavior above.

- **Sensor discovery**: `SensorManager.getDefaultSensor(TYPE_HINGE_ANGLE)`
  returns only one hinge even on a two-hinge device, so the full sensor list is
  enumerated. Left (H1) vs right (H2) is inferred from the sensor name
  ("1"/"left"/"h1" → left, "2"/"right"/"h2" → right), falling back to
  enumeration order. Verify and correct the mapping with
  `adb shell dumpsys sensorservice | grep -i hinge` and
  `adb logcat -s DuoHinge` (logs the discovered hinge sensors and their
  left/right assignment at service connect).
- **Shader**: three vertical regions; the center pane is anchored and crisp,
  the left pane frosts/darkens from H1, the right pane from H2. The right pane
  clears first during an unfold (H2 opens 0→180), then the left (H1 follows).
- **State machine**: one screenshot is captured the moment either hinge
  leaves 0°; the overlay holds across the full canvas piping both hinge angles
  to the shader, and drops only when both hinges reach ~178° (real hinge HALs
  rest short of 180°, so a literal 180° would never dismiss). If the fold parks
  partway the overlay fades out after ~1.2 s so the live screen isn't hidden.
- **Preview / Tune**: the in-app preview and Tune sheet show both hinge readouts
  and a Book/Tri simulate toggle, so the three-pane shader can be previewed on
  any device (or emulator) without two real hinges.

## License

MIT — see [LICENSE](LICENSE). The shader is adapted from
[Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation).
