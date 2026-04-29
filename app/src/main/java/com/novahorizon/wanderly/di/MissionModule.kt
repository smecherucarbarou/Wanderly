package com.novahorizon.wanderly.di

import com.novahorizon.wanderly.data.mission.DeterministicMissionQueryProvider
import com.novahorizon.wanderly.data.mission.GeminiMissionCandidateProvider
import com.novahorizon.wanderly.data.mission.GeminiMissionQueryProvider
import com.novahorizon.wanderly.data.mission.GeminiQueryTextClient
import com.novahorizon.wanderly.data.mission.GooglePlacesCandidateFetcher
import com.novahorizon.wanderly.data.mission.MissionCandidateProvider
import com.novahorizon.wanderly.data.mission.MissionPlaceFilter
import com.novahorizon.wanderly.data.mission.MissionPlaceScorer
import com.novahorizon.wanderly.data.mission.MissionPlaceSearchService
import com.novahorizon.wanderly.data.mission.MissionPlaceSelecting
import com.novahorizon.wanderly.data.mission.MissionPlaceSelector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.scopes.ViewModelScoped
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object MissionModule {
    @Provides
    @ViewModelScoped
    fun provideDeterministicMissionQueryProvider(): DeterministicMissionQueryProvider =
        DeterministicMissionQueryProvider()

    @Provides
    @ViewModelScoped
    fun provideGeminiMissionQueryProvider(textClient: GeminiQueryTextClient): GeminiMissionQueryProvider =
        GeminiMissionQueryProvider(textClient)

    @Provides
    @ViewModelScoped
    fun provideMissionCandidateProvider(queryProvider: GeminiMissionQueryProvider): MissionCandidateProvider =
        GeminiMissionCandidateProvider(queryProvider)

    @Provides
    @ViewModelScoped
    fun provideGooglePlacesCandidateFetcher(
        searchService: MissionPlaceSearchService
    ): GooglePlacesCandidateFetcher = GooglePlacesCandidateFetcher(searchService)

    @Provides
    @ViewModelScoped
    fun provideMissionPlaceFilter(): MissionPlaceFilter = MissionPlaceFilter()

    @Provides
    @ViewModelScoped
    fun provideMissionPlaceScorer(): MissionPlaceScorer = MissionPlaceScorer()

    @Provides
    @ViewModelScoped
    fun provideMissionPlaceSelector(
        geminiQueryProvider: GeminiMissionQueryProvider,
        deterministicQueryProvider: DeterministicMissionQueryProvider,
        candidateFetcher: GooglePlacesCandidateFetcher,
        filter: MissionPlaceFilter,
        scorer: MissionPlaceScorer
    ): MissionPlaceSelecting = MissionPlaceSelector(
        geminiQueryProvider = geminiQueryProvider,
        deterministicQueryProvider = deterministicQueryProvider,
        candidateFetcher = candidateFetcher,
        filter = filter,
        scorer = scorer
    )
}
