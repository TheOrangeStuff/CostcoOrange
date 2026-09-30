package com.eristhewerd.costco.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.eristhewerd.costco.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

class RedirectedException(val server: String) : IOException("The server moved to $server; use that address")

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("costco", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) = sp.edit().putString("server", v.trimEnd('/')).apply()
    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()
    var username: String?
        get() = sp.getString("username", null)
        set(v) = sp.edit().putString("username", v).apply()
    var lastEventId: Long
        get() = sp.getLong("last_event_id", -1)
        set(v) = sp.edit().putLong("last_event_id", v).apply()
    var range: String
        get() = sp.getString("range", null) ?: "TWELVE_MONTHS"
        set(v) = sp.edit().putString("range", v).apply()
}

/** Talks to our own backend (never to Costco directly; the server holds the Costco sign-in). */
class Api(context: Context) {
    val prefs = Prefs(context)
    private val cacheDir = File(context.filesDir, "api-cache")
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    val signedIn: Boolean get() = prefs.token != null

    private fun request(path: String): Request.Builder = Request.Builder()
        .url(prefs.server + path)
        .apply { prefs.token?.let { header("Authorization", "Bearer $it") } }

    private suspend fun execute(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            // OkHttp turns a redirected POST into a bodyless GET (e.g. Cloudflare's http -> https 301),
            // which the server answers with 405. Report where the server actually lives instead.
            if (req.method != "GET" && resp.priorResponse?.isRedirect == true) {
                throw RedirectedException(resp.request.url.newBuilder().encodedPath("/").query(null).build().toString())
            }
            if (!resp.isSuccessful) {
                val detail = runCatching {
                    AppJson.parseToJsonElement(body).let { (it as? kotlinx.serialization.json.JsonObject)?.get("detail") }
                        ?.toString()?.trim('"')
                }.getOrNull()
                if (resp.code == 401 && prefs.token != null && !req.url.encodedPath.endsWith("/login")) {
                    prefs.token = null
                }
                throw ApiException(resp.code, detail ?: "Server returned ${resp.code}")
            }
            body
        }
    }

    suspend fun <T> get(path: String, serializer: KSerializer<T>): T {
        val body = execute(request(path).get().build())
        writeCache(path, body)
        return AppJson.decodeFromString(serializer, body)
    }

    /** Last good response for [path], for showing something instantly and offline. */
    suspend fun <T> cached(path: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        runCatching { AppJson.decodeFromString(serializer, cacheFile(path).readText()) }.getOrNull()
    }

    private fun cacheFile(path: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest((prefs.server + path).toByteArray())
        return File(cacheDir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private suspend fun writeCache(path: String, body: String) = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "tmp-${System.nanoTime()}")
            tmp.writeText(body)
            tmp.renameTo(cacheFile(path))
        }
    }

    private suspend fun post(path: String, json: String = "{}"): String =
        execute(request(path).post(json.toRequestBody(jsonType)).build())

    suspend fun login(server: String, username: String, password: String) {
        prefs.server = server
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
        val json = try {
            post("/api/auth/login", body.toString())
        } catch (e: RedirectedException) {
            prefs.server = e.server
            post("/api/auth/login", body.toString())
        }
        val resp = AppJson.decodeFromString(LoginResponse.serializer(), json)
        prefs.token = resp.token
        prefs.username = username
    }

    suspend fun logout() {
        runCatching { post("/api/auth/logout") }
        prefs.token = null
        prefs.lastEventId = -1
        withContext(Dispatchers.IO) { cacheDir.deleteRecursively() }
    }

    suspend fun linkCostco(refreshToken: String, clientId: String?): String? {
        val body = buildJsonObject {
            put("refresh_token", refreshToken)
            put("client_id", clientId)
        }
        val resp = AppJson.parseToJsonElement(post("/api/costco/link", body.toString()))
        return (resp as? kotlinx.serialization.json.JsonObject)?.get("email")?.toString()?.trim('"')
            ?.takeIf { it != "null" }
    }

    suspend fun syncNow() {
        post("/api/sync")
    }

    suspend fun labelDepartment(number: Int, label: String) {
        val body = buildJsonObject { put("label", label) }
        execute(request("/api/departments/$number").put(body.toString().toRequestBody(jsonType)).build())
    }

    suspend fun events(after: Long): List<Event> =
        get("/api/events?after=$after", ListSerializer(Event.serializer()))

    suspend fun dismiss(eventId: Long) {
        post("/api/events/$eventId/dismiss")
    }

    suspend fun exportCsv(start: String?, end: String?, out: OutputStream) = withContext(Dispatchers.IO) {
        val req = request("/api/export.csv" + rangeQuery(start, end)).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "Export failed (${resp.code})")
            resp.body!!.byteStream().copyTo(out)
        }
    }

    companion object {
        fun rangeQuery(start: String?, end: String?): String {
            val parts = listOfNotNull(start?.let { "start=$it" }, end?.let { "end=$it" })
            return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        }
    }
}

fun Throwable.friendlyMessage(): String = when (this) {
    is ApiException -> message ?: "Server error"
    is java.net.UnknownHostException -> "Can't reach the server"
    is java.net.ConnectException -> "Can't reach the server"
    is java.net.SocketTimeoutException -> "The server took too long to answer"
    else -> message ?: javaClass.simpleName
}
