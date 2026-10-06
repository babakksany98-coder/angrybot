# AngryBot V2 changes

This version is an incremental upgrade of the supplied AngryBot project.

## Changes
- Accessibility drag is now emitted as one atomic press -> drag -> hold -> release gesture.
- Added minimum confidence gates before executing a shot.
- Result evaluation is more conservative and uses the level detector when declaring a clear.
- Planner returns up to seven more-diverse candidates and gives direct pig impacts stronger scores.
- Added a short post-shot delay before the next planning pass.
- Slightly improved pig/structure confidence scoring.

## Important limitation
The project is still a heuristic computer-vision/physics prototype. It does not contain a trained neural object detector and it does not know the exact physics constants of every Angry-Birds-like game. A real device/game test is required to tune HSV rules, drag scale, gravity, and timing.

## Build verification
The source was statically checked for basic Kotlin delimiter consistency. A full Gradle build could not be executed in this environment because the Gradle distribution/dependencies are not available offline.

# V3 (Gemini assist)
- New `ai/` package: `GeminiPlanner` (REST, strict JSON schema, retries), `GridOverlay` (0..1000 labeled grid + numbered
  candidate trajectories), `NavGuard` (click safety filter), `GeminiModels`.
- Hybrid decision: local CV + physics produce the candidate shots; Gemini classifies the screen and picks the best candidate
  (or gives a validated direct drag if CV found nothing). If Gemini fails 5 times in a row the bot continues with local vision only.
- Screen states: PLAYING, LEVEL_COMPLETE, LEVEL_FAILED, OUT_OF_HEARTS, AD_PLAYING, MENU. Only plain Next/Retry/Play buttons are tapped.
  Out-of-hearts and ad screens pause the bot and wait for the user; the bot never taps ads, rewards, purchases, or close/skip buttons.
- `HumanMotion` + new `GestureExecutor`: curved Bezier drag, fast-start/slow-finish speed profile (chained continued strokes),
  tiny tremor, Gaussian timing. The final point is exactly the requested aim point.
- API key and model come from `local.properties` (`gemini.api.key`, `gemini.model`). No new Gradle dependencies.
