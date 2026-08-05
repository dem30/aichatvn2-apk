package com.aichatvn.agent.core.router.stages

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.Layer2Result
import com.aichatvn.agent.core.RoutingContext
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.router.RoutingPipeline
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import com.aichatvn.agent.utils.toMap

@Singleton
class Tier2SlotResolverStage @Inject constructor() : RouterStage<Layer2Result?> {

    override suspend fun process(
        context: RoutingContext,
        devicePlugins: List<Plugin>,
        pipeline: RoutingPipeline
    ): Layer2Result? {
        val wrapperIntentPair = context.globalMatchResult.intentMatches
            .find { 
                try {
                    JSONObject(it.first.answer).optString("plugin") == "schedule"
                } catch (_: Exception) {
                    false
                }
            }

        if (wrapperIntentPair == null) {
            // ✅ SỬA: trước đây so khớp trên bản BỎ DẤU (StringSimilarityUtil.normalizeVietnamese)
            // bằng .contains() thô — 2 vấn đề cộng dồn:
            // (1) bỏ dấu khiến các từ khác nghĩa trùng chuỗi (vd "sua" = cả "sửa" và "sữa", "bo" =
            //     cả "bỏ"/"bò"/"bờ", "doi" = cả "đổi"/"đợi"/"đôi");
            // (2) .contains() không có word-boundary nên khớp cả khi từ khoá chỉ là 1 phần bên
            //     trong từ khác không liên quan (vd "bo" là substring của "bỏng").
            // Nay chuyển sang so khớp trên bản GIỮ NGUYÊN dấu (chỉ lowercase) + word-boundary bằng
            // regex, cùng cách tiếp cận đã áp dụng ở Tier 4 / mentionsAppDomain().
            val queryKeepAccent = context.resolvedQuery.lowercase().trim()

            fun containsWord(keyword: String): Boolean =
                Regex("(?<!\\p{L})${Regex.escape(keyword)}(?!\\p{L})").containsMatchIn(queryKeepAccent)

            val hasScheduleTopic = containsWord("lịch") || containsWord("hẹn giờ")
            val manageVerbs = setOf(
                "hủy", "huỷ", "xóa", "xoá", "bỏ", "ngừng", "dừng lịch", "sửa", "đổi", "cập nhật",
                "xem", "liệt kê", "danh sách", "kiểm tra",
                "tắt lịch", "bật lịch", "kích hoạt", "vô hiệu hoá", "vô hiệu hóa"
            )
            val scheduleManageSignal = hasScheduleTopic && manageVerbs.any { containsWord(it) }
            if (scheduleManageSignal) return null
        }

        val bestIntentPair = wrapperIntentPair ?: context.globalMatchResult.intentMatches
            .firstOrNull() ?: return null

        val bestIntentQA = bestIntentPair.first
        val confidence = bestIntentPair.second

        val rootJson = try { JSONObject(bestIntentQA.answer) } catch (e: Exception) { null }
        val rootPluginId = rootJson?.optString("plugin") ?: ""
        val rootActionName = rootJson?.optString("action") ?: ""
        
        if (rootPluginId.isBlank() || rootActionName.isBlank()) return null
        
        val rootPlugin = devicePlugins.find { it.manifest.id == rootPluginId } ?: return null
        val rootAction = rootPlugin.manifest.actions.find { it.name == rootActionName } ?: return null

        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()

        val resolvedParams = pipeline.resolveParametersWithMeta(
            parameters = rootAction.parameters,
            inputParams = rootParams,
            context = context,
            excludeIntentId = bestIntentQA.id,
            depth = 0
        )

        return Layer2Result(rootPlugin, AgentKernel.Intent(rootPluginId, rootActionName, resolvedParams), confidence)
    }



    
}