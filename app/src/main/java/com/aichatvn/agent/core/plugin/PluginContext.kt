package com.aichatvn.agent.core.plugin

import android.content.Context
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginContext @Inject constructor(
    @ApplicationContext val appContext: Context,
    val database: AppDatabase,
    val groqClient: GroqClientTool,
    val eventBus: PluginEventBus,
    val logger: Logger
)