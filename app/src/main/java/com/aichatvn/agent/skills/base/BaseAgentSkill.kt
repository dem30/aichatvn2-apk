package com.aichatvn.agent.skills.base

interface BaseAgentSkill {
    val skillName: String
    suspend fun initialize()
    suspend fun shutdown()
}