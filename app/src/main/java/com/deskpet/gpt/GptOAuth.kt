package com.deskpet.gpt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-authorized ChatGPT plan use: public OAuth/PKCE client, no API key. */
internal data class GptSession(
    val clientId: String, val subject: String, val email: String,
    val accessToken: String, val refreshToken: String, val idToken: String,
    val scopes: String, val expiresAt: Long
) {
    val allowed: Boolean get() = scopes.split(' ').contains("chatgpt.tokens.use.direct")
    fun json() = JSONObject().apply {
        put("clientId", clientId); put("subject", subject); put("email", email)
        put("accessToken", accessToken); put("refreshToken", refreshToken)
        put("idToken", idToken); put("scopes", scopes); put("expiresAt", expiresAt)
    }
    companion object {
        fun parse(j: JSONObject) = GptSession(
            j.getString("clientId"), j.getString("subject"), j.optString("email"),
            j.getString("accessToken"), j.getString("refreshToken"),
            j.getString("idToken"), j.getString("scopes"), j.getLong("expiresAt")
        )
    }
}

/** All OAuth credentials reside in one atomically replaced Android Keystore-encrypted record. */
internal class GptCredentials(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gpt_oauth_private", Context.MODE_PRIVATE)
    private val alias = "tapnest_gpt_oauth_v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun hostId(): String {
        prefs.getString("host", null)?.let { return it }
        val value = "urn:uuid:" + UUID.randomUUID().toString()
        check(prefs.edit().putString("host", value).commit()) { "无法保存设备授权标识" }
        return value
    }
    @Synchronized fun read(): GptSession? {
        val value = prefs.getString("session", null) ?: return null
        val raw = Base64.decode(value, Base64.NO_WRAP)
        require(raw.size > 12) { "登录记录损坏，请重新授权" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        }
        return GptSession.parse(JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)))
    }
    @Synchronized fun save(session: GptSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(session.json().toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("session", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
            "无法安全保存授权信息"
        }
    }
    @Synchronized fun signOut() {
        check(prefs.edit().remove("session").commit()) { "无法结束本地登录" }
        // Keep the host identifier stable for future reauthorization.
    }
}

internal object GptNetwork {
    fun request(url: String, method: String = "GET", bearer: String? = null, form: String? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.requestMethod = method
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/json")
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer)
        if (form != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            if (form != null) c.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(16000) }.orEmpty()
            if (code !in 200..299) {
                val diagnostic = runCatching {
                    val j = JSONObject(body)
                    j.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }
                        ?: j.optString("detail").take(200)
                }.getOrDefault("")
                throw IllegalStateException("HTTP " + code + (if (diagnostic.isNotBlank()) " · " + diagnostic else ""))
            }
            return JSONObject(body)
        } finally { c.disconnect() }
    }
    fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
    fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

internal class GptOAuth(private val store: GptCredentials) {
    private val random = SecureRandom()
    private val tokenUrl = "https://auth.openai.com/api/accounts/oauth/token"
    private val resource = "https://api.openai.com/v1"
    private fun randomUrlSafe(bytes: Int = 32): String {
        val data = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
    data class Pending(
        val server: ServerSocket, val redirectUri: String,
        val browserUri: Uri, val verifier: String, val state: String,
        val nonce: String, val previous: GptSession?
    ) {
        fun close() = server.close()
    }
    /** Bind loopback before launching the system browser. Port may vary, path must not. */
    fun begin(): Pending {
        val old = store.read()
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        listener.soTimeout = 180_000
        val redirect = "http://127.0.0.1:" + listener.localPort + "/auth/callback"
        val verifier = randomUrlSafe(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val state = randomUrlSafe()
        val nonce = randomUrlSafe()
        val client = old?.clientId ?: "dynamic_agent_client"
        val args = mutableListOf(
            "client_id" to client, "ext_agent_host_id" to store.hostId(),
            "response_type" to "code", "redirect_uri" to redirect,
            "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            "resource" to resource, "state" to state, "nonce" to nonce,
            "code_challenge_method" to "S256", "code_challenge" to challenge
        )
        if (old == null) args.add("agent_name_hint" to "TapNest")
        // Do not put a retained ID token in a URL: skipping the optional hint is safer.
        val uri = Uri.parse("https://auth.openai.com/api/accounts/authorize?" + GptNetwork.form(*args.toTypedArray()))
        return Pending(listener, redirect, uri, verifier, state, nonce, old)
    }
    fun complete(p: Pending): GptSession {
        try {
            val callback = p.server.accept().use { socket ->
                socket.soTimeout = 12000
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val first = reader.readLine().orEmpty()
                // Avoid logging or rendering the query containing the authorization code.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = first.split(' ').getOrNull(1).orEmpty()
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n" +
                    "<html><meta charset='utf-8'><body style='font-family:sans-serif;padding:2em'>" +
                    "授权回调已收到，请返回点点栖查看结果。<br>Return to TapNest.</body></html>"
                socket.getOutputStream().write(response.toByteArray(StandardCharsets.UTF_8))
                require(first.startsWith("GET /auth/callback?")) { "授权回调路径不正确" }
                Uri.parse("http://127.0.0.1" + path)
            }
            check(callback.getQueryParameter("state") == p.state) { "授权校验失败（state）" }
            callback.getQueryParameter("error")?.let { throw IllegalStateException("授权未完成：" + it) }
            val code = callback.getQueryParameter("code")?.takeIf { it.isNotBlank() }
                ?: error("未收到授权代码")
            val receivedId = callback.getQueryParameter("client_id")
            val clientId = if (p.previous == null) {
                receivedId?.takeIf { it.startsWith("oaiapp_") }
                    ?: error("首次注册未返回有效的 client_id")
            } else {
                check(receivedId == null || receivedId == p.previous.clientId) { "授权账号不匹配" }
                p.previous.clientId
            }
            val j = GptNetwork.request(tokenUrl, "POST", form = GptNetwork.form(
                "grant_type" to "authorization_code", "client_id" to clientId, "code" to code,
                "code_verifier" to p.verifier, "redirect_uri" to p.redirectUri, "resource" to resource
            ))
            val token = j.getString("id_token")
            val claims = verifyIdentity(token, clientId, p.nonce)
            val sub = claims.getString("sub")
            if (p.previous != null) check(p.previous.subject == sub) { "登录的不是原来的 ChatGPT 账号" }
            return GptSession(
                clientId, sub, claims.optString("email"),
                j.getString("access_token"), j.getString("refresh_token"), token,
                j.optString("scope"), System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
            ).also { store.save(it) }
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("浏览器授权超时，请回到点点栖重新登录", e)
        } finally { p.close() }
    }
    private fun verifyIdentity(jwt: String, audience: String, nonce: String): JSONObject {
        val parts = jwt.split('.')
        require(parts.size == 3) { "无效的账号身份凭证" }
        fun dec(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP)
        val header = JSONObject(String(dec(parts[0]), Charsets.UTF_8))
        check(header.optString("alg") == "RS256") { "身份签名算法不受支持" }
        val jwks = GptNetwork.request("https://auth.openai.com/.well-known/jwks.json")
        val keys = jwks.getJSONArray("keys")
        var found: JSONObject? = null
        for (i in 0 until keys.length()) {
            val key = keys.getJSONObject(i)
            if (key.optString("kid") == header.optString("kid") && key.optString("kty") == "RSA") {
                found = key; break
            }
        }
        val jwk = found ?: error("无法找到账号身份签名密钥")
        val modulus = BigInteger(1, dec(jwk.getString("n")))
        val exponent = BigInteger(1, dec(jwk.getString("e")))
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val signature = Signature.getInstance("SHA256withRSA").apply { initVerify(pub) }
        signature.update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
        check(signature.verify(dec(parts[2]))) { "账号身份签名校验失败" }
        val payload = JSONObject(String(dec(parts[1]), Charsets.UTF_8))
        check(payload.optString("iss") == "https://auth.openai.com") { "账号签发者不匹配" }
        val aud = payload.opt("aud")
        val audienceOk = when (aud) {
            is String -> aud == audience
            is JSONArray -> (0 until aud.length()).any { aud.optString(it) == audience }
            else -> false
        }
        check(audienceOk) { "账号接收方不匹配" }
        val now = System.currentTimeMillis() / 1000
        check(payload.optLong("exp") > now && payload.optLong("iat") <= now + 120) { "账号身份凭证已过期" }
        check(payload.optString("nonce") == nonce && payload.optString("sub").isNotBlank()) {
            "身份随机校验失败"
        }
        return payload
    }
    /** Refresh on an IO thread; never fall back to a chargeable API key. */
    @Synchronized fun accessToken(): String {
        val current = store.read() ?: error("请先使用 ChatGPT 登录")
        check(current.allowed) { "账号尚未允许使用 ChatGPT 订阅额度，请重新授权" }
        if (System.currentTimeMillis() + 120_000 < current.expiresAt) return current.accessToken
        val j = GptNetwork.request(tokenUrl, "POST", form = GptNetwork.form(
            "grant_type" to "refresh_token", "client_id" to current.clientId,
            "refresh_token" to current.refreshToken, "resource" to resource
        ))
        val updated = current.copy(
            accessToken = j.getString("access_token"),
            refreshToken = j.getString("refresh_token"),
            idToken = j.optString("id_token").takeIf { it.isNotBlank() } ?: current.idToken,
            scopes = j.optString("scope").takeIf { it.isNotBlank() } ?: current.scopes,
            expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
        )
        store.save(updated)
        check(updated.allowed) { "ChatGPT 订阅额度授权已失效" }
        return updated.accessToken
    }
    /** Best-effort remote revoke; local credentials remain until explicit sign-out. */
    fun signOut() {
        val current = store.read()
        if (current != null) runCatching {
            GptNetwork.request("https://auth.openai.com/api/accounts/oauth/revoke", "POST", form =
                GptNetwork.form("token" to current.refreshToken, "token_type_hint" to "refresh_token",
                    "client_id" to current.clientId))
        }
        store.signOut()
    }
}
