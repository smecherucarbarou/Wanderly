package com.novahorizon.wanderly

import android.content.Context
import com.novahorizon.wanderly.data.WanderlyRepository

object WanderlyGraph {
    @Volatile
    private var repository: WanderlyRepository? = null

    fun repository(context: Context): WanderlyRepository {
        return repository ?: synchronized(this) {
            repository ?: WanderlyRepository(context.applicationContext).also { repository = it }
        }
    }
}
