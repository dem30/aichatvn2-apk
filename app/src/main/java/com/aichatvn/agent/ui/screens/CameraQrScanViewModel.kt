package com.aichatvn.agent.ui.screens

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.tools.camera.discovery.CameraQrDiscovery
import com.aichatvn.agent.tools.camera.discovery.CameraQrScanResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bọc CameraQrDiscovery (Singleton) thành ViewModel để lấy được qua hiltViewModel() trong
 * Composable — CameraDialog trong CameraComponents.kt là Composable thuần, không có
 * @HiltViewModel riêng, không @Inject trực tiếp được. Cùng pattern với
 * CameraVerificationViewModel trong CameraDiscoveryDialog.kt.
 */
@HiltViewModel
class CameraQrScanViewModel @Inject constructor(
    private val cameraQrDiscovery: CameraQrDiscovery,
) : ViewModel() {
    private val _result = MutableStateFlow<CameraQrScanResult?>(null)
    val result: StateFlow<CameraQrScanResult?> = _result.asStateFlow()

    fun scan(activity: Activity) {
        viewModelScope.launch {
            _result.value = cameraQrDiscovery.scan(activity)
        }
    }

    /** Xoá kết quả sau khi nơi gọi đã xử lý xong (Success/Cancelled/Error) — cho phép quét lại từ đầu. */
    fun consume() {
        _result.value = null
    }
}