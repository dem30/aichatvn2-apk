package com.aichatvn.agent.core.router.stages

import com.aichatvn.agent.core.*
import com.aichatvn.agent.core.AgentKernel.Intent
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.router.RoutingPipeline
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import com.aichatvn.agent.utils.toMap
import com.aichatvn.agent.data.model.QAEntity

@Singleton
class Tier3ClauseSpotterStage @Inject constructor(
    private val trainingSkill: TrainingSkill,
    private val logger: Logger
) : RouterStage<Layer3Result> {

    companion object {
        // Số từ chêm (filler) tối đa cho phép giữa 2 token liên tiếp của 1 cụm đã train.
        // 2 là đủ để chịu "luôn", "giúp tôi", "cho tôi", "hộ mình"... mà không khớp nhầm
        // 2 cụm nằm cách xa nhau vô nghĩa trong cùng 1 clause (vd "bật quạt rồi tắt đèn"
        // KHÔNG được khớp "bật đèn" dù cả "bật" và "đèn" đều xuất hiện trong câu).
        internal const val MAX_FILLER_WORDS_BETWEEN_TOKENS = 2
        private val TOKEN_SPLIT_REGEX = Regex("\\s+")
    }

    internal data class TokenMatchResult(val start: Int, val end: Int, val matchedLength: Int)

    // ✅ ĐÃ SỬA: Thay so khớp substring liên tục (tempClause.contains(qNorm)) bằng so khớp
    // THEO THỨ TỰ TOKEN, cho phép chêm từ ở giữa (tối đa MAX_FILLER_WORDS_BETWEEN_TOKENS từ).
    //
    // LÝ DO: câu ghép nhiều mệnh đề (bị RoutingPipeline tách bởi dấu ",", ";", "và",
    // "đồng thời", "sau đó", "rồi") thường không đạt confidence 0.80 ở Tầng 2 vì điểm fuzzy
    // bị pha loãng bởi toàn bộ câu dài -> rơi xuống Tầng 3. Ở Tầng 3 cụ thể, cụm đã train
    // (vd "bật đèn") được dò bằng substring LIÊN TỤC — chỉ cần 1 từ chêm ("bật LUÔN đèn")
    // là substring vỡ ngay, matchedIntents rỗng cho clause đó -> clause bị loại bỏ ÂM THẦM,
    // không log lỗi, không action nào được thực thi. Bug này KHÔNG xuất hiện khi câu đơn lẻ
    // (vì Tầng 2 xử lý bằng fuzzy match tolerant, không quan tâm từ chêm) — chỉ lộ ra khi câu
    // bị ghép nhiều mệnh đề VÀ mệnh đề đó chứa từ chêm.
    //
    // internal (không phải private) để JVM unit test trong cùng module gọi trực tiếp được,
    // không cần dựng toàn bộ Hilt graph / Android instrumentation.
    internal fun findTokenOrderMatch(clause: String, trainedPhrase: String): TokenMatchResult? {
        val trainedTokens = trainedPhrase.split(TOKEN_SPLIT_REGEX).filter { it.isNotBlank() }
        if (trainedTokens.isEmpty()) return null

        val clauseTokens = clause.split(TOKEN_SPLIT_REGEX).filter { it.isNotBlank() }
        if (clauseTokens.isEmpty()) return null

        // Offset ký tự (start, endExclusive) của từng token trong chuỗi clause gốc,
        // cần để cắt đúng span khi "nuốt" (thay bằng space) khỏi tempClause sau khi khớp.
        val offsets = ArrayList<Pair<Int, Int>>(clauseTokens.size)
        var cursor = 0
        for (tok in clauseTokens) {
            val idx = clause.indexOf(tok, cursor)
            if (idx < 0) return null // không nên xảy ra, nhưng an toàn thì bail
            offsets.add(idx to idx + tok.length)
            cursor = idx + tok.length
        }

        for (startIdx in clauseTokens.indices) {
            if (clauseTokens[startIdx] != trainedTokens[0]) continue

            var ti = 1
            var ci = startIdx + 1
            var lastMatchedIdx = startIdx
            var ok = true

            while (ti < trainedTokens.size) {
                var filler = 0
                var found = -1
                while (ci < clauseTokens.size && filler <= MAX_FILLER_WORDS_BETWEEN_TOKENS) {
                    if (clauseTokens[ci] == trainedTokens[ti]) { found = ci; break }
                    ci++; filler++
                }
                if (found == -1) { ok = false; break }
                lastMatchedIdx = found
                ci = found + 1
                ti++
            }

            if (ok) {
                val start = offsets[startIdx].first
                val end = offsets[lastMatchedIdx].second
                return TokenMatchResult(start, end, end - start)
            }
        }
        return null
    }

    // ✅ ĐÃ SỬA: Lọc bỏ alias không liên quan tới action đã khớp trước khi quyết định nhánh xử lý
    // (xem chi tiết lý do ở resolveRelevantAliases bên dưới) — tránh clause bị bỏ qua oan khi có
    // alias khác chủ đề (vd category "camera") vô tình trùng substring trong cùng câu lệnh đèn/quạt.
    private fun relevantSemanticTypesForIntents(
        matchedIntents: List<QAEntity>,
        devicePlugins: List<Plugin>
    ): Set<String> {
        val types = mutableSetOf<String>()
        matchedIntents.forEach { qa ->
            val json = try { JSONObject(qa.answer) } catch (e: Exception) { null }
            val pluginId = json?.optString("plugin") ?: ""
            val actionName = json?.optString("action") ?: ""
            val targetPlugin = devicePlugins.find { it.manifest.id == pluginId }
            val targetAction = targetPlugin?.manifest?.actions?.find { it.name == actionName }
            targetAction?.parameters?.forEach { types.add(it.semanticType) }
        }
        return types
    }

    override suspend fun process(
        context: RoutingContext,
        devicePlugins: List<Plugin>,
        pipeline: RoutingPipeline
    ): Layer3Result {
        val intentQAs = trainingSkill.getRawCachedQAList(context.username)
            .filter { it.type == "intent" }
            .sortedByDescending { it.question.length }

        val resolvedIntents = mutableListOf<Pair<Plugin, Intent>>()

        for (clause in context.clauses) {
            val clauseNorm = StringSimilarityUtil.normalizeVietnamese(clause)

            val matchedAliases = context.globalMatchResult.aliasMatches.filter {
                val aliasNorm = StringSimilarityUtil.normalizeVietnamese(it.first.question)
                clauseNorm.contains(aliasNorm)
            }

            val matchedIntents = mutableListOf<QAEntity>()
            // ✅ MỚI: track độ dài span THỰC TẾ đã khớp (kể cả filler đã "nuốt") song song với
            // matchedIntents, để coverageRatio không bị tính sai khi span thực tế dài hơn
            // qa.question.length (trước đây dùng matchedIntents.sumOf { it.question.length },
            // giờ phải dùng độ dài span thật vì có thể chứa thêm từ chêm ở giữa).
            val matchedIntentSpanLengths = mutableListOf<Int>()

            var tempClause = clauseNorm
            val sortedIntents = intentQAs.sortedByDescending { it.question.length }
            for (qa in sortedIntents) {
                val qNorm = StringSimilarityUtil.normalizeVietnamese(qa.question)
                if (qNorm.isBlank()) continue

                val match = findTokenOrderMatch(tempClause, qNorm)
                if (match != null) {
                    matchedIntents.add(qa)
                    matchedIntentSpanLengths.add(match.matchedLength)
                    // "Nuốt" đúng khoảng đã khớp (bao gồm filler ở giữa) bằng khoảng trắng,
                    // để tránh 1 token bị tái sử dụng cho intent khác trong cùng clause.
                    // Dùng space thay vì xóa hẳn để offset của các token còn lại không đổi.
                    tempClause = tempClause.substring(0, match.start) +
                        " ".repeat(match.end - match.start) +
                        tempClause.substring(match.end)
                }
            }

            // ✅ ĐÃ SỬA: dùng span thực tế (matchedIntentSpanLengths) thay vì qa.question.length
            // cho phần intent, vì span có thể dài hơn cụm gốc do chứa từ chêm ở giữa.
            val totalMatchedLength = matchedIntentSpanLengths.sum() +
                                     matchedAliases.sumOf { it.first.question.length }

            if (clause.isEmpty()) continue
            val coverageRatio = totalMatchedLength.toDouble() / clause.length

            if (coverageRatio < 0.70) {
                logger.d("RoutingPipeline", "[ClauseSpotter] ⚠️ Tỷ lệ bao phủ mệnh đề '$clause' quá thấp (${String.format("%.2f", coverageRatio)} < 0.70). Bỏ qua Tầng 3.")
                continue
            }

            val relevantSemanticTypes = relevantSemanticTypesForIntents(matchedIntents, devicePlugins)
            val relevantAliases = matchedAliases.filter { it.first.category in relevantSemanticTypes }

            val uniqueTypes = relevantAliases.map { it.first.category }.distinct()
            val hasDuplicateTypes = relevantAliases.size != uniqueTypes.size

            // ✅ SCHEDULE-WRAPPER GUARD: Nếu clause vừa khớp intent "schedule" (lên lịch) vừa khớp
            // intent hành động thiết bị khác (vd "bật đèn"), thì "schedule" không phải là 1 ý định
            // độc lập ngang hàng — nó là ý định BAO TRÙM (wrapper). Hành động thiết bị đi kèm chỉ mô
            // tả NỘI DUNG của lịch, không phải lệnh cần thực thi ngay. Nếu để lọt vào isMultiIntent,
            // Layer3Result.Multi sẽ thực thi thật CẢ HAI song song -> đèn bị bật ngay dù người dùng
            // chỉ muốn lên lịch. Do đó phải tách case này ra ưu tiên trước, giống cơ chế wrapperIntentPair
            // đã áp dụng ở Tier2SlotResolverStage.
            val scheduleIntentQA = matchedIntents.firstOrNull { qa ->
                val json = try { JSONObject(qa.answer) } catch (e: Exception) { null }
                json?.optString("plugin") == "schedule"
            }
            val isScheduleWrapperCase = scheduleIntentQA != null && matchedIntents.size > 1

            val isIntentDouble = matchedIntents.size == 1 && relevantAliases.size > 1 && uniqueTypes.size == 1
            val isMultiIntent = matchedIntents.size > 1 && !hasDuplicateTypes && !isScheduleWrapperCase

            context.traces.add(TraceNode(
                nodeId = "clause.branchSelect",
                label = "Chọn nhánh xử lý mệnh đề (Clause Spotter)",
                input = "Clause: '$clause' | intents=${matchedIntents.size}, aliases=${matchedAliases.size} (liên quan=${relevantAliases.size}), coverage=${String.format("%.2f", coverageRatio)}",
                output = when {
                    isScheduleWrapperCase -> "isScheduleWrapperCase: intent 'schedule' bao trùm, bỏ qua ${matchedIntents.size - 1} intent hành động thiết bị đi kèm (không thực thi song song)"
                    isIntentDouble -> "isIntentDouble: 1 ý định + nhiều alias cùng category '${uniqueTypes.firstOrNull()}'"
                    isMultiIntent -> "isMultiIntent: ${matchedIntents.size} ý định độc lập, không trùng category"
                    matchedIntents.size == 1 && relevantAliases.size <= 1 -> "Đơn lệnh chuẩn"
                    else -> "Không khớp nhánh nào, bỏ qua clause"
                },
                matched = isScheduleWrapperCase || isIntentDouble || isMultiIntent || (matchedIntents.size == 1 && relevantAliases.size <= 1),
                codeRef = CodeReference(
                    fileName = "AgentKernel.kt",
                    functionName = "processLayer3ClauseEntitySpotter",
                    hardcodedRules = "coverageRatio >= 0.70 (ngưỡng bao phủ mệnh đề), isIntentDouble = 1 intent + >1 alias LIÊN QUAN cùng category, isMultiIntent = >1 intent khác category. Alias có category không thuộc semanticType của bất kỳ tham số nào trong action đã khớp sẽ bị loại trước khi xét (vd alias 'camera' khi action đang xét không có tham số kiểu camera). Cụm intent được dò bằng token-order match, cho phép tối đa $MAX_FILLER_WORDS_BETWEEN_TOKENS từ chêm giữa 2 token liên tiếp của cụm đã train (vd 'bật LUÔN đèn' khớp mẫu 'bật đèn').",
                    businessLogic = "Tách câu thành các mệnh đề rồi phân loại: 1 ý định lặp nhiều thiết bị cùng loại (isIntentDouble), nhiều ý định độc lập song song (isMultiIntent), hoặc đơn lệnh."
                )
            ))

            when {
                isScheduleWrapperCase -> {
                    val qa = scheduleIntentQA!!
                    val rootJson = try { JSONObject(qa.answer) } catch (e: Exception) { null }
                    val rootPluginId = rootJson?.optString("plugin") ?: ""
                    val rootActionName = rootJson?.optString("action") ?: ""
                    val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                    val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }

                    if (targetPlugin != null && targetAction != null) {
                        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                        val resolvedParams = pipeline.resolveParametersWithMeta(
                            parameters = targetAction.parameters,
                            inputParams = rootParams,
                            context = context,
                            excludeIntentId = qa.id,
                            depth = 0
                        )
                        // ⚠️ CHỦ Ý: KHÔNG lặp qua các matchedIntents khác (vd "bật đèn") để executeIntent
                        // riêng — chúng chỉ mô tả nội dung của lịch, không phải lệnh thực thi ngay.
                        resolvedIntents.add(targetPlugin to Intent(rootPluginId, rootActionName, resolvedParams))
                    }
                }

                isIntentDouble -> {
                    val singleIntentQA = matchedIntents.first()
                    val rootJson = try { JSONObject(singleIntentQA.answer) } catch (e: Exception) { null }
                    val rootPluginId = rootJson?.optString("plugin") ?: ""
                    val rootActionName = rootJson?.optString("action") ?: ""

                    val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                    val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }

                    if (targetPlugin != null && targetAction != null) {
                        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()

                        for (alias in relevantAliases) {
                            val resolvedParams = pipeline.resolveParametersWithMeta(
                                parameters = targetAction.parameters,
                                inputParams = rootParams,
                                context = context.copy(globalMatchResult = pipeline.matchResultCopyForSingleAlias(context.globalMatchResult, alias.first)),
                                excludeIntentId = singleIntentQA.id,
                                depth = 0
                            )
                            resolvedIntents.add(targetPlugin to Intent(rootPluginId, rootActionName, resolvedParams))
                        }
                    }
                }

                isMultiIntent -> {
                    for (qa in matchedIntents) {
                        val rootJson = try { JSONObject(qa.answer) } catch (e: Exception) { null }
                        val rootPluginId = rootJson?.optString("plugin") ?: ""
                        val rootActionName = rootJson?.optString("action") ?: ""
                        val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                        val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }

                        if (targetPlugin != null && targetAction != null) {
                            val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                            val resolvedParams = pipeline.resolveParametersWithMeta(
                                parameters = targetAction.parameters,
                                inputParams = rootParams,
                                context = context,
                                excludeIntentId = qa.id,
                                depth = 0
                            )
                            resolvedIntents.add(targetPlugin to Intent(rootPluginId, rootActionName, resolvedParams))
                        }
                    }
                }

                matchedIntents.size == 1 && relevantAliases.size <= 1 -> {
                    val qa = matchedIntents.first()
                    val rootJson = try { JSONObject(qa.answer) } catch (e: Exception) { null }
                    val rootPluginId = rootJson?.optString("plugin") ?: ""
                    val rootActionName = rootJson?.optString("action") ?: ""
                    val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                    val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }

                    if (targetPlugin != null && targetAction != null) {
                        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                        val resolvedParams = pipeline.resolveParametersWithMeta(
                            parameters = targetAction.parameters,
                            inputParams = rootParams,
                            context = context,
                            excludeIntentId = qa.id,
                            depth = 0
                        )
                        resolvedIntents.add(targetPlugin to Intent(rootPluginId, rootActionName, resolvedParams))
                    }
                }
            }
        }

        return when {
            resolvedIntents.isEmpty() -> Layer3Result.NoMatch
            resolvedIntents.size == 1 -> {
                val (plugin, intent) = resolvedIntents.first()
                Layer3Result.Single(plugin, intent)
            }
            else -> Layer3Result.Multi(resolvedIntents)
        }
    }
}