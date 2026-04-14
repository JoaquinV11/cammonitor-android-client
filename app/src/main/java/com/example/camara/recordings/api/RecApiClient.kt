package com.example.camara.recordings.api




// ---------- Imports ----------
//import androidx.navigation.compose.navArgument
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.TimeUnit


// ---------- Runtime config (initialized from app setup) ----------
private object RecRuntimeConfig {
    @Volatile private var _baseUrl: String = "" // configured at runtime from secure setup
    @Volatile private var _apiKey: String = ""                           // empty => no header

    fun init(baseUrl: String, apiKey: String?) {
        _baseUrl = baseUrl.trim().trimEnd('/')
        _apiKey = apiKey?.trim().orEmpty()
    }

    val baseUrl: String get() = _baseUrl.ifBlank { error("RecApiClient no inicializado") }
    val apiKey: String get() = _apiKey
}

// Mantengo estos nombres para minimizar cambios en el resto del archivo
internal val BASE_URL: String
    get() = RecRuntimeConfig.baseUrl

internal val API_KEY: String
    get() = RecRuntimeConfig.apiKey

private const val LIVE_BACKOFF_MS = 60_000L

const val SERVER_TZ_ID = "America/Montevideo"


// ---------- Models ----------
data class Recording(
    val filename: String,
    val camera: String,
    val start_iso: String,
    val size_bytes: Long,
    val url: String,
    val marks: List<Int>? = null
)

data class ListResponse(
    val items: List<Recording>,
    val page: Int,
    val page_size: Int,
    val total: Int,
    val pages: Int
)

// ---------- Detection models ----------
data class Detection(
    val id: Long,
    val camera: String,
    val ts_iso: String,
    val label: String? = null,
    val score: Double? = null,
    val snapshot: String? = null,
    val model_name: String? = null,
    val profile_name: String? = null,
)

data class DetectionListResponse(
    val items: List<Detection>
)

data class DetectionClassItem(
    val camera: String,
    val profile_name: String? = null,
    val classes: List<String> = emptyList()
)

data class DetectionClassesResponse(
    val items: List<DetectionClassItem>
)


// ---------- Retrofit API ----------
interface CamApi {
    @GET("/api/list")
    suspend fun list(
        @Query("cam") cam: String? = null,
        @Query("date") date: String? = null,           // "YYYY-MM-DD"
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
        @Query("include_marks") includeMarks: Int = 0
    ): ListResponse

    @GET("/api/detections")
    suspend fun detections(
        @Query("cam") cam: String? = null,
        @Query("start_iso") startIso: String? = null,  // "YYYY-MM-DD'T'HH:mm:ss"
        @Query("end_iso") endIso: String? = null,      // "YYYY-MM-DD'T'HH:mm:ss"
        @Query("label") label: String? = null,
        @Query("limit") limit: Int = 500,
    ): DetectionListResponse

    @GET("/api/detection_classes")
    suspend fun detectionClasses(
        @Query("cam") cam: String? = null,
    ): DetectionClassesResponse
}

object RecApiClient {
    @Volatile private var _api: CamApi? = null
    @Volatile private var _builtForBaseUrl: String? = null
    @Volatile private var _builtForApiKey: String? = null

    fun init(baseUrl: String, apiKey: String?) {
        RecRuntimeConfig.init(baseUrl, apiKey)
        synchronized(this) {
            _api = null
            _builtForBaseUrl = null
            _builtForApiKey = null
        }
    }

    val baseUrl: String
        get() = BASE_URL

    val api: CamApi
        get() {
            val base = BASE_URL
            val key = API_KEY
            _api?.let { cached ->
                if (_builtForBaseUrl == base && _builtForApiKey == key) return cached
            }
            return synchronized(this) {
                _api?.let { cached ->
                    if (_builtForBaseUrl == base && _builtForApiKey == key) return@synchronized cached
                }
                buildApi(base, key).also {
                    _api = it
                    _builtForBaseUrl = base
                    _builtForApiKey = key
                }
            }
        }

    private fun buildApi(baseUrl: String, apiKey: String): CamApi {
        val header = Interceptor { chain ->
            val reqBuilder = chain.request().newBuilder()
            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("X-Api-Key", apiKey)
            }
            chain.proceed(reqBuilder.build())
        }
        val http = OkHttpClient.Builder()
            .addInterceptor(header)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        // NEW: Moshi with Kotlin adapter
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        return Retrofit.Builder()
            .baseUrl("$baseUrl/")                       // ensure trailing slash
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(http)
            .build()
            .create(CamApi::class.java)
    }
}

// ---------- Helpers ----------
fun todayYmd(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return sdf.format(Date())
}
