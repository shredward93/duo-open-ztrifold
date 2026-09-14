# Galaxy Z TriFold (SM-F968U1) — hinge sensor findings

Status: **blocked**. A sideloaded app cannot get real-time hinge angles on the
Z TriFold, so the fold-tracking effect cannot run on this device. Details below
so nobody has to rediscover this.

Tested 2026-09-14 on SM-F968U1, One UI on Android 16.

## What the device exposes

`dumpsys sensorservice` lists these fold-related sensors:

| Sensor | Type | Permission |
|---|---|---|
| `hinge_angle` | `android.sensor.hinge_angle` (36) | none |
| `Folding Angle` | `com.samsung.sensor.folding_angle` (65686) | `com.samsung.permission.SSENSOR` |
| `Folding Angle INNER` | `com.samsung.sensor.folding_angle_sub` (65708) | `com.samsung.permission.SSENSOR` |
| `lid_angle_fusion` / `folding_state*` | `com.samsung.sensor.folding_state*` | `com.samsung.permission.SSENSOR` |
| `seq_fold_mon` | `com.samsung.sensor.seq_fold_mon` (65710) | `com.samsung.permission.SSENSOR` |

The two `Folding Angle` sensors are the per-hinge angles. Registering a
listener on them from a normal app fails:

```
E SensorService: com.duoopen Tried enabling a sensor (Folding Angle INNER Non-wakeup) without holding com.samsung.permission.SSENSOR
```

`com.samsung.permission.SSENSOR` is `signature|privileged`. Only apps signed
with Samsung's platform key or installed as privileged system apps (root /
custom ROM) can hold it. Samsung Developer Support confirmed there is no SDK
or partner program that grants it (support request #00036662, closed as out
of scope).

## The public `hinge_angle` sensor is binary

The standard `android.sensor.hinge_angle` sensor is readable without any
permission, but on this device it is **not an angle**. Every event in the
system's history is exactly `0.0` or `180.0`:

```
hinge_angle  Wakeup: last 21 events
   ... 0.00,  180.00,  0.00,  0.00,  180.00,  0.00,  180.00, ...
```

It only fires when the device becomes fully closed or fully open. Folding a
single panel produces no events at all. So it is an open/closed switch, not a
fold-progress signal. `DeviceStateManager` gives the same information at
posture granularity (`CLOSED`, `TENT`, `HALF_OPENED`, `OPENED`, …) and the
intermediate states are marked `app_accessible=false`.

## Why a scripted animation on the open event doesn't work either

The fallback in `FoldOverlayService.onFallbackHingeTri()` plays a fixed
unfold curve when the sensor flips 0 → 180. It runs, but it isn't visible:

1. Samsung's own unfold transition blurs the inner display and fades content
   in over roughly a second after the device reports fully open.
2. The `180` event arrives at the start of that transition. The screenshot
   we capture is Samsung's flat grey blur frame, not the app content
   (see below — captured 500 ms after our overlay went up, keyguard not
   showing).
3. Blurring an already-blurred frame produces nothing visible, and our
   overlay fades out around the time Samsung's transition finishes.

![](docs/trifold-unfold-frame.png)

On close, the outer display does not turn on until both panels are fully
closed, so there is no window to draw anything.

A post-unfold flourish (wait for the grey transition frame to pass, then
play the effect on real content) would be visible but reads as a second blur
wave after Samsung's, so it was not pursued.

## Other gotchas hit along the way

- **Samsung freezes background processes**, including bound accessibility
  services (`FreecessController: BG freezed`). The service stayed "enabled"
  but received no sensor callbacks until the app was exempted:
  `adb shell dumpsys deviceidle whitelist +com.duoopen` (or Settings →
  Battery → app → Unrestricted).
- `adb exec-out screencap` needs `-d <physical display id>` on this device;
  `-d 0` fails. Get ids from `dumpsys SurfaceFlinger --display-id`.
- `dumpsys sensorservice` masks sensor values for privileged sensors, so
  ADB cannot be used to peek at the Samsung angles either.

## What would unblock this

- Root / a custom ROM that installs the app as `priv-app` with
  `com.samsung.permission.SSENSOR` allowlisted, or
- Samsung making `hinge_angle` report real angles on the TriFold, or
- a Samsung partner signing arrangement (not available to individuals).

The tri-fold code path (`DualHingeSource`, `TriShader`, `TriFoldOverlayView`,
`tri_unfold.agsl`) is written to consume the two Samsung angle sensors and
should work as-is if one of the above ever becomes possible.
