package com.example.ecoaguacate

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

// ============================
// DOMINIO - define Modelos base y el resultado completo
// ============================

enum class VisionLabel { TRIPS, ARANA_ROJA, VERDE, HASS }

@Serializable
data class VisionResult(
    val imageId: String,
    val label: VisionLabel,
    val confidence: Double,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class Recommendation(
    val topic: String,
    val prioridad: String,
    val acciones: List<String>,
    val notas: List<String>
)

// ============================
// Dominio funcional (Advice) recomendaciones o comentario
// ============================
sealed class Advice {
    data class Rec(val recommendation: Recommendation) : Advice()
    data class Comment(val mensaje: String, val prioridad: String) : Advice()
}

// ============================
// Módulo Funcional Declarativo recomendaciones segun el resultado
// ============================
object RecommendationModule {

    // Bandas de confianza (puras, sin efectos)
    fun band(conf: Double): String = when {
        conf >= 0.80 -> "alto"
        conf >= 0.60 -> "medio"
        else -> "bajo"
    }

    // Catálogo declarativo de acciones por etiqueta (inmutable)
    private val pestCatalog: Map<VisionLabel, Recommendation> = mapOf(
        VisionLabel.TRIPS to Recommendation(
            topic = "plaga",
            prioridad = "alto",
            acciones = listOf(
                "Monitoreo en brotes/inflorescencias/frutos jóvenes",
                "Trampas cromáticas en bordes",
                "Intervenir temprano, antes del cuajado"
            ),
            notas = listOf("Verificar lineamientos locales antes de aplicar insumos")
        ),
        VisionLabel.ARANA_ROJA to Recommendation(
            topic = "plaga",
            prioridad = "alto",
            acciones = listOf(
                "Monitoreo con lupa y registro por hoja",
                "Manejo cultural para reducir estrés hídrico",
                "Considerar intervención si >100-500 ácaros/hoja"
            ),
            notas = listOf("Validar productos autorizados por autoridad local")
        )
    )

    private val ripenessCatalog: Map<VisionLabel, Recommendation> = mapOf(
        VisionLabel.VERDE to Recommendation(
            topic = "maduracion",
            prioridad = "medio",
            acciones = listOf(
                "Ventana estimada de consumo: 4-7 días",
                "Revisar firmeza/color diariamente",
                "Evitar golpes durante manejo"
            ),
            notas = listOf("Evitar frío extremo en fruta verde")
        ),
        VisionLabel.HASS to Recommendation(
            topic = "maduracion",
            prioridad = "medio",
            acciones = listOf(
                "Si el fruto está maduro: consumir en 1-3 días",
                "Si está verde: ventana 4-7 días",
                "Revisar firmeza/color antes del consumo"
            ),
            notas = listOf("En protocolos comerciales con etileno: 3-6 días (20°C, HR 90-95%)")
        )
    )

    // Pure helpers para aplicar la banda a una recomendación existente
    private fun withPriority(rec: Recommendation, conf: Double): Recommendation =
        rec.copy(prioridad = band(conf))

    // Regla declarativa: si la banda es "bajo" -> devolver comentario, si no -> recomendación
    private fun toAdvice(rec: Recommendation?, conf: Double): Advice =
        if (rec == null) {
            Advice.Comment(
                mensaje = "No se encontró recomendación específica para la etiqueta detectada.",
                prioridad = band(conf)
            )
        } else {
            val p = band(conf)
            if (p == "bajo") {
                Advice.Comment(
                    mensaje = "Confianza baja (${(conf * 100).toInt()}%). Toma otra foto con mejor luz y enfoque.",
                    prioridad = p
                )
            } else {
                Advice.Rec(withPriority(rec, conf))
            }
        }

    // Función pura principal: VisionResult -> Advice (composición declarativa)
    fun advise(v: VisionResult): Advice {
        val baseRec: Recommendation? = when (v.label) {
            VisionLabel.TRIPS, VisionLabel.ARANA_ROJA -> pestCatalog[v.label]
            VisionLabel.VERDE, VisionLabel.HASS -> ripenessCatalog[v.label]
        }
        return toAdvice(baseRec, v.confidence)
    }

    // Función de compatibilidad con código legacy
    @Deprecated("Usar advise() en su lugar", ReplaceWith("advise(v)"))
    fun recommendFromVision(v: VisionResult): Recommendation? =
        (advise(v) as? Advice.Rec)?.recommendation
}

// ============================
// API CLIENT para PREDICCIÓN DE IMÁGENES
// ============================

@Serializable
data class PredictRequest(
    val imageId: String? = null,
    val imageBase64: String
)

@Serializable
data class PredictResponse(
    val imageId: String? = null,
    val label: String?,
    val confidence: Double
)

class PredictApiClient(
    private val httpClient: OkHttpClient
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun predict(imageId: String, imageBytes: ByteArray): VisionResult {
        try {
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val requestBody = json.encodeToString(
                PredictRequest(imageId = imageId, imageBase64 = base64Image)
            ).toRequestBody(jsonMedia)

            val request = Request.Builder()
                .url("https://tu-backend-si-lo-tienes/predict")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("Error en API: ${response.code}")

            val body = response.body?.string() ?: throw Exception("Respuesta vacía")
            val predictResponse = json.decodeFromString<PredictResponse>(body)
            val label = when ((predictResponse.label ?: "hass").lowercase()) {
                "trips" -> VisionLabel.TRIPS
                "arana_roja", "arana roja" -> VisionLabel.ARANA_ROJA
                "verde" -> VisionLabel.VERDE
                "hass" -> VisionLabel.HASS
                else -> VisionLabel.HASS
            }

            return VisionResult(
                imageId = predictResponse.imageId ?: imageId,
                label = label,
                confidence = predictResponse.confidence
            )
        } catch (e: Exception) {
            Log.e("PredictApiClient", "Error en predict: ${e.message}", e)
            throw e
        }
    }
}

// ============================
// GOOGLE AUTH HELPER, generar token para llamar vertex
// ============================

object GoogleAuthHelper {
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"

    fun getAccessToken(serviceAccountJson: JSONObject): String {
        val clientEmail = serviceAccountJson.getString("client_email")
        val privateKeyPem = serviceAccountJson.getString("private_key")

        // PEM -> bytes con android.util.Base64
        val privateKeyClean = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val keyBytes = Base64.decode(privateKeyClean, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec) as RSAPrivateKey

        // JWT RS256 (Service Account OAuth2)
        val now = System.currentTimeMillis()
        val exp = now + 3600_000
        val algorithm = Algorithm.RSA256(null, privateKey)
        val jwt = JWT.create()
            .withIssuer(clientEmail)
            .withAudience(TOKEN_URL)
            .withClaim("scope", SCOPE)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(exp))
            .sign(algorithm)

        // Intercambio JWT -> Access Token
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", jwt)
            .build()
        val req = Request.Builder().url(TOKEN_URL).post(body).build()

        OkHttpClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Error token: ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            return json.getString("access_token")
        }
    }
}

// ============================
// FUNCIONES PARA VERTEX AI DIRECTAMENTE
// ============================

private fun loadJsonFromAssets(fileName: String, context: Context): JSONObject {
    val text = context.assets.open(fileName).bufferedReader().use { it.readText() }
    return JSONObject(text)
}

private fun toBase64(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.NO_WRAP)

private fun predictDirectVertexAI(
    projectId: String,
    location: String,
    endpointId: String,
    imageBase64: String,
    accessToken: String
): String {
    val endpointUrl =
        "https://$location-aiplatform.googleapis.com/v1/projects/$projectId/locations/$location/endpoints/$endpointId:predict"

    val jsonBody = """
        {
          "instances": [ { "content": "$imageBase64" } ],
          "parameters": { "score_threshold": 0.0 }
        }
    """.trimIndent()

    val req = Request.Builder()
        .url(endpointUrl)
        .addHeader("Authorization", "Bearer $accessToken")
        .addHeader("Content-Type", "application/json")
        .post(jsonBody.toRequestBody("application/json".toMediaType()))
        .build()

    val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            val err = resp.body?.string()
            throw Exception("Error Vertex (${resp.code}): $err")
        }
        return resp.body?.string() ?: "{}"
    }
}

private fun parseVertexAIResponse(
    responseJson: String,
    imageId: String
): VisionResult {
    val bestLabel = Regex("\"displayNames\"\\s*:\\s*\\{\\s*\"values\"\\s*:\\s*\\[(.*?)\\]")
        .find(responseJson)?.groups?.get(1)?.value
        ?.let {
            Regex("\"stringValue\"\\s*:\\s*\"(.*?)\"").findAll(it)
                .toList().firstOrNull()?.groups?.get(1)?.value
        }

    val bestScore = Regex("\"confidences\"\\s*:\\s*\\{\\s*\"values\"\\s*:\\s*\\[(.*?)\\]")
        .find(responseJson)?.groups?.get(1)?.value
        ?.let {
            Regex("\"numberValue\"\\s*:\\s*([0-9\\.]+)").findAll(it)
                .toList().firstOrNull()?.groups?.get(1)?.value?.toDoubleOrNull()
        } ?: 0.0

    val mapped = when ((bestLabel ?: "hass").lowercase()) {
        "trips" -> VisionLabel.TRIPS
        "arana_roja" -> VisionLabel.ARANA_ROJA
        "verde" -> VisionLabel.VERDE
        "hass" -> VisionLabel.HASS
        else -> VisionLabel.HASS
    }

    return VisionResult(
        imageId = imageId,
        label = mapped,
        confidence = bestScore
    )
}

// ============================
// REPOSITORY para manejo de datos
// ============================

class VisionRepository(
    private val firestore: FirebaseFirestore,
    private val apiClient: PredictApiClient,
    private val storage: FirebaseStorage,
    private val context: Context
) {
    data class VertexConfig(
        val projectId: String,
        val location: String,
        val endpointId: String
    )

    private val vertexConfig = VertexConfig(
        projectId = "TU_PROYECTO_ID",
        location = "us-central1",
        endpointId = "TU_ENDPOINT_ID"
    )

    suspend fun classifyAndPersist(imageUri: Uri, tipo: String): VisionResult {
        val imageId = "${tipo.lowercase()}_${UUID.randomUUID().toString().substring(0, 8)}"
        val imageBytes = readImageBytes(imageUri)
        val imageUrl = uploadToFirebaseStorage(imageBytes, imageId, tipo)
        val result = try {
            classifyWithVertexAI(imageBytes, imageId)
        } catch (e: Exception) {
            Log.w("VisionRepository", "Vertex AI falló, usando API intermedia", e)
            apiClient.predict(imageId, imageBytes)
        }
        saveVisionResult(result, imageUrl, tipo)
        saveImageInfo(imageId, imageUrl, tipo)
        return result
    }

    private suspend fun classifyWithVertexAI(imageBytes: ByteArray, imageId: String): VisionResult =
        withContext(Dispatchers.IO) {
            try {
                val base64Image = toBase64(imageBytes)
                val saJson = loadJsonFromAssets("service_account.json", context)
                val accessToken = GoogleAuthHelper.getAccessToken(saJson)

                val responseJson = predictDirectVertexAI(
                    projectId = vertexConfig.projectId,
                    location = vertexConfig.location,
                    endpointId = vertexConfig.endpointId,
                    imageBase64 = base64Image,
                    accessToken = accessToken
                )

                parseVertexAIResponse(responseJson, imageId)
            } catch (e: Exception) {
                Log.e("VertexAI", "Error en clasificación: ${e.message}", e)
                throw e
            }
        }

    private suspend fun readImageBytes(uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes()
        } ?: throw IllegalArgumentException("No se pudo leer la imagen")
    }

    private suspend fun uploadToFirebaseStorage(
        imageBytes: ByteArray,
        imageId: String,
        tipo: String
    ): String {
        val imageName = "${tipo.lowercase()}_${System.currentTimeMillis()}.jpg"
        val imageRef = storage.reference.child("imagenes_ecoaguacate/$tipo/$imageName")
        val uploadTask = imageRef.putBytes(imageBytes).await()
        val downloadUri = imageRef.downloadUrl.await()
        return downloadUri.toString()
    }

    private suspend fun saveVisionResult(result: VisionResult, imageUrl: String, tipo: String) {
        val visionData = hashMapOf(
            "imageId" to result.imageId,
            "imageUrl" to imageUrl,
            "label" to when (result.label) {
                VisionLabel.TRIPS -> "TRIPS"
                VisionLabel.ARANA_ROJA -> "ARANA_ROJA"
                VisionLabel.VERDE -> "VERDE"
                VisionLabel.HASS -> "HASS"
            },
            "confidence" to result.confidence,
            "timestamp" to Timestamp.now(),
            "tipoAnalisis" to tipo,
            "deviceModel" to Build.MODEL
        )

        firestore.collection("vision_raw")
            .document(result.imageId)
            .set(visionData)
            .await()
    }

    private suspend fun saveImageInfo(imageId: String, imageUrl: String, tipo: String) {
        val imageData = hashMapOf(
            "imageId" to imageId,
            "url" to imageUrl,
            "nombre" to imageId,
            "fechaSubida" to Timestamp.now(),
            "tipo" to tipo,
            "carpeta" to "imagenes_ecoaguacate/$tipo"
        )

        firestore.collection("imagenes")
            .document(imageId)
            .set(imageData)
            .await()
    }
}

// ============================
// Main Activity
// ============================

class MainActivity : ComponentActivity() {
    private val storage = FirebaseStorage.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()
    private val apiClient = PredictApiClient(okHttpClient)
    private lateinit var repository: VisionRepository

    // Estados para resultados
    private var currentRecommendation: Recommendation? = null
    private var currentVisionResult: VisionResult? = null
    private var adviceGlobal: Advice? = null
    private var currentAnalysisType: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = VisionRepository(db, apiClient, storage, this)
        setContent {
            EcoAguacateTheme {
                val currentScreen = remember { mutableStateOf<Screen>(Screen.Welcome) }
                @OptIn(ExperimentalMaterial3Api::class)
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreen.value) {
                            Screen.Welcome -> WelcomeScreen(
                                onStartClicked = { currentScreen.value = Screen.PestDetection }
                            )
                            Screen.PestDetection -> PestDetectionScreen(
                                onBackClicked = { currentScreen.value = Screen.Welcome },
                                onAnalyzeClicked = {
                                    currentScreen.value = Screen.MaturityAnalysis
                                },
                                onPestDetectionClicked = {
                                    currentScreen.value = Screen.PestCameraScreen
                                }
                            )
                            Screen.MaturityAnalysis -> MaturityAnalysisScreen(
                                onBackClicked = { currentScreen.value = Screen.PestDetection },
                                onTakePhotoClicked = { currentScreen.value = Screen.CameraScreen },
                                onReturnToMainClicked = { currentScreen.value = Screen.PestDetection }
                            )
                            Screen.CameraScreen -> CameraScreen(
                                onBackClicked = { currentScreen.value = Screen.MaturityAnalysis },
                                onPhotoTaken = { imageUri ->
                                    lifecycleScope.launch {
                                        currentAnalysisType = "Maduración"
                                        if (imageUri != null) {
                                            analyzeImage(imageUri, "Maduración")
                                        } else {
                                            simulateAnalysis("Maduración")
                                        }
                                        currentScreen.value = Screen.PestResult
                                    }
                                }
                            )
                            Screen.PestCameraScreen -> PestCameraScreen(
                                onBackClicked = { currentScreen.value = Screen.PestDetection },
                                onPhotoTaken = { imageUri ->
                                    lifecycleScope.launch {
                                        currentAnalysisType = "Plaga"
                                        if (imageUri != null) {
                                            analyzeImage(imageUri, "Plaga")
                                        } else {
                                            simulateAnalysis("Plaga")
                                        }
                                        currentScreen.value = Screen.PestResult
                                    }
                                }
                            )
                            Screen.PestResult -> PestResultScreen(
                                onBackClicked = { currentScreen.value = Screen.PestDetection },
                                onReturnToMainClicked = { currentScreen.value = Screen.PestDetection },
                                advice = adviceGlobal,
                                recommendation = currentRecommendation,
                                visionResult = currentVisionResult,
                                requestedAnalysisType = currentAnalysisType
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun analyzeImage(imageUri: Uri, tipo: String) {
        try {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "🔍 Analizando imagen...", Toast.LENGTH_SHORT).show()
            }
            val result = repository.classifyAndPersist(imageUri, tipo)
            currentVisionResult = result
            val advice = RecommendationModule.advise(result)
            adviceGlobal = advice
            currentRecommendation = (advice as? Advice.Rec)?.recommendation
            guardarAnalisisCompletoAdvice(result, advice, tipo)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "✅ Análisis completado", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error en analyzeImage: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "❌ Error: ${e.message ?: "Desconocido"}",
                    Toast.LENGTH_LONG
                ).show()
            }
            simulateAnalysis(tipo)
        }
    }

    private suspend fun simulateAnalysis(tipo: String) {
        // Generar resultados RANDOM para probar diferentes escenarios
        val randomResult = when ((0..3).random()) {
            0 -> VisionResult(
                imageId = "sim_${UUID.randomUUID().toString().substring(0, 8)}",
                label = VisionLabel.TRIPS,
                confidence = (70..95).random() / 100.0
            )
            1 -> VisionResult(
                imageId = "sim_${UUID.randomUUID().toString().substring(0, 8)}",
                label = VisionLabel.ARANA_ROJA,
                confidence = (65..90).random() / 100.0
            )
            2 -> VisionResult(
                imageId = "sim_${UUID.randomUUID().toString().substring(0, 8)}",
                label = VisionLabel.VERDE,
                confidence = (75..98).random() / 100.0
            )
            else -> VisionResult(
                imageId = "sim_${UUID.randomUUID().toString().substring(0, 8)}",
                label = VisionLabel.HASS,
                confidence = (80..99).random() / 100.0
            )
        }

        currentVisionResult = randomResult
        val advice = RecommendationModule.advise(randomResult)
        adviceGlobal = advice
        currentRecommendation = (advice as? Advice.Rec)?.recommendation

        guardarAnalisisCompletoAdvice(randomResult, advice, tipo, esSimulacion = true)

        withContext(Dispatchers.Main) {
            val detectedWhat = when (randomResult.label) {
                VisionLabel.TRIPS -> "Trips (Plaga)"
                VisionLabel.ARANA_ROJA -> "Araña Roja (Plaga)"
                VisionLabel.VERDE -> "Aguacate Verde"
                VisionLabel.HASS -> "Aguacate Hass"
            }
            Toast.makeText(
                this@MainActivity,
                "✅ Simulación: $detectedWhat detectado",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private suspend fun guardarAnalisisCompletoAdvice(
        result: VisionResult,
        advice: Advice,
        tipo: String,
        esSimulacion: Boolean = false
    ) {
        try {
            // Determinar QUÉ se detectó realmente
            val detectedType = when (result.label) {
                VisionLabel.TRIPS, VisionLabel.ARANA_ROJA -> "Plaga"
                VisionLabel.VERDE, VisionLabel.HASS -> "Maduración"
            }

            val base = hashMapOf(
                "fechaSubida" to Timestamp.now(),
                "imageId" to result.imageId,
                "label" to when (result.label) {
                    VisionLabel.TRIPS -> "TRIPS"
                    VisionLabel.ARANA_ROJA -> "ARANA_ROJA"
                    VisionLabel.VERDE -> "VERDE"
                    VisionLabel.HASS -> "HASS"
                },
                "label_texto" to when (result.label) {
                    VisionLabel.TRIPS -> "Trips (Plaga)"
                    VisionLabel.ARANA_ROJA -> "Araña Roja (Plaga)"
                    VisionLabel.VERDE -> "Aguacate Verde"
                    VisionLabel.HASS -> "Aguacate Hass"
                },
                "confidence" to result.confidence,
                "tipo_solicitado" to tipo,
                "tipo_detectado" to detectedType,
                "esSimulacion" to esSimulacion,
                "appVersion" to BuildConfig.VERSION_NAME,
                "deviceModel" to Build.MODEL,
                "timestamp" to System.currentTimeMillis(),
                "proyecto_firebase" to "ecoaguacatebd"
            )

            // Determinar datos específicos según el tipo de advice
            val adviceData = when (advice) {
                is Advice.Rec -> mapOf(
                    "adviceTipo" to "rec",
                    "recomendacion" to mapOf(
                        "topic" to advice.recommendation.topic,
                        "prioridad" to advice.recommendation.prioridad,
                        "acciones" to advice.recommendation.acciones,
                        "notas" to advice.recommendation.notas
                    )
                )
                is Advice.Comment -> mapOf(
                    "adviceTipo" to "comment",
                    "comentario" to mapOf(
                        "mensaje" to advice.mensaje,
                        "prioridad" to advice.prioridad
                    )
                )
            }

            // Documento principal
            db.collection("analisis")
                .document(result.imageId)
                .set(base + adviceData)
                .addOnSuccessListener { documentReference ->
                    Log.d("Firestore", """
                        ✅ Análisis guardado:
                        - ID: ${result.imageId}
                        - Solicitado: $tipo
                        - Detectado: $detectedType (${result.label})
                        - Confianza: ${result.confidence}
                        - Document ID: ${documentReference}
                    """.trimIndent())
                }
                .await()

            // Colección secundaria (opcional) para consultas rápidas
            when (advice) {
                is Advice.Rec -> {
                    val recoData = hashMapOf(
                        "analisisId" to result.imageId,
                        "topic" to advice.recommendation.topic,
                        "prioridad" to advice.recommendation.prioridad,
                        "acciones" to advice.recommendation.acciones,
                        "notas" to advice.recommendation.notas,
                        "fechaGeneracion" to Timestamp.now(),
                        "label_detectado" to when (result.label) {
                            VisionLabel.TRIPS -> "TRIPS"
                            VisionLabel.ARANA_ROJA -> "ARANA_ROJA"
                            VisionLabel.VERDE -> "VERDE"
                            VisionLabel.HASS -> "HASS"
                        }
                    )
                    db.collection("vision_reco")
                        .document("${result.imageId}_reco")
                        .set(recoData)
                        .await()
                }
                is Advice.Comment -> {
                    val commentData = hashMapOf(
                        "analisisId" to result.imageId,
                        "mensaje" to advice.mensaje,
                        "prioridad" to advice.prioridad,
                        "fechaGeneracion" to Timestamp.now()
                    )
                    db.collection("vision_comment")
                        .document("${result.imageId}_comment")
                        .set(commentData)
                        .await()
                }
            }
        } catch (e: Exception) {
            Log.e("Firestore", "❌ Error al guardar análisis: ${e.message}")
        }
    }
}

// ============================
// Enums y Data Classes
// ============================

sealed class Screen {
    object Welcome : Screen()
    object PestDetection : Screen()
    object MaturityAnalysis : Screen()
    object CameraScreen : Screen()
    object PestCameraScreen : Screen()
    object PestResult : Screen()
}

// ============================
// Composable Screens
// ============================

@Composable
fun PestResultScreen(
    onBackClicked: () -> Unit,
    onReturnToMainClicked: () -> Unit,
    advice: Advice? = null,
    recommendation: Recommendation? = null,
    visionResult: VisionResult? = null,
    requestedAnalysisType: String? = null
) {
    val context = LocalContext.current

    // Determinar QUÉ detectó realmente la IA
    val detectedCategory = remember(visionResult) {
        when (visionResult?.label) {
            VisionLabel.TRIPS, VisionLabel.ARANA_ROJA -> "plaga"
            VisionLabel.VERDE, VisionLabel.HASS -> "maduracion"
            null -> null
        }
    }

    // Determinar el TEXTO específico de lo detectado
    val detectedText = remember(visionResult) {
        when (visionResult?.label) {
            VisionLabel.TRIPS -> "Trips (Plaga)"
            VisionLabel.ARANA_ROJA -> "Araña Roja (Plaga)"
            VisionLabel.VERDE -> "Aguacate Verde"
            VisionLabel.HASS -> "Aguacate Hass"
            null -> "No detectado"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Botón de regreso
        Button(
            onClick = onBackClicked,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color(0xFF4CAF50)
            ),
            modifier = Modifier.size(48.dp)
        ) {
            Text(text = "←", fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Título principal
        Text(
            text = "EcoAguacate",
            fontSize = 24.sp,
            color = Color(0xFF4CAF50),
            fontWeight = FontWeight.Bold
        )

        // Mostrar QUÉ detectó la IA (no lo que el usuario seleccionó)
        Text(
            text = "🔍 Resultado del Análisis IA",
            fontSize = 18.sp,
            color = Color(0xFF4CAF50),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Card principal
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // SECCIÓN: LO QUE DETECTÓ LA IA (lo importante)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Icono según lo detectado
                    Text(
                        text = when (detectedCategory) {
                            "plaga" -> "🐛"
                            "maduracion" -> "🥑"
                            else -> "🤖"
                        },
                        fontSize = 60.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Text(
                        text = "La IA detectó:",
                        fontSize = 16.sp,
                        color = Color.Gray
                    )

                    Text(
                        text = detectedText,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (detectedCategory) {
                            "plaga" -> Color.Red
                            "maduracion" -> Color(0xFF4CAF50)
                            else -> Color.Black
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    // Mostrar el flujo seleccionado como referencia (solo informativo)
                    requestedAnalysisType?.let { tipoSolicitado ->
                        Text(
                            text = "Flujo seleccionado: $tipoSolicitado",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Línea divisoria
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.LightGray)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // SECCIÓN: Detalles de la predicción
                visionResult?.let { result ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Detalles de la Predicción",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Confianza
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Confianza del Modelo",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Porcentaje
                                Text(
                                    text = "${(result.confidence * 100).toInt()}%",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        result.confidence >= 0.8 -> Color.Green
                                        result.confidence >= 0.6 -> Color(0xFFFF9800)
                                        else -> Color.Red
                                    }
                                )

                                // Barra de progreso
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(12.dp)
                                        .background(Color.LightGray)
                                        .padding(top = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(result.confidence.toFloat())
                                            .height(12.dp)
                                            .background(
                                                when {
                                                    result.confidence >= 0.8 -> Color.Green
                                                    result.confidence >= 0.6 -> Color(0xFFFF9800)
                                                    else -> Color.Red
                                                }
                                            )
                                    )
                                }

                                // Texto descriptivo de la confianza
                                Text(
                                    text = when {
                                        result.confidence >= 0.8 -> "Alta confianza - Resultado confiable"
                                        result.confidence >= 0.6 -> "Confianza media - Resultado probable"
                                        else -> "Baja confianza - Considerar nueva foto"
                                    },
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }

                        // ID de la imagen
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(text = "🆔", fontSize = 20.sp)
                                Column {
                                    Text(
                                        text = "Identificador",
                                        fontSize = 14.sp,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = result.imageId,
                                        fontSize = 12.sp,
                                        color = Color.DarkGray,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }

                // SECCIÓN: Recomendaciones o Comentarios (basados en lo detectado)
                when (advice) {
                    is Advice.Rec -> {
                        RecommendationSection(recommendation = advice.recommendation)
                    }
                    is Advice.Comment -> {
                        CommentSection(comment = advice)
                    }
                    else -> {
                        recommendation?.let { rec ->
                            RecommendationSection(recommendation = rec)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Botón para volver (quitada la sección de Información del Sistema)
                Button(
                    onClick = onReturnToMainClicked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Volver al Menú Principal",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendationSection(recommendation: Recommendation) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Recomendaciones:",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.fillMaxWidth()
        )

        // Prioridad
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = when (recommendation.prioridad) {
                "alto" -> Color(0xFFFFEBEE)
                "medio" -> Color(0xFFFFF3E0)
                else -> Color(0xFFE8F5E9)
            },
            border = BorderStroke(
                1.dp,
                when (recommendation.prioridad) {
                    "alto" -> Color.Red
                    "medio" -> Color(0xFFFF9800)
                    else -> Color.Green
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Prioridad: ${recommendation.prioridad.uppercase()}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (recommendation.prioridad) {
                        "alto" -> Color.Red
                        "medio" -> Color(0xFFFF9800)
                        else -> Color.Green
                    }
                )

                Text(
                    text = "Tema: ${recommendation.topic}",
                    fontSize = 14.sp,
                    color = Color.DarkGray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Acciones recomendadas
        Text(
            text = "Acciones recomendadas:",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            recommendation.acciones.forEachIndexed { index, accion ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${index + 1}.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                    Text(
                        text = accion,
                        fontSize = 14.sp,
                        color = Color.DarkGray,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Notas
        if (recommendation.notas.isNotEmpty()) {
            Text(
                text = "Notas importantes:",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black,
                modifier = Modifier.padding(top = 8.dp)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recommendation.notas.forEach { nota ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "•",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        Text(
                            text = nota,
                            fontSize = 14.sp,
                            color = Color.DarkGray,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentSection(comment: Advice.Comment) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = when (comment.prioridad) {
            "alto" -> Color(0xFFFFEBEE)
            "medio" -> Color(0xFFFFF3E0)
            else -> Color(0xFFE8F5E9)
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Comentario del sistema:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = comment.mensaje,
                fontSize = 14.sp,
                color = Color.DarkGray,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Prioridad: ${comment.prioridad.uppercase()}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = when (comment.prioridad) {
                    "alto" -> Color.Red
                    "medio" -> Color(0xFFFF9800)
                    else -> Color.Green
                }
            )
        }
    }
}

@Composable
private fun InfoRow(icon: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = icon, fontSize = 16.sp)
        Text(
            text = text,
            fontSize = 14.sp,
            color = Color.DarkGray,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============================
// Resto de las pantallas (sin cambios)
// ============================

@Composable
fun PestCameraScreen(
    onBackClicked: () -> Unit,
    onPhotoTaken: (Uri?) -> Unit
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasStoragePermission by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }

    if (isPreview) {
        PestCameraContent(
            onBackClicked = onBackClicked,
            showError = false,
            hasCameraPermission = true,
            hasStoragePermission = true,
            onDetectClicked = { },
            onContinueNoPhoto = { }
        )
        return
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) {
                Toast.makeText(
                    context,
                    "✅ Foto de plaga guardada",
                    Toast.LENGTH_LONG
                ).show()
                onPhotoTaken(capturedImageUri)
            } else {
                showError = true
                Toast.makeText(context, "❌ Error al guardar la foto", Toast.LENGTH_LONG).show()
                onPhotoTaken(null)
            }
        }
    )

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (!granted) {
                showError = true
                Toast.makeText(
                    context,
                    "Permiso de cámara necesario",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    )

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasStoragePermission = granted
            if (!granted && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                showError = true
                Toast.makeText(
                    context,
                    "Permiso de almacenamiento necesario para Android < 10",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    )

    fun createImageFile(): Uri? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "PLAGA_${timeStamp}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/EcoAguacate/Plagas"
                    )
                }
                context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
            } else {
                val storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val ecoDir = File(storageDir, "EcoAguacate/Plagas")
                if (!ecoDir.exists()) ecoDir.mkdirs()
                val photoFile = File(ecoDir, fileName)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    fun takePicture() {
        try {
            val uri = createImageFile()
            if (uri != null) {
                capturedImageUri = uri
                takePictureLauncher.launch(uri)
            } else {
                Toast.makeText(context, "No se pudo crear el archivo", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error al abrir cámara: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasStoragePermission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        when {
            !hasCameraPermission -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission ->
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else -> Toast.makeText(context, "Cámara lista", Toast.LENGTH_SHORT).show()
        }
    }

    PestCameraContent(
        onBackClicked = onBackClicked,
        showError = showError,
        hasCameraPermission = hasCameraPermission,
        hasStoragePermission = hasStoragePermission,
        onDetectClicked = { takePicture() },
        onContinueNoPhoto = {
            Toast.makeText(context, "Continuando sin foto", Toast.LENGTH_SHORT).show()
            onPhotoTaken(null)
        }
    )
}

@Composable
private fun PestCameraContent(
    onBackClicked: () -> Unit,
    showError: Boolean,
    hasCameraPermission: Boolean,
    hasStoragePermission: Boolean,
    onDetectClicked: () -> Unit,
    onContinueNoPhoto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Button(
            onClick = onBackClicked,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White
            ),
            modifier = Modifier.size(48.dp)
        ) { Text(text = "←", fontSize = 20.sp) }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Detección de Plagas - Cámara",
            fontSize = 20.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(32.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "🐛", fontSize = 80.sp, color = Color.White)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = when {
                            showError -> "❌ Error de configuración"
                            !hasCameraPermission -> "⏳ Esperando permiso de cámara..."
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission ->
                                "⏳ Esperando permiso de almacenamiento..."
                            else -> "✅ Cámara lista para detección"
                        },
                        fontSize = 22.sp,
                        color = when {
                            showError -> Color.Red
                            !hasCameraPermission ||
                                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission) -> Color.Yellow
                            else -> Color.Green
                        },
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = when {
                            showError -> "Verifica los permisos en configuración"
                            !hasCameraPermission -> "La app necesita acceso a la cámara"
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission ->
                                "Android 9 - necesita permiso de almacenamiento"
                            else -> "Tome una foto clara de la plaga para analizar"
                        },
                        fontSize = 14.sp,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onDetectClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (hasCameraPermission &&
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasStoragePermission)
                    ) Color(0xFF4CAF50) else Color.Gray,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                enabled = hasCameraPermission &&
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasStoragePermission)
            ) {
                Text(
                    text = if (hasCameraPermission &&
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasStoragePermission)
                    ) "📸 DETECTAR PLAGA" else "ESPERANDO PERMISOS...",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onContinueNoPhoto,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("CONTINUAR SIN FOTO (Pruebas)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CameraScreen(
    onBackClicked: () -> Unit,
    onPhotoTaken: (Uri?) -> Unit
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasStoragePermission by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }

    if (isPreview) {
        CameraContent(
            title = "EcoAguacate - Cámara",
            showError = showError,
            hasCameraPermission = hasCameraPermission,
            hasStoragePermission = hasStoragePermission,
            onTakePhoto = { },
            onContinueNoPhoto = { },
            onBackClicked = onBackClicked
        )
        return
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) {
                Toast.makeText(
                    context,
                    "✅ Foto guardada",
                    Toast.LENGTH_LONG
                ).show()
                onPhotoTaken(capturedImageUri)
            } else {
                showError = true
                Toast.makeText(context, "❌ Error al guardar la foto", Toast.LENGTH_LONG).show()
                onPhotoTaken(null)
            }
        }
    )

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (!granted) {
                showError = true
                Toast.makeText(
                    context,
                    "Permiso de cámara necesario",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    )

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasStoragePermission = granted
            if (!granted && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                showError = true
                Toast.makeText(
                    context,
                    "Permiso de almacenamiento necesario para Android < 10",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    )

    fun createImageFile(): Uri? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "MADURACION_${timeStamp}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/EcoAguacate/Maduracion"
                    )
                }
                context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
            } else {
                val storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val ecoDir = File(storageDir, "EcoAguacate/Maduracion")
                if (!ecoDir.exists()) ecoDir.mkdirs()
                val photoFile = File(ecoDir, fileName)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    fun takePhoto() {
        try {
            val uri = createImageFile()
            if (uri != null) {
                capturedImageUri = uri
                takePictureLauncher.launch(uri)
            } else {
                Toast.makeText(context, "No se pudo crear el archivo", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error al abrir cámara: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasStoragePermission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        when {
            !hasCameraPermission -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission ->
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else -> Toast.makeText(context, "Cámara lista", Toast.LENGTH_SHORT).show()
        }
    }

    CameraContent(
        title = "EcoAguacate - Cámara",
        showError = showError,
        hasCameraPermission = hasCameraPermission,
        hasStoragePermission = hasStoragePermission,
        onTakePhoto = { takePhoto() },
        onContinueNoPhoto = {
            Toast.makeText(context, "Continuando sin foto", Toast.LENGTH_SHORT).show()
            onPhotoTaken(null)
        },
        onBackClicked = onBackClicked
    )
}

@Composable
private fun CameraContent(
    title: String,
    showError: Boolean,
    hasCameraPermission: Boolean,
    hasStoragePermission: Boolean,
    onTakePhoto: () -> Unit,
    onContinueNoPhoto: () -> Unit,
    onBackClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Button(
            onClick = onBackClicked,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White
            ),
            modifier = Modifier.size(48.dp)
        ) { Text(text = "←", fontSize = 20.sp) }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            fontSize = 20.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(32.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "📷", fontSize = 80.sp, color = Color.White)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = when {
                            showError -> "❌ Error de configuración"
                            !hasCameraPermission -> "⏳ Esperando permiso de cámara..."
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission ->
                                "⏳ Esperando permiso de almacenamiento..."
                            else -> "✅ Cámara lista"
                        },
                        fontSize = 22.sp,
                        color = when {
                            showError -> Color.Red
                            !hasCameraPermission ||
                                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission) -> Color.Yellow
                            else -> Color.Green
                        },
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = when {
                            showError -> "Verifica los permisos en configuración"
                            !hasCameraPermission -> "La app necesita acceso a la cámara"
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission ->
                                "Android 9 - necesita permiso de almacenamiento"
                            else -> "Las fotos se guardan en: Galería/EcoAguacate/Maduracion"
                        },
                        fontSize = 14.sp,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onTakePhoto,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (hasCameraPermission &&
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasStoragePermission)
                    ) Color(0xFF4CAF50) else Color.Gray,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                enabled = hasCameraPermission &&
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasStoragePermission)
            ) {
                Text(
                    text = if (hasCameraPermission &&
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasStoragePermission)
                    ) "📸 TOMAR FOTO" else "ESPERANDO PERMISOS...",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onContinueNoPhoto,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("CONTINUAR SIN FOTO (Pruebas)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PestDetectionScreen(
    onBackClicked: () -> Unit,
    onAnalyzeClicked: () -> Unit,
    onPestDetectionClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp)
    ) {
        Button(
            onClick = onBackClicked,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color(0xFF4CAF50)
            ),
            modifier = Modifier.size(48.dp)
        ) {
            Text(text = "←", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "EcoAguacate",
            fontSize = 24.sp,
            color = Color(0xFF4CAF50),
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Análisis de",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Detección de Plagas y Maduración",
                    fontSize = 20.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(48.dp))
                Button(
                    onClick = onAnalyzeClicked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Análisis de Maduración",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onPestDetectionClicked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Ver Detección de Plaga",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun WelcomeScreen(onStartClicked: () -> Unit) {
    val isPreview = LocalInspectionMode.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // ¡AQUÍ SE USA TU IMAGEN ecoaguacate.jpg!
            Image(
                painter = painterResource(id = R.drawable.ecoaguacate), // ← Tu imagen
                contentDescription = "Logo EcoAguacate",
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(16.dp)) // Opcional: esquinas redondeadas
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "EcoAguacate",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Análisis Inteligente de Aguacates",
                fontSize = 16.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onStartClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Comenzar",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun MaturityAnalysisScreen(
    onBackClicked: () -> Unit,
    onTakePhotoClicked: () -> Unit,
    onReturnToMainClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp)
    ) {
        Button(
            onClick = onBackClicked,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color(0xFF4CAF50)
            ),
            modifier = Modifier.size(48.dp)
        ) {
            Text(text = "←", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "EcoAguacate",
            fontSize = 24.sp,
            color = Color(0xFF4CAF50),
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Análisis de maduración",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(48.dp))
                Button(
                    onClick = onTakePhotoClicked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Tomar foto",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onReturnToMainClicked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Regresar al Menú Principal",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ============================
// Previews
// ============================

@Preview(showBackground = true)
@Composable
fun WelcomePreview() {
    EcoAguacateTheme {
        WelcomeScreen(onStartClicked = {})
    }
}

@Preview(showBackground = true)
@Composable
fun PestDetectionPreview() {
    EcoAguacateTheme {
        PestDetectionScreen(
            onBackClicked = {},
            onAnalyzeClicked = {},
            onPestDetectionClicked = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MaturityAnalysisPreview() {
    EcoAguacateTheme {
        MaturityAnalysisScreen(
            onBackClicked = {},
            onTakePhotoClicked = {},
            onReturnToMainClicked = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PestResultPlagaPreview() {
    EcoAguacateTheme {
        PestResultScreen(
            onBackClicked = {},
            onReturnToMainClicked = {},
            advice = Advice.Rec(
                recommendation = Recommendation(
                    topic = "plaga",
                    prioridad = "alto",
                    acciones = listOf(
                        "Monitoreo en brotes/inflorescencias/frutos jóvenes",
                        "Trampas cromáticas en bordes"
                    ),
                    notas = listOf("Verificar lineamientos locales antes de aplicar insumos")
                )
            ),
            visionResult = VisionResult(
                imageId = "img_123",
                label = VisionLabel.TRIPS,
                confidence = 0.87
            ),
            requestedAnalysisType = "Maduración"
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PestResultMaduracionPreview() {
    EcoAguacateTheme {
        PestResultScreen(
            onBackClicked = {},
            onReturnToMainClicked = {},
            advice = Advice.Rec(
                recommendation = Recommendation(
                    topic = "maduracion",
                    prioridad = "medio",
                    acciones = listOf(
                        "Ventana estimada de consumo: 4-7 días",
                        "Revisar firmeza/color diariamente"
                    ),
                    notas = listOf("Evitar frío extremo en fruta verde")
                )
            ),
            visionResult = VisionResult(
                imageId = "img_456",
                label = VisionLabel.HASS,
                confidence = 0.92
            ),
            requestedAnalysisType = "Plaga"
        )
    }
}

@Preview(showBackground = true)
@Composable
fun CameraScreenPreview() {
    EcoAguacateTheme {
        CameraScreen(
            onBackClicked = {},
            onPhotoTaken = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PestCameraScreenPreview() {
    EcoAguacateTheme {
        PestCameraScreen(
            onBackClicked = {},
            onPhotoTaken = {}
        )
    }
}

@Composable
fun EcoAguacateTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}