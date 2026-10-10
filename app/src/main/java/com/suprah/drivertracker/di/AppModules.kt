package com.suprah.drivertracker.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.suprah.drivertracker.BuildConfig
import com.suprah.drivertracker.core.AppConfig
import com.suprah.drivertracker.core.ApplicationScope
import com.suprah.drivertracker.core.Clock
import com.suprah.drivertracker.core.IoDispatcher
import com.suprah.drivertracker.core.SystemClock
import com.suprah.drivertracker.data.positions.PositionDatabase
import com.suprah.drivertracker.data.positions.PositionStore
import com.suprah.drivertracker.data.positions.RoomPositionStore
import com.suprah.drivertracker.data.remote.RetrofitTrackerApi
import com.suprah.drivertracker.data.remote.SlowCallTimeoutInterceptor
import com.suprah.drivertracker.data.remote.TrackerApiService
import com.suprah.drivertracker.data.settings.DataStoreSettingsRepository
import com.suprah.drivertracker.data.settings.DeviceKeyCipher
import com.suprah.drivertracker.data.settings.KeystoreDeviceKeyCipher
import com.suprah.drivertracker.data.settings.SettingsRepository
import com.suprah.drivertracker.domain.TrackerApi
import com.suprah.drivertracker.permissions.AndroidPhoneSettingsChecker
import com.suprah.drivertracker.permissions.PhoneSettingsChecker
import com.suprah.drivertracker.tracking.FusedLocationSource
import com.suprah.drivertracker.tracking.LocationSource
import com.suprah.drivertracker.tracking.TrackingControl
import com.suprah.drivertracker.tracking.TrackingLauncher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Interceptors that only exist in some builds (for example request logging in debug builds). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BuildInterceptors

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun appConfig(): AppConfig = AppConfig(
        defaultServerUrl = BuildConfig.DEFAULT_SERVER_URL,
        serverEditable = BuildConfig.SERVER_EDITABLE,
        allowHttp = BuildConfig.ALLOW_HTTP_SERVER,
        driverPortalUrl = BuildConfig.DRIVER_PORTAL_URL,
        appVersion = BuildConfig.VERSION_NAME,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(MAX_DEVICE_NAME),
    )

    private const val MAX_DEVICE_NAME = 80

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
}

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("tracker_settings") }

    @Provides
    @Singleton
    fun positionDatabase(@ApplicationContext context: Context): PositionDatabase =
        Room.databaseBuilder(context, PositionDatabase::class.java, PositionDatabase.NAME).build()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private const val CONNECT_SECONDS = 15L
    private const val READ_WRITE_SECONDS = 20L

    @Provides
    @Singleton
    fun okHttpClient(@BuildInterceptors buildInterceptors: Set<@JvmSuppressWildcards Interceptor>): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_WRITE_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_WRITE_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(SlowCallTimeoutInterceptor())
        .apply { buildInterceptors.forEach(::addInterceptor) }
        .build()

    @Provides
    @Singleton
    fun trackerApiService(client: OkHttpClient, json: Json): TrackerApiService = Retrofit.Builder()
        // Every call passes its full URL (the server address can change in dev builds).
        .baseUrl("https://localhost/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json; charset=utf-8".toMediaType()))
        .build()
        .create(TrackerApiService::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun clock(impl: SystemClock): Clock

    @Binds
    abstract fun deviceKeyCipher(impl: KeystoreDeviceKeyCipher): DeviceKeyCipher

    @Binds
    @Singleton
    abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun positionStore(impl: RoomPositionStore): PositionStore

    @Binds
    @Singleton
    abstract fun trackerApi(impl: RetrofitTrackerApi): TrackerApi

    @Binds
    abstract fun locationSource(impl: FusedLocationSource): LocationSource

    @Binds
    abstract fun phoneSettingsChecker(impl: AndroidPhoneSettingsChecker): PhoneSettingsChecker

    @Binds
    abstract fun trackingControl(impl: TrackingLauncher): TrackingControl
}
