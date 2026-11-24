package dev.deusbr.luminaa55.online

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.deusbr.luminaa55.context.PhotographyCrewDirector
import dev.deusbr.luminaa55.model.GeminiOnlineMode
import dev.deusbr.luminaa55.model.PhotometricStats
import dev.deusbr.luminaa55.model.PhotographyCrewSheet
import dev.deusbr.luminaa55.model.ProcessingEngine
import dev.deusbr.luminaa55.model.SceneConcept
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

data class GeminiOnlineResult(
    val generatedBitmap: Bitmap?,       // Non-null when Gemini generated a full studio image directly
    val calibratedConcept: SceneConcept,
    val statusNote: String,
    val usedGenerativeImage: Boolean
)

/**
 * Online Studio Engine powered by Google Gemini API.
 * Never embeds any API key in source code: users store their own Gemini key locally on device.
 * Supports:
 * - Full generative studio relighting (gemini-3-pro-image-preview / gemini-3.1-flash-image-preview / gemini-2.5-flash-image)
 * - Online 6-agent Vision Crew Director calibrating the on-device GPU engine (works on Free Tier too)
 */
class GeminiStudioService(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("lumina_gemini_prefs", Context.MODE_PRIVATE)

    fun getSavedApiKey(): String = prefs.getString(KEY_GEMINI_API, "")?.trim().orEmpty()

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API, key.trim()).apply()
    }

    fun hasApiKey(): Boolean = getSavedApiKey().isNotBlank()

    suspend fun enhanceWithGemini(
        sourceBitmap: Bitmap,
        baseConcept: SceneConcept,
        stats: PhotometricStats,
        labels: List<String>,
        faceCount: Int,
        mode: GeminiOnlineMode,
        hasNeuralAlpha: Boolean
    ): GeminiOnlineResult = withContext(Dispatchers.IO) {
        val apiKey = getSavedApiKey()
        require(apiKey.isNotBlank()) { "Configure sua Chave API do Gemini nas opções do Motor Online." }

        val baseSheet = baseConcept.crewSheet ?: PhotographyCrewDirector.compileCrewSheet(
            concept = baseConcept,
            stats = stats,
            labels = labels,
            faceCount = faceCount,
            engine = ProcessingEngine.ONLINE_GEMINI,
            hasNeuralAlpha = hasNeuralAlpha
        )

        val encodedJpeg = encodeDownscaledJpegBase64(sourceBitmap, maxDim = if (mode.outputsImageDirectly) 1536 else 1024)

        if (mode.outputsImageDirectly) {
            val imagePrompt = PhotographyCrewDirector.buildGeminiGenerativePrompt(baseSheet, baseConcept)
            val candidates = listOf(
                mode.modelId,
                "gemini-3.1-flash-image-preview",
                "gemini-3.1-flash-image",
                "gemini-2.5-flash-image",
                "gemini-3-pro-image-preview",
                "gemini-3-pro-image"
            ).distinct()
            var lastQuotaError: String? = null
            var lastHttpError: String? = null

            for (modelId in candidates) {
                val resp = postGenerateContent(
                    apiKey = apiKey,
                    modelId = modelId,
                    textPrompt = imagePrompt,
                    base64Jpeg = encodedJpeg,
                    responseModalities = listOf("TEXT", "IMAGE"),
                    responseMimeType = null
                )
                if (resp.isOk) {
                    val imgBmp = resp.imageBytes?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    if (imgBmp != null) {
                        val updatedSheet = baseSheet.copy(
                            engineUsedLabel = "Online · $modelId",
                            antiAiAuditStatus = "Auditor Anti-IA (Gemini): Revelação gerada na nuvem com trava biométrica."
                        )
                        return@withContext GeminiOnlineResult(
                            generatedBitmap = imgBmp,
                            calibratedConcept = baseConcept.copy(crewSheet = updatedSheet),
                            statusNote = "Gemini Estúdio ($modelId): revelação gerada na nuvem.",
                            usedGenerativeImage = true
                        )
                    }
                } else if (resp.httpCode == 429) {
                    lastQuotaError = resp.errorMessage
                } else if (resp.httpCode == 400 || resp.httpCode == 401 || resp.httpCode == 403) {
                    error("Chave Gemini recusada (HTTP ${resp.httpCode}): ${resp.errorMessage}")
                } else {
                    lastHttpError = "HTTP ${resp.httpCode}: ${resp.errorMessage}"
                }
            }

            if (lastQuotaError != null) {
                error("Sua chave Gemini está sem cota de geração de imagem (HTTP 429). Ative o faturamento no Google AI Studio ou selecione 'Gemini Diretor + GPU Local' na chave (🔑).")
            }
            error("Gemini Estúdio falhou (${lastHttpError ?: "sem resposta de imagem"}). Tente novamente ou use o Motor Offline.")
        }

        return@withContext runVisionDirectorOnline(
            apiKey = apiKey,
            base64Jpeg = encodedJpeg,
            baseConcept = baseConcept,
            baseSheet = baseSheet,
            stats = stats
        )
    }

    private fun runVisionDirectorOnline(
        apiKey: String,
        base64Jpeg: String,
        baseConcept: SceneConcept,
        baseSheet: PhotographyCrewSheet,
        stats: PhotometricStats
    ): GeminiOnlineResult {
        val prompt = PhotographyCrewDirector.buildGeminiVisionDirectorPrompt(baseConcept, stats)
        val candidates = listOf("gemini-2.5-flash-lite", "gemini-3-flash-preview", "gemini-3.8-flash", "gemini-flash-latest")
        var lastError = "Falha de conexão com Gemini"

        for (modelId in candidates) {
            val resp = postGenerateContent(
                apiKey = apiKey,
                modelId = modelId,
                textPrompt = prompt,
                base64Jpeg = base64Jpeg,
                responseModalities = null,
                responseMimeType = "application/json"
            )
            if (resp.isOk && !resp.textContent.isNullOrBlank()) {
                val parsed = parseDirectorCalibration(resp.textContent, baseConcept, baseSheet, modelId)
                return GeminiOnlineResult(
                    generatedBitmap = null,
                    calibratedConcept = parsed,
                    statusNote = "Banca Gemini Vision ($modelId) calibrou ótica, luz 3D e filme na GPU.",
                    usedGenerativeImage = false
                )
            }
            if (resp.httpCode == 401 || resp.httpCode == 403) {
                error("Chave Gemini inválida ou sem permissão (HTTP ${resp.httpCode}).")
            }
            lastError = "HTTP ${resp.httpCode}: ${resp.errorMessage}"
        }
        error("Gemini indisponível no momento ($lastError).")
    }

    private fun parseDirectorCalibration(
        rawJson: String,
        baseConcept: SceneConcept,
        baseSheet: PhotographyCrewSheet,
        modelId: String
    ): SceneConcept {
        val cleaned = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            val obj = JSONObject(cleaned)
            val r = baseConcept.recipe
            val allowPortraitBokeh = baseConcept.recipe.backgroundBokeh > 0.05f
            val updatedRecipe = r.copy(
                exposureOffset = obj.optDouble("exposureOffset", r.exposureOffset.toDouble()).toFloat().coerceIn(-0.25f, 0.35f),
                contrast = obj.optDouble("contrast", r.contrast.toDouble()).toFloat().coerceIn(1.02f, 1.28f),
                dehazeAmount = obj.optDouble("dehazeAmount", r.dehazeAmount.toDouble()).toFloat().coerceIn(0.01f, 0.11f),
                warmth = obj.optDouble("warmth", r.warmth.toDouble()).toFloat().coerceIn(-0.08f, 0.09f),
                tint = obj.optDouble("tint", r.tint.toDouble()).toFloat().coerceIn(-0.05f, 0.05f),
                saturation = obj.optDouble("saturation", r.saturation.toDouble()).toFloat().coerceIn(0.92f, 1.16f),
                faceRelightGain = obj.optDouble("faceRelightGain", r.faceRelightGain.toDouble()).toFloat().coerceIn(0f, 0.36f),
                relight3dStrength = obj.optDouble("relight3dStrength", r.relight3dStrength.toDouble()).toFloat().coerceIn(0f, 1.05f),
                backgroundBokeh = if (allowPortraitBokeh) obj.optDouble("backgroundBokeh", r.backgroundBokeh.toDouble()).toFloat().coerceIn(0f, 1.0f) else 0f,
                backgroundDimming = if (allowPortraitBokeh) obj.optDouble("backgroundDimming", r.backgroundDimming.toDouble()).toFloat().coerceIn(0f, 0.45f) else 0f,
                studioBackdropStrength = if (allowPortraitBokeh) obj.optDouble("studioBackdropStrength", r.studioBackdropStrength.toDouble()).toFloat().coerceIn(0f, 0.65f) else 0f,
                clarity = obj.optDouble("clarity", r.clarity.toDouble()).toFloat().coerceIn(0.08f, 0.32f),
                keyDirectionSign = if (obj.optDouble("keyDirectionSign", r.keyDirectionSign.toDouble()) >= 0.0) 1.0f else -1.0f
            )
            val updatedSheet = baseSheet.copy(
                directorDecision = obj.optString("directorDecision", baseSheet.directorDecision).ifBlank { baseSheet.directorDecision },
                cameraLensSpec = obj.optString("cameraLensSpec", baseSheet.cameraLensSpec).ifBlank { baseSheet.cameraLensSpec },
                lightingPlan = obj.optString("lightingPlan", baseSheet.lightingPlan).ifBlank { baseSheet.lightingPlan },
                consistencyGuardLock = obj.optString("consistencyGuardLock", baseSheet.consistencyGuardLock).ifBlank { baseSheet.consistencyGuardLock },
                antiAiAuditStatus = obj.optString("antiAiAuditStatus", baseSheet.antiAiAuditStatus).ifBlank { baseSheet.antiAiAuditStatus },
                engineUsedLabel = "Online · $modelId + GPU"
            )
            baseConcept.copy(
                conceptTitle = obj.optString("conceptTitle", baseConcept.conceptTitle).ifBlank { baseConcept.conceptTitle },
                conceptSummary = updatedSheet.directorDecision,
                recipe = updatedRecipe,
                crewSheet = updatedSheet
            )
        }.getOrElse {
            baseConcept.copy(crewSheet = baseSheet.copy(engineUsedLabel = "Online · $modelId (fallback)"))
        }
    }

    private data class HttpGeminiResponse(
        val isOk: Boolean,
        val httpCode: Int,
        val textContent: String? = null,
        val imageBytes: ByteArray? = null,
        val errorMessage: String = ""
    )

    private fun postGenerateContent(
        apiKey: String,
        modelId: String,
        textPrompt: String,
        base64Jpeg: String,
        responseModalities: List<String>?,
        responseMimeType: String?
    ): HttpGeminiResponse {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", textPrompt))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Jpeg)
                        }))
                    })
                })
            })
            val genCfg = JSONObject()
            if (responseModalities != null) {
                genCfg.put("responseModalities", JSONArray(responseModalities))
            }
            if (responseMimeType != null) {
                genCfg.put("responseMimeType", responseMimeType)
            }
            if (genCfg.length() > 0) {
                put("generationConfig", genCfg)
            }
        }

        return try {
            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bodyStr = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(bodyStr).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault(bodyStr.take(180))
                return HttpGeminiResponse(isOk = false, httpCode = code, errorMessage = msg.take(180))
            }

            val json = JSONObject(bodyStr)
            val parts = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: JSONArray()

            var textOut: String? = null
            var imageOut: ByteArray? = null
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.has("text") && textOut == null) {
                    textOut = p.optString("text")
                }
                val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                val dataStr = inline?.optString("data")
                if (!dataStr.isNullOrBlank() && imageOut == null) {
                    imageOut = Base64.decode(dataStr, Base64.DEFAULT)
                }
            }
            HttpGeminiResponse(isOk = true, httpCode = code, textContent = textOut, imageBytes = imageOut)
        } catch (t: Throwable) {
            HttpGeminiResponse(isOk = false, httpCode = -1, errorMessage = t.message ?: "Erro de rede")
        } finally {
            conn.disconnect()
        }
    }

    private fun encodeDownscaledJpegBase64(bitmap: Bitmap, maxDim: Int): String {
        val longEdge = max(bitmap.width, bitmap.height)
        val scaled = if (longEdge > maxDim) {
            val scale = maxDim.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                bitmap,
                max(16, (bitmap.width * scale).toInt()),
                max(16, (bitmap.height * scale).toInt()),
                true
            )
        } else bitmap

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 92, baos)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val KEY_GEMINI_API = "gemini_api_key"
    }
}
