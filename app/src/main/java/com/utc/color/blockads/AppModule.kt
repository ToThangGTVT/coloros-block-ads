package com.utc.color.blockads

import android.content.Context
import androidx.room.Room
import com.utc.color.blockads.core.data.QueryDatabase
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.enginebridge.EngineBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context) = SettingsRepository(context)

    @Provides @Singleton fun engine() = EngineBridge()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): QueryDatabase =
        Room.databaseBuilder(context, QueryDatabase::class.java, "queries.db").build()
}
