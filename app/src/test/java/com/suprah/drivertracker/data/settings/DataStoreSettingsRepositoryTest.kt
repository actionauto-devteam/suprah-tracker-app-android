package com.suprah.drivertracker.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.core.AppConfig
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.domain.DeviceCredentials
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Spec B-2, P-6 and H-9: what the app remembers. */
class DataStoreSettingsRepositoryTest {
    /** Stands in for the Android Keystore, which only exists on a phone. */
    private class FakeCipher : DeviceKeyCipher {
        override fun encrypt(plain: String) = "sealed:" + plain.reversed()

        override fun decrypt(encoded: String) = encoded.removePrefix("sealed:").reversed()
    }

    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    private val devConfig = AppConfig("http://localhost:5000", serverEditable = true, allowHttp = true, "https://portal", "1.0.0")
    private val prodConfig = devConfig.copy(defaultServerUrl = "https://api.suprah-app.com", serverEditable = false, allowHttp = false)

    private fun repository(config: AppConfig = devConfig) = DataStoreSettingsRepository(dataStore, FakeCipher(), config)

    @Before
    fun setUp() {
        directory = createTempDirectory("settings").toFile()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "tracker_settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun `a new phone is unpaired, off duty, waiting, on the built-in server`() = runBlocking<Unit> {
        val settings = repository().current()

        assertThat(settings.isPaired).isFalse()
        assertThat(settings.onDuty).isFalse()
        assertThat(settings.approval).isEqualTo(Approval.WAITING)
        assertThat(settings.serverUrl).isEqualTo("http://localhost:5000")
    }

    @Test
    fun `pairing is remembered and the key is never stored as plain text (P-6)`() = runBlocking<Unit> {
        val repository = repository()

        repository.savePairing(DeviceCredentials("dev-1", "the-key"), "Jo", Approval.APPROVED)

        val settings = repository.current()
        assertThat(settings.credentials).isEqualTo(DeviceCredentials("dev-1", "the-key"))
        assertThat(settings.driverName).isEqualTo("Jo")
        assertThat(settings.approval).isEqualTo(Approval.APPROVED)
        val stored = dataStore.data.first().asMap().values.map { it.toString() }
        assertThat(stored).doesNotContain("the-key")
    }

    @Test
    fun `unpairing forgets the phone and goes off duty but keeps the server address (H-9)`() = runBlocking<Unit> {
        val repository = repository()
        repository.setServerUrl("http://192.168.1.20:5000/")
        repository.savePairing(DeviceCredentials("dev-1", "the-key"), "Jo", Approval.APPROVED)
        repository.setOnDuty(true)
        repository.setLastSentAt(123L)

        repository.clearPairing()

        val settings = repository.current()
        assertThat(settings.isPaired).isFalse()
        assertThat(settings.onDuty).isFalse()
        assertThat(settings.driverName).isEmpty()
        assertThat(settings.lastSentAt).isEqualTo(0L)
        assertThat(settings.serverUrl).isEqualTo("http://192.168.1.20:5000")
    }

    @Test
    fun `the live build always uses its own server (B-2)`() = runBlocking<Unit> {
        repository(devConfig).setServerUrl("http://192.168.1.20:5000")

        assertThat(repository(prodConfig).current().serverUrl).isEqualTo("https://api.suprah-app.com")
        assertThat(repository(devConfig).current().serverUrl).isEqualTo("http://192.168.1.20:5000")
    }

    @Test
    fun `a key the phone can no longer read means not paired`() = runBlocking<Unit> {
        val broken =
            object : DeviceKeyCipher {
                override fun encrypt(plain: String) = "sealed"

                override fun decrypt(encoded: String): String? = null
            }
        val repository = DataStoreSettingsRepository(dataStore, broken, devConfig)

        repository.savePairing(DeviceCredentials("dev-1", "the-key"), "Jo", Approval.APPROVED)

        assertThat(repository.current().isPaired).isFalse()
    }
}
