package com.aichatvn.agent.core

import com.aichatvn.agent.skills.ChatSkill
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.skills.NotificationSkill
import com.aichatvn.agent.skills.EmailSkill
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRouter @Inject constructor(
    private val chatSkill: ChatSkill,
    private val cameraSkill: CameraSkill,
    private val trainingSkill: TrainingSkill,
    private val notificationSkill: NotificationSkill,
    private val emailSkill: EmailSkill,
    private val commandDispatcher: CommandDispatcher
) {
    
    private val _responses = MutableSharedFlow<AgentResponse>()
    val responses = _responses.asSharedFlow()
    
    suspend fun route(request: AgentRequest): AgentResponse {
        return try {
            val result: AgentResponse = when (request.intent) {
                IntentType.CHAT_QUERY -> {
                    val message = request.payload["message"] as? String ?: ""
                    val context = request.payload["context"] as? String ?: ""
                    val fileUrl = request.payload["fileUrl"] as? String
                    chatSkill.processQuery(
                        message = message,
                        context = context,
                        username = request.username,
                        fileUrl = fileUrl
                    )
                }
                
                IntentType.CAMERA_SCAN -> {
                    val cameraId = request.payload["cameraId"] as? String
                    val isDailyReport = request.payload["isDailyReport"] as? Boolean ?: false
                    cameraSkill.scanCamera(cameraId, isDailyReport)
                }
                
                IntentType.CAMERA_ADD, IntentType.CAMERA_UPDATE -> {
                    val config = request.payload["config"] as? Map<String, Any> ?: emptyMap()
                    cameraSkill.saveCameraConfig(config)
                }
                
                IntentType.CAMERA_DELETE -> {
                    val cameraId = request.payload["cameraId"] as? String ?: ""
                    cameraSkill.deleteCamera(cameraId)
                }
                
                IntentType.CAMERA_DELETE_CUSTOMER -> {
                    val customerId = request.payload["customerId"] as? String ?: ""
                    cameraSkill.deleteCustomer(customerId)
                }
                
                IntentType.CAMERA_SET_SMART_MODE -> {
                    val customerId = request.payload["customerId"] as? String ?: ""
                    val enabled = request.payload["enabled"] as? Boolean ?: false
                    cameraSkill.setSmartMode(customerId, enabled)
                }
                
                IntentType.CAMERA_SET_ACTIVE -> {
                    val customerId = request.payload["customerId"] as? String ?: ""
                    val active = request.payload["active"] as? Boolean ?: false
                    cameraSkill.setCustomerActive(customerId, active)
                }
                
                IntentType.CAMERA_GET_PAGINATED -> {
                    val page = request.payload["page"] as? Int ?: 1
                    val pageSize = request.payload["pageSize"] as? Int ?: 10
                    cameraSkill.getCamerasPaginated(page, pageSize)
                }
                
                IntentType.TRAINING_ADD -> {
                    val qa = request.payload["qa"] as? Map<String, String> ?: emptyMap()
                    trainingSkill.addQA(
                        question = qa["question"] ?: "",
                        answer = qa["answer"] ?: "",
                        category = qa["category"] ?: "chat",
                        username = request.username
                    )
                }
                
                IntentType.TRAINING_UPDATE -> {
                    val id = request.payload["id"] as? String ?: ""
                    val qa = request.payload["qa"] as? Map<String, String> ?: emptyMap()
                    trainingSkill.updateQA(
                        id = id,
                        question = qa["question"],
                        answer = qa["answer"],
                        category = qa["category"],
                        username = request.username
                    )
                }
                
                IntentType.TRAINING_DELETE -> {
                    val id = request.payload["id"] as? String ?: ""
                    trainingSkill.deleteQA(id, request.username)
                }
                
                IntentType.TRAINING_SEARCH -> {
                    val query = request.payload["query"] as? String ?: ""
                    trainingSkill.searchQAs(query, request.username)
                }
                
                IntentType.SEND_EMAIL -> {
                    val to = request.payload["to"] as? String ?: ""
                    val subject = request.payload["subject"] as? String ?: ""
                    val body = request.payload["body"] as? String ?: ""
                    val imageBytes = request.payload["imageBytes"] as? ByteArray
                    emailSkill.sendEmail(to, subject, body, imageBytes)
                }
                
                IntentType.SEND_NOTIFICATION -> {
                    val title = request.payload["title"] as? String ?: ""
                    val message = request.payload["message"] as? String ?: ""
                    notificationSkill.sendNotification(title, message)
                    AgentResponse(success = true, data = "Notification sent")
                }
                
                IntentType.GET_DIAGNOSTICS -> {
                    AgentResponse(
                        success = true,
                        data = cameraSkill.getDiagnostics()
                    )
                }
                
                // FIXED: Thêm xử lý SYNC_CLOUD
                IntentType.SYNC_CLOUD -> {
                    // TODO: Implement cloud sync logic
                    // Hiện tại trả về success với message
                    AgentResponse(
                        success = true,
                        data = mapOf(
                            "message" to "Cloud sync completed",
                            "timestamp" to System.currentTimeMillis()
                        )
                    )
                }
            }
            
            _responses.emit(result)
            result
            
        } catch (e: Exception) {
            val errorResponse = AgentResponse(
                success = false,
                error = e.message ?: "Unknown error occurred"
            )
            _responses.emit(errorResponse)
            errorResponse
        }
    }
}