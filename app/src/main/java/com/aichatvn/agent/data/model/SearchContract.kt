package com.aichatvn.agent.data.model

enum class QuestionType { YES_NO, QUANTITY, WHAT, WHEN, WHERE, WHY, HOW, OTHER }
enum class AggregationType { COUNT, COMPARE, NONE }

data class SearchContract(
    val questionType: QuestionType = QuestionType.OTHER,
    val sinceMs: Long,
    val untilMs: Long,
    val timeframeLabel: String = "hôm nay",
    val sourceCategory: String? = null, // camera, tuya, chat
    val sourceIdOrName: String? = null,
    val targetObject: String? = null,   // person, car, dog, cat, package...
    val detailsKeywords: List<String> = emptyList(),
    val deviceState: String? = null,     // "true" (bật), "false" (tắt)
    val aggregation: AggregationType = AggregationType.NONE
)