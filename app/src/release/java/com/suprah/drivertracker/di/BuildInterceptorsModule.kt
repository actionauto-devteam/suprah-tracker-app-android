package com.suprah.drivertracker.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import okhttp3.Interceptor

/** Release builds: no request logging. */
@Module
@InstallIn(SingletonComponent::class)
object BuildInterceptorsModule {
    @Provides
    @ElementsIntoSet
    @BuildInterceptors
    fun interceptors(): Set<Interceptor> = emptySet()
}
