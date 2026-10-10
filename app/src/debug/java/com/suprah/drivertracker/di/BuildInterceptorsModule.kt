package com.suprah.drivertracker.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/** Debug builds: log each request's method, address, status and timing (never the device key). */
@Module
@InstallIn(SingletonComponent::class)
object BuildInterceptorsModule {
    @Provides
    @ElementsIntoSet
    @BuildInterceptors
    fun interceptors(): Set<Interceptor> = setOf(
        HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
        },
    )
}
