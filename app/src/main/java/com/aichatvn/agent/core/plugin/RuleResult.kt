package com.aichatvn.agent.core.plugin

// TODO(v2.0): Replace Map<String, Any> with sealed class ParamValue
// to support nested types: List, Boolean, Long, Double, Nested Object
sealed class RuleResult {
    data class Match(
        val pluginId: String,
        val action: String,
        val params: Map<String, Any>
    ) : RuleResult()
    
    data class NeedMoreInfo(
        val pluginId: String,
        val action: String,
        val params: Map<String, Any>,
        val missingParams: List<String>,
        val question: String
    ) : RuleResult()
    
    object NoMatch : RuleResult()
}