package com.example.angrybot.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.angrybot.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a (grid-annotated) screenshot plus a compact CV summary to Gemini and parses a strict JSON decision.
 *
 * Uses the public REST endpoint directly (HttpURLConnection + org.json), so there is no SDK version to keep in sync
 * and any newer Gemini model works by only changing `gemini.model` in local.properties.
 * The API key comes from local.properties (BuildConfig) and is sent in a header, never logged.
 */
class GeminiPlanner(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    val model: String = BuildConfig.GEMINI_MODEL
) {
    val available: Boolean get() = apiKey.isNotBlank()

    suspend fun decide(image: Bitmap, hint: String, timeoutMs: Long): GeminiDecision =
        withContext(Dispatchers.IO) {
            if (!available) throw GeminiException("No API key (set gemini.api.key in local.properties)", false)
            val body = buildBody(image, hint)
            var last: GeminiException? = null
            for (attempt in 0..2) {
                try {
                    return@withContext parse(post(body, timeoutMs))
                } catch (e: GeminiException) {
                    last = e
                    if (!e.retryable) throw e
                    delay(1200L * (attempt + 1))
                }
            }
            throw last ?: GeminiException("Gemini request failed", false)
        }

    // ---------------------------------------------------------------- request

    private fun buildBody(image: Bitmap, hint: String): JSONObject {
        val bos = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val parts = JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("text", hint))

        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig", JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 8192)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", schema())
            )
    }

    private fun enumProp(values: List<String>) =
        JSONObject().put("type", "STRING").put("enum", JSONArray(values))

    private fun schema(): JSONObject {
        val num = JSONObject().put("type", "NUMBER")
        val props = JSONObject()
            .put("state", enumProp(ScreenState.values().map { it.name }))
            .put("action", enumProp(AiAction.values().map { it.name }))
            .put("candidateId", JSONObject().put("type", "INTEGER"))
            .put("startX", num).put("startY", num).put("endX", num).put("endY", num)
            .put("buttonLabel", JSONObject().put("type", "STRING"))
            .put("waitMs", JSONObject().put("type", "INTEGER"))
            .put("reason", JSONObject().put("type", "STRING"))
        val required = JSONArray(
            listOf(
                "state", "action", "candidateId", "startX", "startY", "endX", "endY",
                "buttonLabel", "waitMs", "reason"
            )
        )
        return JSONObject().put("type", "OBJECT").put("properties", props).put("required", required)
    }

    private fun post(body: JSONObject, timeoutMs: Long): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = timeoutMs.toInt()
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw GeminiException("HTTP $code: ${errorMessage(text)}", code == 429 || code >= 500)
            }
            return text
        } catch (e: GeminiException) {
            throw e
        } catch (e: java.io.IOException) {
            throw GeminiException("Network error: ${e.message}", true)
        } finally {
            c.disconnect()
        }
    }

    private fun errorMessage(text: String): String = try {
        JSONObject(text).getJSONObject("error").optString("message", "unknown error").take(200)
    } catch (e: Exception) {
        text.take(200)
    }

    // ---------------------------------------------------------------- response

    private fun parse(raw: String): GeminiDecision {
        val root = try { JSONObject(raw) } catch (e: Exception) {
            throw GeminiException("Response is not JSON", true)
        }
        val cands = root.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw GeminiException("No candidates returned" + if (block.isNotEmpty()) " (blocked: $block)" else "", false)
        }
        val partsArr = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw GeminiException("Empty response (finishReason=${cands.getJSONObject(0).optString("finishReason")})", true)
        val sb = StringBuilder()
        for (i in 0 until partsArr.length()) {
            val p = partsArr.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        val clean = sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val j = try { JSONObject(clean) } catch (e: Exception) {
            throw GeminiException("Model output was not valid JSON: ${clean.take(120)}", true)
        }
        return GeminiDecision(
            state = enumOr(j.optString("state"), ScreenState.UNKNOWN),
            action = enumOr(j.optString("action"), AiAction.WAIT),
            candidateId = j.optInt("candidateId", 0),
            startX = coord(j, "startX"), startY = coord(j, "startY"),
            endX = coord(j, "endX"), endY = coord(j, "endY"),
            buttonLabel = j.optString("buttonLabel", "").take(60),
            waitMs = j.optLong("waitMs", 2000L).coerceIn(500L, 10_000L),
            reason = j.optString("reason", "").take(300)
        )
    }

    private fun coord(j: JSONObject, key: String): Float {
        val v = j.optDouble(key, -1.0)
        return if (v.isNaN() || v < 0.0 || v > 1000.0) -1f else v.toFloat()
    }

    private inline fun <reified T : Enum<T>> enumOr(s: String, default: T): T =
        enumValues<T>().firstOrNull { it.name.equals(s.trim(), ignoreCase = true) } ?: default

    companion object {
        private val SYSTEM_PROMPT = """
You are the decision module of an on-device bot that plays a single-player slingshot physics game (Angry-Birds style).
You receive one screenshot with a labeled grid and a short text summary produced by a computer-vision pipeline.

COORDINATES
- The grid lines are labeled 0..1000 on both axes (x to the right, y downward). Every coordinate you return MUST use this normalized 0..1000 system. Use -1 for unused coordinates.
- Colored polylines with numbered badges are candidate shots computed by a physics simulator. The badge sits at the predicted impact point. Green boxes = pigs, red box = active bird, yellow = slingshot.

STEP 1 - classify the screen into exactly one state:
PLAYING (a level is in progress and a bird can be launched), LEVEL_COMPLETE (win / stars / next level screen),
LEVEL_FAILED (lose screen with retry), OUT_OF_HEARTS (no lives left, a screen offering to wait, buy, or watch a video for more),
AD_PLAYING (an advertisement or sponsored video is on screen), MENU (any other menu / map / dialog), UNKNOWN.

STEP 2 - choose the action:
- PLAYING: prefer action DRAG with candidateId = the id of the best listed candidate. Pick the candidate that kills the most pigs or topples a structure onto pigs, avoiding stone walls and wasted shots. If no candidate is good, or none are listed, return DRAG with startX/startY on the bird in the slingshot and endX/endY at the point you pull the bird back to (down-left of the bird; the bird flies in the opposite direction), and candidateId = 0.
- LEVEL_COMPLETE / LEVEL_FAILED / MENU: if there is a plain game-flow button (Next, Continue, Retry, Replay, Play, Start, OK), return CLICK with its center in startX/startY and its visible text in buttonLabel. Never choose any button that mentions ads, videos, rewards, free items, purchases, shops, offers, or closing/skipping an ad. If no such plain button exists, return WAIT.
- OUT_OF_HEARTS and AD_PLAYING: always return WAIT. Never click anything on these screens. A human handles them.
- UNKNOWN: WAIT.
- STOP: only if the game is clearly not a slingshot game or something is badly wrong.

Always fill every field: candidateId (0 if none), the four coordinates (-1 if unused), buttonLabel ("" if none), waitMs (500..10000; used for WAIT), and a one-sentence reason in English.
""".trimIndent()
    }
}
