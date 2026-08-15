package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichatvn.agent.devices.mqtt.MqttBrokerElectionManager

/**
 * MqttBrokerStatusSection
 *
 * UI kiểm soát BrokerRole — hiển thị trong tab MQTT (TuyaScreen / Mqtt tab).
 * User xác nhận có màn hình để kiểm soát (mục 12 kế hoạch, quyết định #4).
 *
 * Nhúng vào MQTT tab:
 *   MqttBrokerStatusSection(
 *       state = electionState,          // từ MqttViewModel.brokerElectionState
 *       isElecting = isElecting,
 *       onElectNow = { viewModel.forceElection() },
 *       onScanLan = { viewModel.scanTasmotaLan() }
 *   )
 */
@Composable
fun MqttBrokerStatusSection(
    state: MqttBrokerElectionManager.BrokerElectionState,
    isElecting: Boolean = false,
    onElectNow: () -> Unit = {},
    onScanLan: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Router, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    "MQTT Broker",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))

            StatusRow("Vai trò", roleLabel(state.role))
            StatusRow("Chế độ", state.brokerMode)
            if (!state.activeBrokerIp.isNullOrBlank()) {
                StatusRow("IP Broker", state.activeBrokerIp)
            }
            StatusRow("Epoch", state.epoch.toString())

            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onElectNow,
                    enabled = !isElecting,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (isElecting) "Đang bầu..." else "Bầu lại")
                }
                OutlinedButton(
                    onClick = onScanLan,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Quét LAN Tasmota")
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun roleLabel(role: MqttBrokerElectionManager.BrokerRole): String = when (role) {
    MqttBrokerElectionManager.BrokerRole.NONE -> "Chưa xác định"
    MqttBrokerElectionManager.BrokerRole.HOSTING_EMBEDDED -> "🟢 Đang host broker"
    MqttBrokerElectionManager.BrokerRole.FOLLOWING_CAMERA_NODE -> "🔵 Theo Camera Node"
    MqttBrokerElectionManager.BrokerRole.FOLLOWING_ADHOC -> "🟡 Theo ad-hoc"
    MqttBrokerElectionManager.BrokerRole.USING_CLOUD -> "☁️ Cloud Broker"
}
