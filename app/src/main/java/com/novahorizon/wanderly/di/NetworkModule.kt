package com.novahorizon.wanderly.di

import android.content.Context
import com.novahorizon.wanderly.data.ai.AiAssistantGateway
import com.novahorizon.wanderly.data.ai.DefaultAiAssistantGateway
import com.novahorizon.wanderly.data.mission.DefaultGeminiCandidateTextClient
import com.novahorizon.wanderly.data.mission.DefaultGeminiQueryTextClient
import com.novahorizon.wanderly.data.mission.GeminiCandidateTextClient
import com.novahorizon.wanderly.data.mission.GeminiQueryTextClient
import com.novahorizon.wanderly.data.mission.GooglePlacesSearchService
import com.novahorizon.wanderly.data.mission.MissionPlaceSearchService
import com.novahorizon.wanderly.ui.guide.AndroidGuideLocationProvider
import com.novahorizon.wanderly.ui.guide.GuideLocationProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideAiAssistantGateway(): AiAssistantGateway = DefaultAiAssistantGateway()

    @Provides
    @Singleton
    fun provideGuideLocationProvider(
        @ApplicationContext context: Context
    ): GuideLocationProvider = AndroidGuideLocationProvider(context)

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
