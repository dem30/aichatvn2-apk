package com.aichatvn.agent.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aichatvn.agent.skills.CameraSkill
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class CameraScheduleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cameraSkill: CameraSkill
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val response = cameraSkill.scanCamera(cameraId = null, isDailyReport = false)
                if (response.success) {
                    Result.success()
                } else {
                    Result.retry()
                }
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }
}