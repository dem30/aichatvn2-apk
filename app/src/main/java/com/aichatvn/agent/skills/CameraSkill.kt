package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.skills.base.BaseAgentSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.tools.camera.ImageHashTool
import com.aichatvn.agent.tools.camera.SnapshotFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshotFetcher: SnapshotFetcher,
    private val imageHashTool: ImageHashTool,
    private val groqClient: GroqClientTool,
    private val emailSkill: EmailSkill,
    private val notificationSkill: NotificationSkill
) : BaseAgentSkill {
    
    override val skillName = "CameraSkill"
    
    private val database by lazy { AppDatabase.getDatabase(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cameraMutex = Mutex()
    
    // Learning state per camera
    private data class CameraLearningState(
        var lastPhash: String = "",
        var lastDiff: Int = 0,
        val falseDeltas: MutableList<Int> = mutableListOf(),
        val falseDiffs: MutableList<Int> = mutableListOf(),
        val baselineWindow: MutableList<Int> = mutableListOf(),
        var realEvents: Int = 0,
        var deltaTrigger: Int = 10,
        var absDiffTrigger: Int = 18,
        var cooldownUntil: Long = 0L
    )
    
    private val learningStates = mutableMapOf<String, CameraLearningState>()
    
    private val _diagnostics = MutableStateFlow<Map<String, Any>>(emptyMap())
    val diagnostics: StateFlow<Map<String, Any>> = _diagnostics.asStateFlow()
    
    override suspend fun initialize() {
        // Load existing cameras and initialize states
        val cameras = database.cameraDao().getActiveCameras()
        cameras.forEach { camera ->
            learningStates[camera.id] = CameraLearningState()
        }
        updateDiagnostics()
    }
    
    override suspend fun shutdown() {
        // Save any pending states if needed
    }
    
    suspend fun scanCamera(cameraId: String?, isDailyReport: Boolean): AgentResponse {
        return try {
            val cameras = if (cameraId != null) {
                listOfNotNull(database.cameraDao().getCameraById(cameraId))
            } else {
                database.cameraDao().getActiveCameras()
            }
            
            val results = mutableListOf<Map<String, Any>>()
            
            for (camera in cameras) {
                // Check if customer is active
                val customerSetting = database.cameraDao().getCustomerSetting(camera.customerId)
                if (customerSetting?.isActive != 1) {
                    continue
                }
                
                val result = if (isDailyReport) {
                    scanForDailyReport(camera)
                } else {
                    scanWithLearning(camera, customerSetting?.smartMode == 1)
                }
                results.add(result)
            }
            
            AgentResponse(
                success = true,
                data = mapOf(
                    "processed" to results.size,
                    "results" to results
                )
            )
            
        } catch (e: Exception) {
            AgentResponse(
                success = false,
                error = e.message ?: "Camera scan failed"
            )
        }
    }
    
    private suspend fun scanWithLearning(camera: CameraConfigEntity, isSmartMode: Boolean): Map<String, Any> {
        return cameraMutex.withLock {
            val state = learningStates.getOrPut(camera.id) { CameraLearningState() }
            val now = System.currentTimeMillis()
            
            try {
                // Fetch snapshot
                val imageBytes = snapshotFetcher.fetchSnapshot(camera.snapshoturl)
                if (imageBytes == null) {
                    handleOfflineCamera(camera)
                    return mapOf(
                        "cameraId" to camera.id,
                        "success" to false,
                        "error" to "Cannot fetch snapshot"
                    )
                }
                
                // Calculate phash
                val currentPhash = imageHashTool.calculatePhash(imageBytes)
                val optimizedBytes = imageHashTool.optimizeImage(imageBytes)
                
                // Calculate diff
                var currentDiff = 0
                if (state.lastPhash.isNotEmpty()) {
                    currentDiff = imageHashTool.calculateHammingDistance(state.lastPhash, currentPhash)
                }
                
                // Adaptive threshold logic (from camera_worker.py)
                val delta = kotlin.math.abs(currentDiff - state.lastDiff)
                val deltaTrigger = state.deltaTrigger
                val absDiffTrigger = state.absDiffTrigger
                val driftTrigger = 12
                
                // Calculate baseline drift
                val baselineDiff = if (state.baselineWindow.isNotEmpty()) {
                    state.baselineWindow.average().toInt()
                } else {
                    currentDiff
                }
                val drift = kotlin.math.abs(currentDiff - baselineDiff)
                
                val isSuddenChange = delta >= deltaTrigger || 
                                     currentDiff >= absDiffTrigger || 
                                     drift >= driftTrigger
                
                val isMature = state.baselineWindow.size >= 3
                
                // Determine if we need to call AI
                val shouldCallAi = isSuddenChange && isSmartMode && now >= state.cooldownUntil
                
                var aiComment: String? = null
                var isSuspicious = false
                
                if (shouldCallAi) {
                    val prompt = if (camera.aiPrompt.isNotEmpty()) camera.aiPrompt else DEFAULT_AI_PROMPT
                    aiComment = groqClient.analyzeImage(optimizedBytes, prompt)
                    
                    val positiveKeywords = if (camera.aiPositiveKeywords.isNotEmpty()) {
                        camera.aiPositiveKeywords.split(",").map { it.trim().lowercase() }
                    } else {
                        DEFAULT_POSITIVE_KEYWORDS
                    }
                    val negativeKeywords = if (camera.aiNegativeKeywords.isNotEmpty()) {
                        camera.aiNegativeKeywords.split(",").map { it.trim().lowercase() }
                    } else {
                        DEFAULT_NEGATIVE_KEYWORDS
                    }
                    
                    val textClean = aiComment.lowercase()
                    val hasPositive = positiveKeywords.any { textClean.contains(it) }
                    val hasNegative = negativeKeywords.any { textClean.contains(it) }
                    isSuspicious = hasPositive && !hasNegative
                    
                    if (isSuspicious) {
                        state.realEvents++
                        state.cooldownUntil = now + COOLDOWN_DURATION_MS
                        
                        // Send email alert
                        val customerEmail = camera.customeremail
                        if (customerEmail.isNotEmpty()) {
                            emailSkill.sendEmail(
                                to = customerEmail,
                                subject = "🚨 CẢNH BÁO AN NINH KHẨN CẤP!",
                                body = buildAlertEmailBody(camera, aiComment),
                                imageBytes = optimizedBytes
                            )
                        }
                        
                        // Send notification
                        notificationSkill.sendNotification(
                            title = "Cảnh Báo Camera ${camera.customername}",
                            message = aiComment.take(100)
                        )
                    }
                }
                
                // Update learning state
                if (!shouldCallAi || !isSuspicious) {
                    state.baselineWindow.add(currentDiff)
                    if (state.baselineWindow.size > 20) {
                        state.baselineWindow.removeAt(0)
                    }
                    
                    if (!shouldCallAi && isMature && isSuddenChange) {
                        // Pending reset - monitor next cycle
                    }
                    
                    // Update triggers
                    if (state.falseDeltas.size >= 3) {
                        val recentDeltas = state.falseDeltas.takeLast(30).sorted()
                        val idx = (recentDeltas.size * 0.9).toInt().coerceIn(0, recentDeltas.size - 1)
                        state.deltaTrigger = (recentDeltas[idx] + 2).coerceAtMost(25)
                        
                        val recentDiffs = state.falseDiffs.takeLast(30).sorted()
                        val idxDiff = (recentDiffs.size * 0.9).toInt().coerceIn(0, recentDiffs.size - 1)
                        state.absDiffTrigger = (recentDiffs[idxDiff] + 3).coerceAtMost(35)
                    }
                }
                
                // Update state
                state.lastPhash = currentPhash
                state.lastDiff = currentDiff
                
                // Update camera online status
                if (camera.isOnline != 1) {
                    database.cameraDao().updateCamera(camera.copy(isOnline = 1, status = "online"))
                }
                
                updateDiagnostics()
                
                return mapOf(
                    "cameraId" to camera.id,
                    "success" to true,
                    "hasChange" to isSuddenChange,
                    "isSuspicious" to isSuspicious,
                    "aiComment" to (aiComment ?: "No analysis"),
                    "diff" to currentDiff,
                    "delta" to delta,
                    "drift" to drift,
                    "deltaTrigger" to deltaTrigger,
                    "absDiffTrigger" to absDiffTrigger
                )
                
            } catch (e: Exception) {
                return mapOf(
                    "cameraId" to camera.id,
                    "success" to false,
                    "error" to e.message
                )
            }
        }
    }
    
    private suspend fun scanForDailyReport(camera: CameraConfigEntity): Map<String, Any> {
        return try {
            val imageBytes = snapshotFetcher.fetchSnapshot(camera.snapshoturl)
            if (imageBytes == null) {
                return mapOf(
                    "cameraId" to camera.id,
                    "success" to false,
                    "error" to "Cannot fetch snapshot for daily report"
                )
            }
            
            val optimizedBytes = imageHashTool.optimizeImage(imageBytes)
            val prompt = if (camera.aiPrompt.isNotEmpty()) camera.aiPrompt else DEFAULT_AI_PROMPT
            val aiComment = groqClient.analyzeImage(optimizedBytes, prompt)
            
            mapOf(
                "cameraId" to camera.id,
                "success" to true,
                "aiComment" to aiComment,
                "imageBytes" to optimizedBytes
            )
            
        } catch (e: Exception) {
            mapOf(
                "cameraId" to camera.id,
                "success" to false,
                "error" to e.message
            )
        }
    }
    
    private suspend fun handleOfflineCamera(camera: CameraConfigEntity) {
        if (camera.isOnline != 0) {
            database.cameraDao().updateCamera(camera.copy(isOnline = 0, status = "offline"))
            notificationSkill.sendNotification(
                title = "Camera ${camera.customername} mất kết nối",
                message = "Không thể kết nối đến camera. Vui lòng kiểm tra!"
            )
        }
    }
    
    suspend fun saveCameraConfig(config: Map<String, Any>): AgentResponse {
        return try {
            val camera = CameraConfigEntity(
                id = config["id"] as? String ?: return AgentResponse(success = false, error = "Missing camera id"),
                customerId = config["customerId"] as? String ?: "",
                customername = config["customername"] as? String ?: "",
                customeremail = config["customeremail"] as? String ?: "",
                snapshoturl = config["snapshoturl"] as? String ?: "",
                landinfo = config["landinfo"] as? String,
                timestamp = System.currentTimeMillis(),
                aiPrompt = config["aiPrompt"] as? String ?: "",
                aiPositiveKeywords = config["aiPositiveKeywords"] as? String ?: "",
                aiNegativeKeywords = config["aiNegativeKeywords"] as? String ?: ""
            )
            
            database.cameraDao().insertCamera(camera)
            
            // Ensure customer setting exists
            val existingSetting = database.cameraDao().getCustomerSetting(camera.customerId)
            if (existingSetting == null && camera.customerId.isNotEmpty()) {
                val setting = CustomerSettingEntity(
                    customerId = camera.customerId,
                    smartMode = 0,
                    isActive = 1,
                    updatedAt = System.currentTimeMillis(),
                    timestamp = System.currentTimeMillis()
                )
                database.cameraDao().insertCustomerSetting(setting)
            }
            
            // Initialize learning state for new camera
            if (!learningStates.containsKey(camera.id)) {
                learningStates[camera.id] = CameraLearningState()
            }
            
            AgentResponse(success = true, data = "Camera saved")
            
        } catch (e: Exception) {
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun deleteCamera(cameraId: String): AgentResponse {
        return try {
            database.cameraDao().deleteCamera(cameraId)
            learningStates.remove(cameraId)
            AgentResponse(success = true, data = "Camera deleted")
        } catch (e: Exception) {
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun deleteCustomer(customerId: String): AgentResponse {
        return try {
            database.cameraDao().deleteCamerasByCustomer(customerId)
            database.cameraDao().deleteCustomerSetting(customerId)
            
            // Remove learning states for deleted cameras
            val camerasToRemove = learningStates.keys.filter { it.startsWith(customerId) }
            camerasToRemove.forEach { learningStates.remove(it) }
            
            AgentResponse(success = true, data = "Customer deleted")
        } catch (e: Exception) {
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun setSmartMode(customerId: String, enabled: Boolean): AgentResponse {
        return try {
            database.cameraDao().updateSmartMode(customerId, enabled, System.currentTimeMillis())
            AgentResponse(success = true, data = "Smart mode updated")
        } catch (e: Exception) {
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun setCustomerActive(customerId: String, active: Boolean): AgentResponse {
        return try {
            database.cameraDao().updateActiveStatus(customerId, active, System.currentTimeMillis())
            AgentResponse(success = true, data = "Customer status updated")
        } catch (e: Exception) {
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun getCamerasPaginated(page: Int, pageSize: Int): AgentResponse {
        return try {
            val cameras = database.cameraDao().getActiveCameras()
            val start = (page - 1) * pageSize
            val end = minOf(start + pageSize, cameras.size)
            val paginated = if (start < cameras.size) cameras.subList(start, end) else emptyList()
            
            AgentResponse(
                success = true,
                data = mapOf(
                    "cameras" to paginated,
                    "total" to cameras.size,
                    "page" to page,
                    "pageSize" to pageSize
                )
            )
        } catch (e: Exception) {
            AgentResponse(success = false, error = e.message)
        }
    }
    
    fun getDiagnostics(): Map<String, Any> = _diagnostics.value
    
    private fun updateDiagnostics() {
        scope.launch {
            val stats = learningStates.mapValues { (_, state) ->
                mapOf(
                    "samples" to state.falseDeltas.size,
                    "realEvents" to state.realEvents,
                    "deltaTrigger" to state.deltaTrigger,
                    "absDiffTrigger" to state.absDiffTrigger,
                    "baselineSize" to state.baselineWindow.size,
                    "inCooldown" to (state.cooldownUntil > System.currentTimeMillis())
                )
            }
            _diagnostics.value = stats
        }
    }
    
    private fun buildAlertEmailBody(camera: CameraConfigEntity, analysis: String): String {
        return """
            <html>
            <body style="font-family: Arial, sans-serif; padding: 20px;">
                <h2 style="color: #dc2626;">🚨 CẢNH BÁO AN NINH KHẨN CẤP</h2>
                <p>Hệ thống phát hiện biến động bất thường tại camera <strong>${camera.customername}</strong></p>
                <table style="margin: 15px 0;">
                    <tr><td><strong>Vị trí:</strong></td><td>${camera.landinfo ?: "Không xác định"}</td></tr>
                    <tr><td><strong>Thời gian:</strong></td><td>${java.text.SimpleDateFormat("HH:mm:ss dd/MM/yyyy").format(System.currentTimeMillis())}</td></tr>
                </table>
                <div style="background: #fff3cd; padding: 15px; border-left: 4px solid #ffc107;">
                    <strong>🤖 Phân tích AI:</strong><br>
                    $analysis
                </div>
                <p><small>Hình ảnh bằng chứng được đính kèm email này.</small></p>
            </body>
            </html>
        """.trimIndent()
    }
    
    companion object {
        private const val DEFAULT_AI_PROMPT = "Camera giám sát thửa đất. Hãy xem có người/xe? hoặc xây dựng không. Nếu có ghi: cảnh báo và mô tả. Ngược lại ghi: Bình thường và mô tả."
        private val DEFAULT_POSITIVE_KEYWORDS = listOf("cảnh báo")
        private val DEFAULT_NEGATIVE_KEYWORDS = listOf("bình thường")
        private const val COOLDOWN_DURATION_MS = 3 * 60 * 60 * 1000L // 3 hours
    }
}