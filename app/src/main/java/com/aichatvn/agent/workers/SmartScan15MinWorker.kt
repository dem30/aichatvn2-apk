package com.aichatvn.agent.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.skills.CameraSkill
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class SmartScan15MinWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cameraSkill: CameraSkill
) : CoroutineWorker(context, params) {

    private val database by lazy { AppDatabase.getDatabase(applicationContext) }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val cameras = database.cameraDao().getActiveCameras()
                val smartCameras = cameras.filter { camera ->
                    val setting = database.cameraDao().getCustomerSetting(camera.customerId)
                    setting?.smartMode == 1 && setting.isActive == 1
                }
                for (camera in smartCameras) {
                    cameraSkill.scanCamera(camera.id, isDailyReport = false)
                }
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }
}
