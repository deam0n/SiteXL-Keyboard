package helium314.keyboard.sync

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.os.Build
import android.provider.UserDictionary
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.dictionary.history.UserHistoryDictionary
import helium314.keyboard.latin.makedict.ProbabilityInfo
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.core.content.edit
import androidx.annotation.RequiresApi

/**
 * Synchronises preferences, custom user files and learned vocabulary.
 * Standard language dictionaries stay under the existing dictionary management UI.
 */
object SiteXLSyncManager {
    private const val CREDENTIALS_PREFS = "sitexl_sync_credentials"
    private const val STATE_PREFS = "sitexl_sync_state"
    private const val KEY_TOKEN = "api_token"
    private const val KEY_USERNAME = "username"
    private const val TOKEN_KEY_ALIAS = "SiteXL.Sync.ApiToken"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_SETTINGS_HASH = "last_settings_hash"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val SNAPSHOT_SCHEMA_VERSION = 1
    private const val MAX_LOCAL_SNAPSHOT_BYTES = 25 * 1024 * 1024
    private const val MAX_SYNC_WORDS_PER_LOCALE = 5000
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 45000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class AuthRequest(val username: String, val password: String)

    @Serializable
    private data class AuthResponse(val username: String, val token: String)

    @Serializable
    private data class AuthErrorResponse(val detail: String = "")

    data class AccountSession(val username: String, val token: String)

    @Serializable
    private data class SnapshotListResponse(
        val snapshots: List<SnapshotMetadata> = emptyList(),
        @SerialName("latest_device_id") val latestDeviceId: String? = null
    )

    @Serializable
    private data class SnapshotMetadata(
        @SerialName("device_id") val deviceId: String,
        @SerialName("device_name") val deviceName: String,
        @SerialName("sha256") val sha256: String,
        @SerialName("updated_at") val updatedAt: Long,
        @SerialName("size_bytes") val sizeBytes: Int = 0
    )

    @Serializable
    private data class PersonalDictionaryWord(
        val word: String,
        val shortcut: String? = null,
        val frequency: Int,
        val locale: String? = null
    )

    @Serializable
    private data class VocabularyPayload(val locales: Map<String, List<SyncWord>>)

    @Serializable
    private data class VocabularyResponse(val locales: Map<String, List<SyncWord>> = emptyMap())

    @Serializable
    private data class SyncWord(
        val word: String,
        val frequency: Int,
        val timestamp: Int,
        @SerialName("is_not_a_word") val isNotAWord: Boolean = false,
        @SerialName("is_possibly_offensive") val isPossiblyOffensive: Boolean = false
    )

    fun getToken(context: Context): String {
        val stored = context.getSharedPreferences(CREDENTIALS_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "").orEmpty()
        if (stored.isBlank()) return ""
        if (stored.startsWith("enc:")) {
            return runCatching { decryptToken(stored.substringAfter("enc:")) }.getOrDefault("")
        }
        // Accept the previous local-only format during development upgrades.
        return stored.removePrefix("plain:")
    }

    fun storeToken(context: Context, token: String) {
        context.getSharedPreferences(CREDENTIALS_PREFS, Context.MODE_PRIVATE).edit {
            if (token.isBlank()) {
                remove(KEY_TOKEN)
            } else {
                val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    "enc:" + encryptToken(token.trim())
                } else {
                    "plain:" + token.trim()
                }
                putString(KEY_TOKEN, value)
            }
        }
    }

    fun getUsername(context: Context): String =
        context.getSharedPreferences(CREDENTIALS_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_USERNAME, "").orEmpty()

    fun isLoggedIn(context: Context): Boolean = getToken(context).isNotBlank()

    private fun normalizeServerUrl(raw: String): String {
        if (raw.isBlank()) throw IllegalArgumentException("Enter your SiteXL server URL.")
        val normalized = raw.trim().trimEnd('/')
        val parsed = runCatching { URL(normalized) }.getOrElse {
            throw IllegalArgumentException("The server URL is not valid.")
        }
        if (parsed.protocol != "https" || parsed.host.isBlank() || parsed.userInfo != null ||
            parsed.query != null || parsed.ref != null
        ) throw IllegalArgumentException("Use a valid HTTPS server URL, without a username, password or query.")
        return normalized
    }

    suspend fun authenticate(
        serverUrl: String,
        username: String,
        password: String,
        register: Boolean
    ): AccountSession = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val baseUrl = normalizeServerUrl(serverUrl)
        val cleanUsername = username.trim()
        if (cleanUsername.isBlank()) throw IllegalArgumentException("Enter your username.")
        if (password.length < 8 || password.length > 128) {
            throw IllegalArgumentException("Your password must contain between 8 and 128 characters.")
        }
        val body = json.encodeToString(AuthRequest(cleanUsername, password)).encodeToByteArray()
        val path = if (register) "/api/v1/auth/register" else "/api/v1/auth/login"
        val connection = URL(baseUrl + path).openConnection() as? HttpsURLConnection
            ?: throw IOException("The server must use HTTPS.")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(16000) }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { json.decodeFromString<AuthErrorResponse>(responseText).detail }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                throw IOException(detail ?: "Authentication failed (HTTP $code).")
            }
            val response = json.decodeFromString<AuthResponse>(responseText)
            if (response.token.isBlank() || response.username.isBlank()) {
                throw IOException("The server returned an invalid sign-in response.")
            }
            AccountSession(response.username, response.token)
        } finally {
            connection.disconnect()
        }
    }

    fun saveAccount(context: Context, serverUrl: String, username: String, token: String) {
        val normalized = normalizeServerUrl(serverUrl)
        context.prefs().edit { putString(Settings.PREF_SERVER_URL, normalized) }
        context.getSharedPreferences(CREDENTIALS_PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_USERNAME, username.trim())
        }
        storeToken(context, token)
    }

    suspend fun logout(context: Context): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val token = getToken(context)
            var message = "You are signed out on this device."
            try {
                if (token.isNotBlank()) {
                    request(serverBaseUrl(context), token, "POST", "/api/v1/auth/logout", ByteArray(0), "application/json")
                    message = "Signed out. Your session was revoked on the server."
                }
            } catch (_: Exception) {
                message = "Signed out on this device. The server session could not be revoked while offline."
            } finally {
                storeToken(context, "")
                context.getSharedPreferences(CREDENTIALS_PREFS, Context.MODE_PRIVATE).edit {
                    remove(KEY_USERNAME)
                    remove(KEY_DEVICE_ID)
                }
                statePrefs(context).edit {
                    remove(KEY_LAST_SETTINGS_HASH)
                    remove(KEY_LAST_SYNC_AT)
                    remove("last_sync_completed_at")
                }
                context.getSharedPreferences("sitexl_account_state", Context.MODE_PRIVATE).edit {
                    putBoolean("completed", false)
                }
                SiteXLSyncScheduler.schedule(context, false)
            }
            message
        }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getOrCreateTokenKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(TOKEN_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                TOKEN_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun encryptToken(token: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateTokenKey())
        val encrypted = cipher.doFinal(token.encodeToByteArray())
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun decryptToken(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        if (payload.size <= 12) return ""
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(TOKEN_KEY_ALIAS, null) as? SecretKey ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    fun isConfigured(context: Context): Boolean =
        context.prefs().getString(Settings.PREF_SERVER_URL, "").orEmpty().isNotBlank() &&
            getToken(context).isNotBlank()

    fun isAutoSyncEnabled(context: Context): Boolean =
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getBoolean("auto_sync", true)

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit { putBoolean("auto_sync", enabled) }
    }

    private fun statePrefs(context: Context) =
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(CREDENTIALS_PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "")
        prefs.edit { putString(KEY_DEVICE_ID, created) }
        return created
    }

    private fun serverBaseUrl(context: Context): String {
        val raw = context.prefs().getString(Settings.PREF_SERVER_URL, Defaults.PREF_SERVER_URL)?.trim().orEmpty()
        if (raw.isBlank()) throw IllegalStateException("Enter your SiteXL sync server URL first.")
        return normalizeServerUrl(raw)
    }

    suspend fun sync(context: Context): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val baseUrl = serverBaseUrl(context)
            val token = getToken(context)
            if (token.isBlank()) throw IllegalStateException("Enter your SiteXL API token first.")
            val id = deviceId(context)
            val currentArchive = createSettingsSnapshot(context)
            val currentHash = sha256(currentArchive)
            val state = statePrefs(context)
            val lastHash = state.getString(KEY_LAST_SETTINGS_HASH, null)
            val metadataText = request(
                baseUrl, token, "GET", "/api/v1/sync/settings", null, "application/json"
            ).decodeToString()
            val snapshots = json.decodeFromString<SnapshotListResponse>(metadataText).snapshots
            val latest = snapshots.maxByOrNull { it.updatedAt }
            var finalHash = currentHash
            var settingsMessage = "Settings are already in sync."

            if (lastHash == null) {
                if (latest != null && latest.sha256 != currentHash) {
                    val remoteArchive = request(
                        baseUrl, token, "GET", "/api/v1/sync/settings/${latest.deviceId}", null, "application/zip"
                    )
                    applySettingsSnapshot(context, remoteArchive)
                    settingsMessage = "Settings restored from ${latest.deviceName}."
                }
                val snapshotToRegister = createSettingsSnapshot(context)
                finalHash = sha256(snapshotToRegister)
                if (snapshots.none { it.deviceId == id }) {
                    uploadSettings(baseUrl, token, id, snapshotToRegister)
                    if (latest == null) settingsMessage = "Initial settings backup uploaded."
                }
            } else {
                val localChanged = currentHash != lastHash
                val remoteChanged = latest != null &&
                    latest.sha256 != currentHash &&
                    latest.deviceId != id
                if (localChanged || (latest == null && lastHash.isNotBlank())) {
                    uploadSettings(baseUrl, token, id, currentArchive)
                    finalHash = currentHash
                    settingsMessage = "Local settings uploaded."
                } else if (remoteChanged && latest != null) {
                    val remoteArchive = request(
                        baseUrl, token, "GET", "/api/v1/sync/settings/${latest.deviceId}", null, "application/zip"
                    )
                    applySettingsSnapshot(context, remoteArchive)
                    val restoredArchive = createSettingsSnapshot(context)
                    finalHash = sha256(restoredArchive)
                    // Keep a current restore point registered for this device as well.
                    uploadSettings(baseUrl, token, id, restoredArchive)
                    settingsMessage = "Settings updated from ${latest.deviceName}."
                } else if (snapshots.none { it.deviceId == id }) {
                    uploadSettings(baseUrl, token, id, currentArchive)
                    settingsMessage = "This device has been added to your sync account."
                }
            }

            val vocabulary = collectVocabulary(context)
            request(
                baseUrl, token, "PUT", "/api/v1/sync/vocabulary/$id",
                json.encodeToString(vocabulary).encodeToByteArray(), "application/json"
            )
            val mergedText = request(
                baseUrl, token, "GET", "/api/v1/sync/vocabulary", null, "application/json"
            ).decodeToString()
            val merged = json.decodeFromString<VocabularyResponse>(mergedText)
            val mergedCount = applyVocabulary(context, merged)

            state.edit {
                putString(KEY_LAST_SETTINGS_HASH, finalHash)
                putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis() / 1000L)
                putLong("last_sync_completed_at", System.currentTimeMillis())
            }
            "$settingsMessage Learned vocabulary synchronised ($mergedCount entries available)."
        }

    private fun uploadSettings(baseUrl: String, token: String, deviceId: String, archive: ByteArray) {
        val deviceName = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).joinToString(" ").trim()
        request(
            baseUrl, token, "PUT", "/api/v1/sync/settings/$deviceId", archive, "application/zip",
            mapOf(
                "X-Device-Name" to deviceName,
                "X-App-Version" to helium314.keyboard.latin.BuildConfig.VERSION_NAME,
                "X-Schema-Version" to SNAPSHOT_SCHEMA_VERSION.toString()
            )
        )
    }

    private fun request(
        baseUrl: String,
        token: String,
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): ByteArray {
        val connection = URL(baseUrl + path).openConnection() as? HttpsURLConnection
            ?: throw IOException("The sync server must use HTTPS.")
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", if (contentType == "application/zip") "application/zip" else "application/json")
            extraHeaders.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_LOCAL_SNAPSHOT_BYTES * 5) throw IOException("The server response is too large.")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            if (code !in 200..299) {
                val detail = response.decodeToString().take(300)
                throw IOException("Sync server returned HTTP $code${if (detail.isBlank()) "" else ": $detail"}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun createSettingsSnapshot(context: Context): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val regularPrefs = context.prefs().all.filterKeys { it != Settings.PREF_SERVER_URL }
            putText(zip, "preferences.json", encodeSettings(regularPrefs))
            putText(zip, "protected_preferences.json", encodeSettings(context.protectedPrefs().all))
            putText(zip, "personal_dictionary.json", json.encodeToString(collectPersonalDictionary(context)))
            val roots = listOf(
                context.filesDir to "files",
                DeviceProtectedUtils.getFilesDir(context) to "device"
            )
            val entries = roots.flatMap { (root, prefix) ->
                root.walkTopDown().filter { it.isFile }.mapNotNull { file ->
                    val relative = relativePath(file, root)
                    if (isSyncFile(relative)) Triple(root, prefix, file) else null
                }.toList()
            }.sortedBy { "${it.second}/${relativePath(it.third, it.first)}" }
            entries.forEach { (root, prefix, file) ->
                val relative = relativePath(file, root)
                putBytes(zip, "$prefix/$relative", file.readBytes())
            }
        }
        val bytes = output.toByteArray()
        if (bytes.size > MAX_LOCAL_SNAPSHOT_BYTES) {
            throw IOException("Settings backup exceeds 25 MB. Remove large custom files before syncing.")
        }
        return bytes
    }

    private fun collectPersonalDictionary(context: Context): List<PersonalDictionaryWord> {
        val projection = arrayOf(
            UserDictionary.Words.WORD,
            UserDictionary.Words.SHORTCUT,
            UserDictionary.Words.FREQUENCY,
            UserDictionary.Words.LOCALE
        )
        val entries = mutableListOf<PersonalDictionaryWord>()
        context.contentResolver.query(
            UserDictionary.Words.CONTENT_URI, projection, null, null, null
        )?.use { cursor ->
            val wordIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.WORD)
            val shortcutIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.SHORTCUT)
            val frequencyIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.FREQUENCY)
            val localeIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.LOCALE)
            while (cursor.moveToNext()) {
                val word = cursor.getString(wordIndex)?.takeIf { it.isNotBlank() } ?: continue
                entries += PersonalDictionaryWord(
                    word = word,
                    shortcut = cursor.getString(shortcutIndex)?.takeIf { it.isNotBlank() },
                    frequency = cursor.getInt(frequencyIndex).coerceIn(0, 255),
                    locale = cursor.getString(localeIndex)?.takeIf { it.isNotBlank() }
                )
            }
        }
        return entries.distinctBy { listOf(it.word, it.shortcut.orEmpty(), it.locale.orEmpty()) }
            .sortedWith(compareBy<PersonalDictionaryWord> { it.locale.orEmpty() }.thenBy { it.word }.thenBy { it.shortcut.orEmpty() })
    }

    private fun applyPersonalDictionary(context: Context, entries: List<PersonalDictionaryWord>) {
        if (entries.isEmpty()) return
        val projection = arrayOf(
            UserDictionary.Words.WORD,
            UserDictionary.Words.SHORTCUT,
            UserDictionary.Words.LOCALE
        )
        val existing = mutableSetOf<String>()
        context.contentResolver.query(
            UserDictionary.Words.CONTENT_URI, projection, null, null, null
        )?.use { cursor ->
            val wordIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.WORD)
            val shortcutIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.SHORTCUT)
            val localeIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.LOCALE)
            while (cursor.moveToNext()) {
                existing += listOf(
                    cursor.getString(wordIndex).orEmpty(),
                    cursor.getString(shortcutIndex).orEmpty(),
                    cursor.getString(localeIndex).orEmpty()
                ).joinToString("\u0000")
            }
        }
        entries.forEach { entry ->
            val key = listOf(entry.word, entry.shortcut.orEmpty(), entry.locale.orEmpty()).joinToString("\u0000")
            if (key !in existing) {
                val locale = entry.locale?.replace('_', '-')?.let { Locale.forLanguageTag(it) }
                UserDictionary.Words.addWord(
                    context, entry.word, entry.frequency, entry.shortcut, locale
                )
                existing += key
            }
        }
    }

    private fun relativePath(file: File, root: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun isSyncFile(path: String): Boolean =
        path.matches(Regex("""blacklists/.*\.txt""")) ||
            path.matches(Regex("""layouts/.*custom\..*""")) ||
            path.matches(Regex("""dicts/.*/.*user\.dict""")) ||
            path.matches(Regex("""custom_background_image.*""")) ||
            path == "custom_font" ||
            path == "custom_emoji_font"

    private fun putText(zip: ZipOutputStream, name: String, value: String) =
        putBytes(zip, name, value.encodeToByteArray())

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        // Stored ZIP entries make hashes deterministic across Android/Deflater versions.
        val crc = CRC32().apply { update(bytes, 0, bytes.size) }.value
        val entry = ZipEntry(name).apply {
            time = 0L
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun encodeSettings(settings: Map<String, *>): String {
        val booleans = settings.filter { it.key is String && it.value is Boolean }.mapValues { it.value as Boolean }.toSortedMap()
        val ints = settings.filter { it.key is String && it.value is Int }.mapValues { it.value as Int }.toSortedMap()
        val longs = settings.filter { it.key is String && it.value is Long }.mapValues { it.value as Long }.toSortedMap()
        val floats = settings.filter { it.key is String && it.value is Float }.mapValues { it.value as Float }.toSortedMap()
        val strings = settings.filter { it.key is String && it.value is String }.mapValues { it.value as String }.toSortedMap()
        val stringSets = settings.filter { it.key is String && it.value is Set<*> }
            .mapValues { (it.value as Set<*>).filterIsInstance<String>().toSortedSet() }.toSortedMap()
        return buildString {
            append("boolean settings\n").append(json.encodeToString(booleans))
            append("\nint settings\n").append(json.encodeToString(ints))
            append("\nlong settings\n").append(json.encodeToString(longs))
            append("\nfloat settings\n").append(json.encodeToString(floats))
            append("\nstring settings\n").append(json.encodeToString(strings))
            append("\nstring set settings\n").append(json.encodeToString(stringSets))
        }
    }

    private fun decodeSettings(data: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val lines = data.split('\n')
        var index = 0
        while (index + 1 < lines.size) {
            val section = lines[index++]
            val payload = lines[index++]
            when (section) {
                "boolean settings" -> result.putAll(json.decodeFromString<Map<String, Boolean>>(payload))
                "int settings" -> result.putAll(json.decodeFromString<Map<String, Int>>(payload))
                "long settings" -> result.putAll(json.decodeFromString<Map<String, Long>>(payload))
                "float settings" -> result.putAll(json.decodeFromString<Map<String, Float>>(payload))
                "string settings" -> result.putAll(json.decodeFromString<Map<String, String>>(payload))
                "string set settings" -> result.putAll(json.decodeFromString<Map<String, Set<String>>>(payload))
            }
        }
        return result
    }

    private fun applySettingsSnapshot(context: Context, archive: ByteArray) {
        val regularSettings = mutableMapOf<String, Any>()
        val protectedSettings = mutableMapOf<String, Any>()
        val regularFiles = mutableMapOf<String, ByteArray>()
        val protectedFiles = mutableMapOf<String, ByteArray>()
        var personalDictionary: List<PersonalDictionaryWord> = emptyList()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            var total = 0
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zip.readBytes()
                    total += bytes.size
                    if (total > MAX_LOCAL_SNAPSHOT_BYTES * 4) throw IOException("Settings snapshot is too large.")
                    when (entry.name) {
                        "preferences.json" -> regularSettings.putAll(decodeSettings(bytes.decodeToString()))
                        "protected_preferences.json" -> protectedSettings.putAll(decodeSettings(bytes.decodeToString()))
                        "personal_dictionary.json" -> personalDictionary = json.decodeFromString(bytes.decodeToString())
                        else -> {
                            val isDevice = entry.name.startsWith("device/")
                            val isFiles = entry.name.startsWith("files/")
                            if (isDevice || isFiles) {
                                val relative = entry.name.substringAfter('/')
                                if (!isSyncFile(relative) || relative.startsWith("/") ||
                                    relative.split('/').any { it == ".." || it.isBlank() }
                                ) throw IOException("Unsafe file in settings snapshot.")
                                if (isDevice) protectedFiles[relative] = bytes else regularFiles[relative] = bytes
                            } else throw IOException("Unexpected settings snapshot entry.")
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val oldServerUrl = context.prefs().getString(Settings.PREF_SERVER_URL, null)
        Settings.getInstance().stopListener()
        try {
            replaceSyncedFiles(context.filesDir, regularFiles)
            replaceSyncedFiles(DeviceProtectedUtils.getFilesDir(context), protectedFiles)
            context.prefs().edit {
                clear()
                regularSettings.forEach { (key, value) -> putPreference(key, value) }
                if (oldServerUrl != null) putString(Settings.PREF_SERVER_URL, oldServerUrl)
            }
            context.protectedPrefs().edit {
                clear()
                protectedSettings.forEach { (key, value) -> putPreference(key, value) }
            }
        } finally {
            Settings.getInstance().startListener()
        }
        applyPersonalDictionary(context, personalDictionary)
        LayoutUtilsCustom.onLayoutFileChanged()
        SubtypeSettings.reloadEnabledSubtypes(context)
        context.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    private fun replaceSyncedFiles(root: File, incoming: Map<String, ByteArray>) {
        val incomingNames = incoming.keys
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (isSyncFile(relative) && relative !in incomingNames) file.delete()
        }
        incoming.forEach { (relative, bytes) ->
            val target = File(root, relative)
            val canonicalRoot = root.canonicalFile
            val canonicalTarget = target.canonicalFile
            if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                throw IOException("Invalid path in settings snapshot.")
            }
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".sitexl-tmp")
            temporary.writeBytes(bytes)
            if (target.exists() && !target.delete()) throw IOException("Could not replace $relative")
            if (!temporary.renameTo(target)) throw IOException("Could not install $relative")
        }
    }

    private fun SharedPreferences.Editor.putPreference(key: String, value: Any) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun collectVocabulary(context: Context): VocabularyPayload {
        val locales = SubtypeSettings.getEnabledSubtypes(true)
            .map { it.locale() }
            .filter { it.language.isNotBlank() }
            .distinctBy { it.toLanguageTag() }
        val byLocale = linkedMapOf<String, List<SyncWord>>()
        locales.forEach { locale ->
            val properties = UserHistoryDictionary.getDictionary(context, locale).wordPropertiesForSync()
            byLocale[locale.toLanguageTag()] = properties.asSequence()
                .filter { it.mWord.isNotBlank() && it.mProbabilityInfo != null && it.isValid() }
                .sortedByDescending { it.getProbability() }
                .take(MAX_SYNC_WORDS_PER_LOCALE)
                .map { property ->
                    SyncWord(
                        word = property.mWord,
                        frequency = property.getProbability().coerceIn(0, 255),
                        timestamp = property.mProbabilityInfo.mTimestamp.coerceAtLeast(-1),
                        isNotAWord = property.mIsNotAWord,
                        isPossiblyOffensive = property.mIsPossiblyOffensive
                    )
                }.toList()
        }
        return VocabularyPayload(byLocale)
    }

    private fun applyVocabulary(context: Context, payload: VocabularyResponse): Int {
        var count = 0
        payload.locales.forEach { (languageTag, words) ->
            val locale = Locale.forLanguageTag(languageTag)
            if (locale.language.isBlank()) return@forEach
            val dictionary = UserHistoryDictionary.getDictionary(context, locale)
            val properties = words.asSequence()
                .filter { it.word.isNotBlank() && it.word.length <= 80 }
                .take(MAX_SYNC_WORDS_PER_LOCALE)
                .map { word ->
                    WordProperty(
                        word.word,
                        ProbabilityInfo(word.frequency.coerceIn(0, 255), word.timestamp.coerceAtLeast(-1), 0, 1),
                        null,
                        null,
                        word.isNotAWord,
                        word.isPossiblyOffensive
                    )
                }.toList()
            if (!dictionary.mergeWordPropertiesForSync(properties)) {
                throw IOException("Could not load the local learned-word dictionary for $languageTag.")
            }
            count += properties.size
        }
        return count
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
