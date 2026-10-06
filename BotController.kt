package com.example.angrybot.bot

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.example.angrybot.ai.AiAction
import com.example.angrybot.ai.GeminiDecision
import com.example.angrybot.ai.GeminiException
import com.example.angrybot.ai.GeminiPlanner
import com.example.angrybot.ai.GridOverlay
import com.example.angrybot.ai.NavGuard
import com.example.angrybot.ai.ScreenState
import com.example.angrybot.automation.GestureExecutor
import com.example.angrybot.automation.HumanMotion
import com.example.angrybot.capture.ScreenCaptureService
import com.example.angrybot.model.BotSettings
import com.example.angrybot.model.BotUiState
import com.example.angrybot.model.Candidate
import com.example.angrybot.model.DebugSnapshot
import com.example.angrybot.model.GameState
import com.example.angrybot.model.LevelStatus
import com.example.angrybot.model.ObjectType
import com.example.angrybot.model.Vec2
import com.example.angrybot.physics.PhysicsParams
import com.example.angrybot.physics.PhysicsPredictor
import com.example.angrybot.planner.ResultEvaluator
import com.example.angrybot.planner.TrajectoryPlanner
import com.example.angrybot.ui.DebugRenderer
import com.example.angrybot.utils.Logger
import com.example.angrybot.utils.Logger.Tag
import com.example.angrybot.vision.BirdTracker
import com.example.angrybot.vision.DetectionConfig
import com.example.angrybot.vision.FrameDiff
import com.example.angrybot.vision.HeuristicDetector
import com.example.angrybot.vision.LevelStateDetector
import com.example.angrybot.vision.ObjectDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.sqrt

/** Orchestrates: capture -> detect -> plan -> gesture -> track -> settle -> evaluate -> repeat. */
object BotController {

    val ui = MutableStateFlow(BotUiState())
    val settings = MutableStateFlow(BotSettings())
    val debug = MutableStateFlow<DebugSnapshot?>(null)

    @Volatile var ignoreRects: List<Rect> = emptyList()
    @Volatile var overlayVisibility: ((Boolean) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val detector: ObjectDetector = HeuristicDetector()
    private val levelDetector = LevelStateDetector()
    private val tracker = BirdTracker()
    private var physics: PhysicsPredictor? = null
    private var planner: TrajectoryPlanner? = null
    val gemini = GeminiPlanner()
    private var lastAiCall = 0L
    private var aiFailures = 0
    private var sizeW = 0
    private var sizeH = 0

    private fun now() = SystemClock.elapsedRealtime()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                runLoop()
            } finally {
                ui.update { it.copy(running = false) }
                overlayVisibility?.invoke(true)
                Logger.log(Tag.SYSTEM, "Bot stopped")
            }
        }
    }

    fun stop() { job?.cancel() }

    /** Test mode: one analysis + plan, never performs a gesture. */
    fun testOnce() {
        if (job?.isActive == true) return
        scope.launch {
            Logger.log(Tag.SYSTEM, "Test mode: single analysis, no gesture")
            levelDetector.reset()
            val cd = captureAndDetect() ?: return@launch
            val (frame, state) = cd
            publish(frame, state, plannerFor(state).plan(state))
            frame.recycle()
        }
    }

    private fun plannerFor(state: GameState): TrajectoryPlanner {
        var pl = planner
        if (pl == null || physics == null || sizeW != state.width || sizeH != state.height) {
            val ph = PhysicsPredictor(PhysicsParams.defaults(state.width, state.height))
            physics = ph
            pl = TrajectoryPlanner(ph)
            planner = pl
            sizeW = state.width
            sizeH = state.height
            Logger.log(Tag.PHYSICS, "Physics initialised for ${sizeW}x$sizeH")
        }
        return pl
    }

    private suspend fun captureAndDetect(): Pair<Bitmap, GameState>? {
        val cap = ScreenCaptureService.instance
        if (cap == null) {
            Logger.log(Tag.CAPTURE, "Screen capture is not active")
            return null
        }
        val frame: Bitmap?
        if (settings.value.debug && overlayVisibility != null) {
            val t0 = now()
            overlayVisibility?.invoke(false)
            delay(80)
            frame = cap.awaitFrame(t0 + 30, 300)
            overlayVisibility?.invoke(true)
        } else {
            frame = cap.grab()
        }
        if (frame == null) {
            Logger.log(Tag.CAPTURE, "No frame available yet")
            return null
        }
        Logger.log(Tag.CAPTURE, "Frame captured ${frame.width}x${frame.height}")
        val st = detector.detect(frame, DetectionConfig(ignoreRects))
        val state = st.copy(status = levelDetector.update(frame, st))
        state.activeBird?.let {
            Logger.log(Tag.DETECTION, "Bird detected at x=${it.centerX.toInt()} y=${it.centerY.toInt()} (birds visible: ${state.birds.size})")
        } ?: Logger.log(Tag.DETECTION, "No active bird found")
        state.pigs.forEach {
            Logger.log(Tag.DETECTION, "Pig detected at x=${it.centerX.toInt()} y=${it.centerY.toInt()} conf=${(it.confidence * 100).toInt()}%")
        }
        Logger.log(
            Tag.DETECTION,
            "Structures: ${state.structures.size}, ground y=${state.groundY.toInt()}, status=${state.status}"
        )
        ui.update {
            it.copy(
                levelStatus = state.status,
                detection = "birds=${state.birds.size} pigs=${state.pigs.size} structures=${state.structures.size}"
            )
        }
        return frame to state
    }

    private fun publish(frame: Bitmap, state: GameState, cands: List<Candidate>): Candidate? {
        val best = cands.firstOrNull()
        if (cands.isEmpty()) {
            Logger.log(Tag.PLANNER, "No candidate trajectories (need active bird + at least one pig)")
        } else {
            Logger.log(Tag.PLANNER, "${cands.size} candidate trajectories generated")
            cands.forEach {
                Logger.log(Tag.PLANNER, "Candidate ${it.id}: angle=%.0f power=%.0f%% score=%.1f hit=%s".format(it.angleDeg, it.power * 100, it.score, it.hitType))
            }
            Logger.log(Tag.PLANNER, "Best trajectory selected: candidate ${best!!.id}")
        }
        debug.value = DebugSnapshot(state, cands, best, DebugRenderer.renderBitmap(frame, state, cands, best))
        if (best != null) {
            ui.update {
                it.copy(
                    target = best.target?.let { t -> "${t.type} (${t.centerX.toInt()},${t.centerY.toInt()})" } ?: "-",
                    angle = best.angleDeg,
                    power = best.power,
                    confidence = state.activeBird?.confidence ?: 0f
                )
            }
        }
        return best
    }

    // ------------------------------------------------------------------ Gemini helpers

    private fun nx(v: Float, size: Int) = (v / size * 1000f).toInt()

    private fun buildHint(state: GameState, cands: List<Candidate>): String = buildString {
        appendLine("Screenshot ${state.width}x${state.height}px with a labeled 0..1000 grid. Return normalized 0..1000 coordinates only.")
        appendLine("CV summary: birds=${state.birds.size} pigs=${state.pigs.size} structures=${state.structures.size} cvStatus=${state.status}")
        state.activeBird?.let { appendLine("Active bird at (${nx(it.centerX, state.width)},${nx(it.centerY, state.height)})") }
        state.slingshot?.let { appendLine("Slingshot at (${nx(it.centerX, state.width)},${nx(it.centerY, state.height)})") }
        state.pigs.forEach { appendLine("Pig at (${nx(it.centerX, state.width)},${nx(it.centerY, state.height)}) conf=${(it.confidence * 100).toInt()}%") }
        if (cands.isEmpty()) {
            appendLine("Physics candidates: none (CV could not plan a shot). If the screen is PLAYING, give a direct DRAG with coordinates.")
        } else {
            appendLine("Physics candidates (id, angle, power, first thing hit, impact point, score):")
            cands.take(5).forEach {
                appendLine("  #${it.id} angle=${it.angleDeg.toInt()} power=${(it.power * 100).toInt()}% hit=${it.hitType} impact=(${nx(it.impact.x, state.width)},${nx(it.impact.y, state.height)}) score=${it.score.toInt()}")
            }
        }
    }

    /** Returns null when Gemini is unavailable/failed; the bot then falls back to pure CV. */
    private suspend fun askGemini(frame: Bitmap, state: GameState, cands: List<Candidate>, s: BotSettings): GeminiDecision? {
        if (!s.useGemini || !gemini.available || aiFailures >= 5) return null
        val wait = s.geminiMinIntervalMs - (now() - lastAiCall)
        if (wait > 0) delay(wait)
        lastAiCall = now()
        val img = GridOverlay.render(frame, state, cands)
        return try {
            val d = gemini.decide(img, buildHint(state, cands), s.geminiTimeoutMs)
            aiFailures = 0
            Logger.log(Tag.PLANNER, "[Gemini ${gemini.model}] state=${d.state} action=${d.action} cand=${d.candidateId} :: ${d.reason}")
            d
        } catch (e: GeminiException) {
            aiFailures++
            Logger.log(Tag.PLANNER, "Gemini error ($aiFailures/5): ${e.message}")
            if (aiFailures >= 5) Logger.log(Tag.PLANNER, "Gemini disabled for this session; using local vision only")
            null
        } finally {
            img.recycle()
        }
    }

    /** Chooses the shot: Gemini's pick among physics candidates, else the best CV candidate, else a validated direct drag. */
    private fun chooseShot(d: GeminiDecision?, cands: List<Candidate>, state: GameState): Candidate? {
        val cvBest = cands.firstOrNull()
        if (d != null && d.action == AiAction.DRAG) {
            val picked = cands.firstOrNull { it.id == d.candidateId }
            if (picked != null) {
                if (cvBest == null || picked.score >= cvBest.score * 0.6f) return picked
                Logger.log(Tag.PLANNER, "Gemini pick #${picked.id} scores far below CV best; keeping CV best")
            }
        }
        if (cvBest != null) return cvBest
        if (d != null && d.action == AiAction.DRAG && d.hasStart && d.hasEnd) return directShot(d, state)
        return null
    }

    /** Converts a raw Gemini drag into a Candidate after sanity checks (start near the bird, bounded pull length). */
    private fun directShot(d: GeminiDecision, state: GameState): Candidate? {
        val w = state.width.toFloat()
        val h = state.height.toFloat()
        val start = Vec2(d.startX / 1000f * w, d.startY / 1000f * h)
        var end = Vec2(d.endX / 1000f * w, d.endY / 1000f * h)
        val anchor = state.activeBird ?: state.slingshot
        val ok = if (anchor != null) start.dist(Vec2(anchor.centerX, anchor.centerY)) < 0.12f * minOf(w, h) * 1.6f
                 else start.x < 0.45f * w
        if (!ok) {
            Logger.log(Tag.PLANNER, "Direct Gemini drag rejected: start is not at the bird/slingshot")
            return null
        }
        val maxDrag = (physics?.params?.maxDragPx ?: (0.2f * h))
        val len = hypot(end.x - start.x, end.y - start.y)
        if (len < 0.03f * h) {
            Logger.log(Tag.PLANNER, "Direct Gemini drag rejected: pull too short")
            return null
        }
        if (len > maxDrag) {
            val k = maxDrag / len
            end = Vec2(start.x + (end.x - start.x) * k, start.y + (end.y - start.y) * k)
        }
        val angle = Math.toDegrees(Math.atan2((start.y - end.y).toDouble(), (start.x - end.x).toDouble())).toFloat()
        Logger.log(Tag.PLANNER, "Using direct Gemini drag (no physics candidate available)")
        return Candidate(0, start, end, angle, (len.coerceAtMost(maxDrag) / maxDrag), null, end, listOf(start, end), 0f, null, start.y)
    }

    private var navClicks = 0
    private var lastNavClick = 0L
    private var needsUserSince = 0L

    /** Pauses and waits for a human on ad / out-of-hearts screens. Never taps anything there. */
    private suspend fun waitForHuman(d: GeminiDecision): Boolean {
        if (needsUserSince == 0L) {
            needsUserSince = now()
            Logger.log(Tag.SYSTEM, "Paused: ${d.state}. The bot does not touch ads or purchase/heart screens - handle it yourself, it resumes automatically.")
        }
        ui.update { it.copy(lastAction = "Waiting for you: ${d.state}") }
        if (now() - needsUserSince > 10 * 60_000L) {
            Logger.log(Tag.SYSTEM, "No longer waiting (10 min). Bot stopped.")
            return false
        }
        delay(5000)
        return true
    }

    /** Result/menu screens: tap only plain game-flow buttons that pass NavGuard. Returns false to stop the bot. */
    private suspend fun handleNavigation(d: GeminiDecision, state: GameState, s: BotSettings): Boolean {
        if (NavGuard.isAllowedClick(d)) {
            if (navClicks >= 6) {
                Logger.log(Tag.SYSTEM, "Too many navigation taps without gameplay; stopping so you can check the game.")
                return false
            }
            if (now() - lastNavClick < 4000L) { delay(1500); return true }
            val p = Vec2(d.startX / 1000f * state.width, d.startY / 1000f * state.height)
            Logger.log(Tag.EXECUTOR, "Navigation tap on '${d.buttonLabel}' (${d.state})")
            if (s.simulation) {
                Logger.log(Tag.EXECUTOR, "Simulation mode: tap skipped")
            } else {
                overlayVisibility?.invoke(false)
                GestureExecutor.tap(p)
                overlayVisibility?.invoke(true)
            }
            navClicks++
            lastNavClick = now()
            ui.update { it.copy(lastAction = "Tapped '${d.buttonLabel}'") }
            delay(HumanMotion.delayMs(1800))
        } else {
            ui.update { it.copy(lastAction = "${d.state}: waiting") }
            delay(d.waitMs)
        }
        return true
    }

    // ------------------------------------------------------------------ main loop

    private suspend fun runLoop() {
        Logger.log(Tag.SYSTEM, "Bot started")
        if (settings.value.useGemini) {
            Logger.log(Tag.SYSTEM, if (gemini.available) "Gemini enabled (model ${gemini.model})" else "Gemini key missing (local.properties); using local vision only")
        }
        levelDetector.reset()
        planner?.reset()
        aiFailures = 0
        navClicks = 0
        needsUserSince = 0L
        ui.update { it.copy(running = true, lastAction = "started") }

        while (currentCoroutineContext().isActive) {
            val s = settings.value
            val cd = captureAndDetect()
            if (cd == null) { delay(1000); continue }
            val (frame, state) = cd

            val bird = state.activeBird
            val reliableBird = bird != null && bird.confidence >= s.minBirdConfidence
            val reliablePigs = state.pigs.filter { it.confidence >= s.minPigConfidence }
            // Use only confidently detected pigs for this planning pass.
            val planningState = state.copy(objects = state.objects.filter {
                it.type != ObjectType.PIG || it.confidence >= s.minPigConfidence
            })
            val cands = if (reliableBird && reliablePigs.isNotEmpty()) plannerFor(planningState).plan(planningState) else emptyList()

            val ai = askGemini(frame, planningState, cands, s)

            // Local-vision-only terminal states keep the old behaviour.
            if (ai == null) {
                if (state.status == LevelStatus.LEVEL_COMPLETED || state.status == LevelStatus.LEVEL_FAILED) {
                    Logger.log(Tag.RESULT, if (state.status == LevelStatus.LEVEL_COMPLETED) "Level completed" else "Level failed")
                    ui.update { it.copy(lastAction = if (state.status == LevelStatus.LEVEL_COMPLETED) "Level completed" else "Level failed") }
                    frame.recycle()
                    break
                }
            } else {
                when (ai.state) {
                    ScreenState.OUT_OF_HEARTS, ScreenState.AD_PLAYING -> {
                        frame.recycle()
                        if (!waitForHuman(ai)) break
                        continue
                    }
                    ScreenState.LEVEL_COMPLETE, ScreenState.LEVEL_FAILED, ScreenState.MENU -> {
                        frame.recycle()
                        if (!handleNavigation(ai, planningState, s)) break
                        continue
                    }
                    ScreenState.PLAYING -> { needsUserSince = 0L; navClicks = 0 }
                    ScreenState.UNKNOWN -> {}
                }
                if (ai.action == AiAction.STOP) {
                    Logger.log(Tag.SYSTEM, "Gemini requested STOP: ${ai.reason}")
                    frame.recycle()
                    break
                }
                if (ai.action == AiAction.WAIT) {
                    frame.recycle()
                    delay(ai.waitMs)
                    continue
                }
            }

            val best = chooseShot(ai, cands, planningState)
            if (best == null) {
                Logger.log(Tag.DETECTION, "No usable shot yet (detection unreliable); waiting for another frame")
                frame.recycle()
                delay(s.loopDelayMs)
                continue
            }
            val ordered = listOf(best) + cands.filter { it !== best }
            publish(frame, planningState, ordered)
            frame.recycle()

            if (s.simulation) {
                Logger.log(Tag.EXECUTOR, "Simulation mode: gesture skipped")
                ui.update { it.copy(lastAction = "Simulated shot (no gesture)") }
                delay(2500)
                continue
            }
            if (s.debug) delay(s.debugShowMs)

            overlayVisibility?.invoke(false)
            ui.update { it.copy(lastAction = "Executing gesture") }
            val ok = GestureExecutor.drag(best.dragStart, best.dragEnd, s.dragDurationMs, s.holdMs)
            if (!ok) { delay(1000); continue }

            val track = trackFlight(best.dragStart)
            calibrate(best, track, state)
            waitSettled(s)

            val after = captureAndDetect() ?: continue
            after.first.recycle()
            val result = ResultEvaluator.evaluate(planningState, after.second, best)
            planner?.record(best, result)
            if (result.destroyedTargets > 0) {
                Logger.log(Tag.RESULT, "Target destroyed (${result.destroyedTargets}), remaining=${result.remainingTargets}")
            } else {
                Logger.log(Tag.RESULT, "No target destroyed; structures changed=${result.destroyedObjects}")
            }
            ui.update {
                it.copy(
                    lastAction = if (result.success) "Hit: -${result.destroyedTargets} pig(s)" else "Miss / retrying",
                    confidence = result.confidence
                )
            }
            delay(HumanMotion.delayMs(s.postShotDelayMs))
        }
    }

    private suspend fun trackFlight(origin: Vec2): List<Vec2> {
        val cap = ScreenCaptureService.instance ?: return emptyList()
        val pts = ArrayList<Vec2>()
        val end = now() + 2600
        var misses = 0
        delay(120)
        while (now() < end) {
            val f = cap.grab() ?: break
            val p = tracker.find(f, origin.x, ignoreRects)
            f.recycle()
            if (p != null) { pts.add(p); misses = 0 }
            else if (pts.isNotEmpty() && ++misses > 6) break
            delay(70)
        }
        return pts
    }

    /** Adjusts launch speed scale from the observed apex height of the real flight. */
    private fun calibrate(best: Candidate, track: List<Vec2>, state: GameState) {
        val ph = physics ?: return
        if (track.size < 5) {
            Logger.log(Tag.PHYSICS, "Not enough flight samples (${track.size}); calibration skipped")
            return
        }
        val h = state.height
        val originY = best.dragStart.y
        val apexObs = track.minOf { it.y }
        val lastObs = track.last().y
        val riseObs = originY - apexObs
        val risePred = originY - best.apexY
        val predDescending = best.trajectory.isNotEmpty() && best.trajectory.last().y > best.apexY + 0.03f * h
        if (risePred > 0.06f * h && riseObs > 0.04f * h && lastObs > apexObs + 0.03f * h && predDescending) {
            val ratio = (riseObs / risePred).coerceIn(0.4f, 2.5f)
            val factor = 1f + 0.6f * (sqrt(ratio) - 1f)
            ph.params.speedScale *= factor
            Logger.log(Tag.PHYSICS, "Calibrated speedScale x%.3f (observed rise %.0fpx vs predicted %.0fpx)".format(factor, riseObs, risePred))
        } else {
            Logger.log(Tag.PHYSICS, "Calibration skipped (apex not reliably observed)")
        }
    }

    private suspend fun waitSettled(s: BotSettings) {
        val cap = ScreenCaptureService.instance ?: return
        val start = now()
        var prev: Bitmap? = cap.grab()
        var stable = 0
        while (now() - start < s.settleTimeoutMs) {
            delay(300)
            val cur = cap.grab() ?: break
            val p = prev
            if (p != null) {
                stable = if (FrameDiff.ratio(p, cur) < 0.004f) stable + 1 else 0
                p.recycle()
            }
            prev = cur
            if (stable >= 3 && now() - start > 1500) break
        }
        prev?.recycle()
        Logger.log(Tag.RESULT, "Scene settled after ${now() - start} ms")
    }
}
