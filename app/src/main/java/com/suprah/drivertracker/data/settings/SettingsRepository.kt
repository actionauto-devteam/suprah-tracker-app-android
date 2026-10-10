package com.suprah.drivertracker.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.suprah.drivertracker.core.AppConfig
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.domain.DeviceCredentials
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the app remembers between launches. */
data class TrackerSettings(
    val serverUrl: String,
    /** Null until the phone is paired. */
    val credentials: DeviceCredentials?,
    val driverName: String,
    val approval: Approval,
    /** The driver tapped Start on duty (survives restarts). */
    val onDuty: Boolean,
    val lastSentAt: Long,
) {
    val isPaired: Boolean get() = credentials != null
}

interface SettingsRepository {
    val settings: Flow<TrackerSettings>

    suspend fun current(): TrackerSettings = settings.first()

    suspend fun setServerUrl(url: String)

    suspend fun savePairing(credentials: DeviceCredentials, driverName: String, approval: Approval)

    suspend fun updateFromStatus(driverName: String, approval: Approval)

    suspend fun setOnDuty(onDuty: Boolean)

    suspend fun setLastSentAt(timeMs: Long)

    /** Forget the pairing (unlinked here, from the Driver Portal, or by Dispatch). Keeps the server address. */
    suspend fun clearPairing()
}

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: DeviceKeyCipher,
    private val config: AppConfig,
) : SettingsRepository {
    /** The last decrypted key, so a settings change doesn't decrypt again. */
    @Volatile private var decrypted: Pair<String, String?>? = null

    override val settings: Flow<TrackerSettings> =
        dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { it.toSettings() }
            .distinctUntilChanged()

    private fun Preferences.toSettings(): TrackerSettings {
        val deviceId = this[Keys.DEVICE_ID]
        val deviceKey = this[Keys.DEVICE_KEY_ENCRYPTED]?.let(::decryptCached)
        return TrackerSettings(
            // The live build always uses its built-in server, whatever was saved (spec B-2).
            serverUrl = if (config.serverEditable) this[Keys.SERVER_URL] ?: config.defaultServerUrl else config.defaultServerUrl,
            credentials = if (deviceId != null && deviceKey != null) DeviceCredentials(deviceId, deviceKey) else null,
            driverName = this[Keys.DRIVER_NAME].orEmpty(),
            approval = Approval.fromWire(this[Keys.APPROVAL]),
            onDuty = this[Keys.ON_DUTY] ?: false,
            lastSentAt = this[Keys.LAST_SENT_AT] ?: 0L,
        )
    }

    private fun decryptCached(encrypted: String): String? {
        decrypted?.let { (source, plain) -> if (source == encrypted) return plain }
        return cipher.decrypt(encrypted).also { decrypted = encrypted to it }
    }

    override suspend fun setServerUrl(url: String) {
        dataStore.edit { it[Keys.SERVER_URL] = url.trim().trimEnd('/') }
    }

    override suspend fun savePairing(credentials: DeviceCredentials, driverName: String, approval: Approval) {
        val encrypted = cipher.encrypt(credentials.deviceKey)
        dataStore.edit {
            it[Keys.DEVICE_ID] = credentials.deviceId
            it[Keys.DEVICE_KEY_ENCRYPTED] = encrypted
            it[Keys.DRIVER_NAME] = driverName
            it[Keys.APPROVAL] = approval.wire
        }
    }

    override suspend fun updateFromStatus(driverName: String, approval: Approval) {
        dataStore.edit {
            it[Keys.DRIVER_NAME] = driverName
            it[Keys.APPROVAL] = approval.wire
        }
    }

    override suspend fun setOnDuty(onDuty: Boolean) {
        dataStore.edit { it[Keys.ON_DUTY] = onDuty }
    }

    override suspend fun setLastSentAt(timeMs: Long) {
        dataStore.edit { it[Keys.LAST_SENT_AT] = timeMs }
    }

    override suspend fun clearPairing() {
        dataStore.edit {
            it.remove(Keys.DEVICE_ID)
            it.remove(Keys.DEVICE_KEY_ENCRYPTED)
            it.remove(Keys.DRIVER_NAME)
            it.remove(Keys.APPROVAL)
            it.remove(Keys.LAST_SENT_AT)
            it[Keys.ON_DUTY] = false
        }
        decrypted = null
    }

    private object Keys {
        val SERVER_URL = stringPreferencesKey("serverUrl")
        val DEVICE_ID = stringPreferencesKey("deviceId")
        val DEVICE_KEY_ENCRYPTED = stringPreferencesKey("deviceKeyEnc")
        val DRIVER_NAME = stringPreferencesKey("driverName")
        val APPROVAL = stringPreferencesKey("approval")
        val ON_DUTY = booleanPreferencesKey("onDuty")
        val LAST_SENT_AT = longPreferencesKey("lastSentAt")
    }
}
