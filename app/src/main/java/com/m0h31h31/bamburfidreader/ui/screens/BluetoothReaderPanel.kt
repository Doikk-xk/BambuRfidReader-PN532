package com.m0h31h31.bamburfidreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.ui.components.AppSwitch
import com.m0h31h31.bamburfidreader.ui.components.NeuButton
import com.m0h31h31.bamburfidreader.ui.components.NeuPanel

/**
 * 蓝牙 PN532 读卡器设置区块。
 * 仅在"杂项"页展示，开启后全局改用蓝牙读卡源；支持主动扫描周边蓝牙设备。
 */
@Composable
fun BluetoothReaderPanel(
    enabled: Boolean,
    connectedDevice: String,
    statusMessage: String,
    pairedDevices: List<Pair<String, String>>,
    scanning: Boolean = false,
    onScan: () -> Unit = {},
    onEnabledChange: (Boolean) -> Unit,
    onConnectDevice: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    NeuPanel(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.bt_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.bt_enable_label))
                    Text(
                        text = stringResource(R.string.bt_enable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AppSwitch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            if (enabled) {
                Text(
                    text = stringResource(R.string.bt_status_label) + ": " + statusMessage,
                    style = MaterialTheme.typography.bodySmall
                )
                if (connectedDevice.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.bt_device_label) + ": " + connectedDevice,
                        style = MaterialTheme.typography.bodySmall
                    )
                    NeuButton(
                        text = stringResource(R.string.bt_disconnect),
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    NeuButton(
                        text = if (scanning) {
                            stringResource(R.string.bt_scanning)
                        } else {
                            stringResource(R.string.bt_scan_action)
                        },
                        onClick = onScan,
                        enabled = !scanning,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 只显示 PCR532 读卡器：扫描时找到会直接弹连接框，
                    // 这里保留一个列表作为兜底（弹框被忽略后仍可手动连接）。
                    if (pairedDevices.isEmpty()) {
                        Text(
                            text = if (scanning) {
                                stringResource(R.string.bt_scanning)
                            } else {
                                stringResource(R.string.bt_scan_no_pn532)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.bt_scan_devices),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        pairedDevices.forEach { (address, name) ->
                            NeuButton(
                                text = name.ifBlank { address },
                                onClick = { onConnectDevice(address) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.bt_reading_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
