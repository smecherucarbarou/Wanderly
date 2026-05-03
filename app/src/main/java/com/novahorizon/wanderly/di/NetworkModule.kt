package com.novahorizon.wanderly.di

import com.novahorizon.wanderly.data.mission.DefaultGeminiCandidateTextClient
import com.novahorizon.wanderly.data.mission.DefaultGeminiQueryTextClient
import com.novahorizon.wanderly.data.mission.GeminiCandidateTextClient
import com.novahorizon.wanderly.data.mission.GeminiQueryTextClient
import com.novahorizon.wanderly.data.mission.GooglePlacesSearchService
import com.novahorizon.wanderly.data.mission.MissionPlaceSearchService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideGeminiQueryTextClient(): GeminiQueryTextClient = DefaultGeminiQueryTextClient

    @Provides
    @Singleton
    fun provideGeminiCandidateTextClient(): GeminiCandidateTextClient = DefaultGeminiCandidateTextClient

    @Provides
    @Singleton
    fun provideMissionPlaceSearchService(): MissionPlaceSearchService = GooglePlacesSearchService()
}
