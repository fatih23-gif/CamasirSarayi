package com.camasirsarayi.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import org.chromium.net.CronetEngine
import org.chromium.net.NetworkException
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

internal fun JSONObject.items(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}

internal fun json(vararg values: Pair<String, Any>): JSONObject = JSONObject(mapOf(*values))

private class WorkerRequestException(val code: FirebaseFunctionsException.Code, message: String) : Exception(message)

internal fun Throwable.laundryErrorCode(): FirebaseFunctionsException.Code? = when (this) {
    is FirebaseFunctionsException -> code
    is WorkerRequestException -> code
    else -> null
}

internal class LaundryRepository(context: Context) {
    private val appContext = context.applicationContext
    val auth: FirebaseAuth? = if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAuth.getInstance()
    private val preferences = context.getSharedPreferences("laundry", Context.MODE_PRIVATE)
    private val demo by lazy { DemoLaundry(preferences) }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    var staffSession: Boolean
        get() = auth?.currentUser?.uid?.let { preferences.getBoolean("staff_" + it, false) } ?: false
        set(value) { auth?.currentUser?.uid?.let { preferences.edit().putBoolean("staff_" + it, value).apply() } }

    var dormId: String
        get() = preferences.getString("dorm_" + (auth?.currentUser?.uid ?: "demo"), "") ?: ""
        set(value) = preferences.edit().putString("dorm_" + (auth?.currentUser?.uid ?: "demo"), value).apply()

    var staffDraftDorm: String
        get() = auth?.currentUser?.uid?.let { preferences.getString("staffDraft_" + it, "") }.orEmpty()
        set(value) { auth?.currentUser?.uid?.let { preferences.edit().putString("staffDraft_" + it, value).apply() } }

    var staffDraftName: String
        get() = auth?.currentUser?.uid?.let { preferences.getString("staffDraftName_" + it, "") }.orEmpty()
        set(value) { auth?.currentUser?.uid?.let { preferences.edit().putString("staffDraftName_" + it, value).apply() } }

    var studentDraftDorm: String
        get() = auth?.currentUser?.uid?.let { preferences.getString("studentDraft_" + it, "") }.orEmpty()
        set(value) { auth?.currentUser?.uid?.let { preferences.edit().putString("studentDraft_" + it, value).apply() } }

    var kykDraftDorm: String
        get() = auth?.currentUser?.uid?.let { preferences.getString("kykDraft_" + it, "") }.orEmpty()
        set(value) { auth?.currentUser?.uid?.let { preferences.edit().putString("kykDraft_" + it, value).apply() } }

    var verificationSentAt: Long
        get() = auth?.currentUser?.uid?.let { preferences.getLong("verificationSent_" + it, 0) } ?: 0
        set(value) { auth?.currentUser?.uid?.let { preferences.edit().putLong("verificationSent_" + it, value).apply() } }

    fun staffDorms(result: (Result<JSONObject>) -> Unit) = publicDorms("staff-dorms", result)

    fun kykDorms(result: (Result<JSONObject>) -> Unit) = publicDorms("kyk-catalog", result)

    private fun publicDorms(endpoint: String, result: (Result<JSONObject>) -> Unit) {
        if (BuildConfig.LAUNDRY_WORKER_URL.isEmpty()) {
            if (endpoint == "staff-dorms" && auth?.currentUser != null) readDormCatalog(result)
            else result(Result.failure(IllegalStateException("Personel kataloğu için sunucu bağlantısı gerekiyor.")))
            return
        }
        workerExecutor.execute {
            val reply = runCatching { callWorker(endpoint, null, null, workerRequestIds.incrementAndGet()) }
            mainHandler.post { result(reply) }
        }
    }

    fun call(action: String, arguments: Map<String, Any>, result: (Result<JSONObject>) -> Unit) {
        if (auth == null) {
            result(runCatching { demo.call(action, JSONObject(arguments)) })
            return
        }
        val network = connectivity?.let { it.getNetworkCapabilities(it.activeNetwork) }
        if (network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            result(Result.failure(IllegalStateException("İnternet bağlantısı yok.")))
            return
        }
        val function = if (action == "deleteAccount") "deleteAccount" else "laundry"
        val payload = mapOf("dormId" to dormId) + arguments + ("action" to action)
        if (BuildConfig.LAUNDRY_WORKER_URL.isNotEmpty()) {
            val user = auth.currentUser ?: return result(Result.failure(IllegalStateException("Oturum açman gerekiyor.")))
            val requestId = workerRequestIds.incrementAndGet()
            val transport = when {
                network.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            workerLog(requestId, "token:start action=$action transport=$transport")
            user.getIdToken(false).addOnCompleteListener { task ->
                val token = if (task.isSuccessful) task.result.token else null
                if (token == null) {
                    workerLog(requestId, "token:failed type=${task.exception?.javaClass?.simpleName ?: "MissingToken"}")
                    result(Result.failure(task.exception ?: IllegalStateException("Oturum doğrulanamadı.")))
                    return@addOnCompleteListener
                }
                workerLog(requestId, "token:ready")
                workerExecutor.execute {
                    val reply = runCatching {
                        val data = JSONObject(payload)
                        try {
                            callWorker(function, token, data, requestId)
                        } catch (error: SocketException) {
                            // Bu sorgular kullanıcı verisini değiştiren bir işlem başlatmaz.
                            if (action !in readOnlyWorkerActions) throw error
                            workerLog(requestId, "retry:fresh-connection")
                            callWorker(function, token, data, requestId)
                        }
                    }
                    mainHandler.post { finishCall(action, reply, result) }
                }
            }
            return
        }
        FirebaseFunctions.getInstance().getHttpsCallable(function)
            .call(payload)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    @Suppress("UNCHECKED_CAST")
                    val data = task.result.data as? Map<String, Any>
                    result(Result.success(if (data == null) JSONObject() else JSONObject(data)))
                } else {
                    finishCall(action, Result.failure(task.exception ?: IllegalStateException("Sunucuya ulaşılamadı.")), result)
                }
            }
    }

    private fun finishCall(action: String, reply: Result<JSONObject>, result: (Result<JSONObject>) -> Unit) {
        if (action == "catalog" && reply.exceptionOrNull()?.laundryErrorCode() == FirebaseFunctionsException.Code.NOT_FOUND) {
            readDormCatalog(result)
        } else {
            result(reply)
        }
    }

    private fun callWorker(function: String, token: String?, data: JSONObject?, requestId: Int): JSONObject {
        val url = URL("${BuildConfig.LAUNDRY_WORKER_URL}/${function}")
        val connection = workerEngine(context = appContext).openConnection(url) as HttpURLConnection
        var phase = "connect"
        try {
            val bytes = data?.toString()?.toByteArray(Charsets.UTF_8)
            connection.requestMethod = if (bytes == null) "GET" else "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            if (bytes != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token)
            workerLog(requestId, "request:${connection.requestMethod} ${url.host}${url.path}")
            connection.connect()
            phase = "write"
            if (bytes != null) connection.outputStream.use { it.write(bytes) }
            phase = "response"
            val statusCode = connection.responseCode
            workerLog(requestId, "response:HTTP $statusCode")
            phase = "body"
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(body) }.getOrNull()
            if (statusCode in 200..299) {
                return response?.optJSONObject("result") ?: error("Sunucudan geçerli bir yanıt alınamadı.")
            }
            val error = response?.optJSONObject("error")
            val diagnostic = Regex("\\(([A-Z0-9_]+)\\)$").find(error?.optString("message").orEmpty())?.groupValues?.get(1)
            if (diagnostic != null) workerLog(requestId, "response:diagnostic=$diagnostic")
            val code = runCatching {
                FirebaseFunctionsException.Code.valueOf(error?.optString("code").orEmpty().uppercase(java.util.Locale.ROOT).replace('-', '_'))
            }.getOrDefault(if (statusCode == 404) FirebaseFunctionsException.Code.NOT_FOUND else FirebaseFunctionsException.Code.UNAVAILABLE)
            throw WorkerRequestException(code, error?.optString("message").orEmpty().ifBlank { "Sunucuya ulaşılamadı." })
        } catch (error: java.io.IOException) {
            val cause = error.cause?.javaClass?.simpleName.orEmpty()
            val cronetCode = (error as? NetworkException)?.let { " cronet=${it.errorCode}/${it.cronetInternalErrorCode}" }.orEmpty()
            workerLog(requestId, "network:failed phase=$phase type=${error.javaClass.simpleName} cause=$cause$cronetCode")
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun workerLog(requestId: Int, event: String) {
        if (BuildConfig.DEBUG) Log.d("CamasirWorker", "#$requestId $event")
    }

    private companion object {
        @Volatile private var cronet: CronetEngine? = null

        private fun workerEngine(context: Context): CronetEngine = cronet ?: synchronized(this) {
            cronet ?: CronetEngine.Builder(context)
                .enableQuic(true)
                .addQuicHint(URL(BuildConfig.LAUNDRY_WORKER_URL).host, 443, 443)
                .build().also { cronet = it }
        }

        val workerRequestIds = AtomicInteger()
        val readOnlyWorkerActions = setOf("managerAccess", "catalog", "staffStatus", "staffReviews")
        val workerExecutor = Executors.newFixedThreadPool(2)
        val mainHandler = Handler(Looper.getMainLooper())
    }

    private fun readDormCatalog(result: (Result<JSONObject>) -> Unit) {
        FirebaseFirestore.getInstance().collection("dormCatalog")
            .whereEqualTo("active", true)
            .limit(100)
            .get(Source.SERVER)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    val error = task.exception
                    val message = if ((error as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                        "Yurt listesine erişilemiyor. Firebase yurt kataloğu kuralının yayımlandığını kontrol et."
                    else "Yurt listesi yüklenemedi. Bağlantını kontrol edip tekrar dene."
                    result(Result.failure(IllegalStateException(message, error)))
                    return@addOnCompleteListener
                }
                val dorms = task.result.documents.mapNotNull { doc ->
                    val name = doc.get("name") as? String ?: return@mapNotNull null
                    if (name.isBlank()) return@mapNotNull null
                    json("id" to doc.id, "name" to name, "city" to ((doc.get("city") as? String).orEmpty()),
                        "district" to ((doc.get("district") as? String).orEmpty()))
                }.sortedBy { it.optString("name") }
                result(Result.success(json("dorms" to JSONArray(dorms), "catalogOnly" to true)))
            }
    }
}
