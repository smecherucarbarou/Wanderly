package com.novahorizon.wanderly.di

import com.novahorizon.wanderly.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeminiProxyUrl

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlacesProxyUrl

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {
    @Provides
    @GeminiProxyUrl
    fun provideGeminiProxyUrl(): String = BuildConfig.GEMINI_PROXY_URL

    @Provides
    @PlacesProxyUrl
    fun providePlacesProxyUrl(): String = BuildConfig.PLACES_PROXY_URL
}
