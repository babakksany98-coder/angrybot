# Angry Bot (on-device Angry-Birds-style auto player) — Kotlin, no OpenCV, no PC needed at runtime

## Architecture
Screen Capture (MediaProjection, ScreenCaptureService) -> HeuristicDetector (HSV classes + connected components,
resolution independent) -> GameState (+ LevelStateDetector) -> TrajectoryPlanner (angle x power grid, PhysicsPredictor,
5 diverse scored candidates) -> BotAccessibilityService (press-drag-hold-release) -> flight tracking (self-calibrates
launch speed) -> wait until scene settles -> new GameState -> ResultEvaluator (MoveResult) -> history -> next move.

## Structure
app/src/main/java/com/example/angrybot/
  model/ (Models.kt, BotModels.kt)  utils/ (Logger, ScreenUtil)  capture/ (ScreenCaptureService)
  vision/ (ColorRules, Segmenter, ObjectDetector, HeuristicDetector, LevelStateDetector, FrameDiff, BirdTracker)
  physics/ (PhysicsPredictor)  planner/ (TrajectoryPlanner, ResultEvaluator)
  automation/ (BotAccessibilityService, GestureExecutor)  bot/ (BotController)
  ui/ (MainActivity, OverlayManager, DebugRenderer)

## Build
1. Open the folder in Android Studio (Koala+ / JDK 17). If asked, use Gradle 8.7. Sync.
2. Build > Build APK(s), or terminal (after Android Studio generated the wrapper): ./gradlew assembleDebug
   APK: app/build/outputs/apk/debug/app-debug.apk

## Install (no PC needed after the APK exists)
Copy the APK to the phone, open it, allow "install unknown apps". (With a PC: adb install -r app-debug.apk)

## Enable Accessibility
Settings > Accessibility > Installed apps > "Angry Bot Gesture Service" > On.
Android 13 sideload: if switch is greyed out: Settings > Apps > Angry Bot > ⋮ > "Allow restricted settings", then retry.
A small floating panel (Start/Test/Dbg) appears top-left; that region is ignored by the detector.

## First test
1. Open app > "Screen Capture" > Start now.
2. Turn on Accessibility (above). Turn on Debug.
3. Open the game on a level, press the floating "Test": nothing is tapped. Check the preview/overlay: red box = bird,
   green = pigs, orange = wood, grey = stone, cyan = glass, yellow line = best path, red X = impact.
4. Enable "Simulation Mode" to see the loop run without gestures. Then disable it and press "Start".

## Debugging
- Log panel in app (and `adb logcat -s AngryBot` if available). Tags: CAPTURE, DETECTION, PLANNER, EXECUTOR, PHYSICS, RESULT.
- Nothing detected: tune HSV rules in vision/ColorRules.kt (game skin/theme differs).
- Shots land short/long: watch "[PHYSICS] Calibrated speedScale"; it needs 2-3 shots. Adjust PhysicsParams.defaults for gravity/maxDrag.
- Gesture ignored: increase dragDurationMs/holdMs in BotSettings.
- Frames black: some games block capture (FLAG_SECURE).
- Better detector: implement ObjectDetector (e.g. TFLite) and swap it in BotController.


## V2 changes
- Atomic press-drag-hold-release Accessibility gesture.
- Conservative result evaluation to reduce false kills from temporary detector misses.
- Minimum confidence gates before firing.
- More diverse trajectory candidates and stronger direct-pig scoring.
- Post-shot settling delay before the next planning pass.

This remains a heuristic CV/physics prototype: visual detection and game physics are approximate and may require tuning for a specific game/version/device.

## V3: Gemini assist
1. Copy `local.properties.example` to `local.properties`, set `gemini.api.key` and (optionally) `gemini.model`. Sync Gradle.
2. In the app, keep the "Gemini assist" switch on. The status panel shows the model in use.
3. Out-of-hearts / ad screens: the bot pauses and waits for you (it never taps ads or reward buttons) and resumes when gameplay returns.
4. Logs: `[PLANNER] [Gemini <model>] state=... action=... :: reason`.
Not verified on a device: the chained-stroke drag (`continueStroke`) and Gemini's choices need real-game testing; tune `dragDurationMs`/`holdMs` if a launch is ignored.
