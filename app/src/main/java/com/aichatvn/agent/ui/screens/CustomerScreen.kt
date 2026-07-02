package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.CustomerEntity
import com.aichatvn.agent.ui.viewmodels.CustomerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerScreen(
    navController: NavController,
    viewModel: CustomerViewModel = hiltViewModel()
) {
    val customers by viewModel.customers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val result by viewModel.result.collectAsState()

    var showDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CustomerEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(result) {
        result?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearResult()
        }
    }

    if (showDialog) {
        CustomerDialog(
            customer = editTarget,
            isLoading = isLoading,
            onDismiss = { showDialog = false; editTarget = null },
            onSave = { id, name, email, address, note ->
                viewModel.saveCustomer(id, name, email, address, note)
                showDialog = false
                editTarget = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Khách hàng") },
                actions = {
                    IconButton(onClick = { editTarget = null; showDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Thêm khách")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (customers.isEmpty()) {
                Text(
                    text = "Chưa có khách hàng nào. Nhấn + để thêm.",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(customers, key = { it.id }) { customer ->
                        CustomerCard(
                            customer = customer,
                            onEdit = { editTarget = customer; showDialog = true },
                            onDelete = { viewModel.deleteCustomer(customer.id) },
                            onOpenCameras = {
                                navController.navigate("customer_cameras/${customer.id}")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerCard(
    customer: CustomerEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenCameras: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Xoá khách hàng?") },
            text = { Text("Sẽ xoá luôn toàn bộ camera của \"${customer.name}\". Không thể hoàn tác.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("Xoá", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Huỷ") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenCameras
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(customer.name, style = MaterialTheme.typography.titleSmall)
                if (customer.email.isNotBlank()) {
                    Text(
                        customer.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (customer.address.isNotBlank()) {
                    Text(
                        customer.address,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Sửa")
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Xoá",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerDialog(
    customer: CustomerEntity?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSave: (id: String?, name: String, email: String, address: String, note: String) -> Unit
) {
    var id      by remember { mutableStateOf(customer?.id      ?: "") }
    var name    by remember { mutableStateOf(customer?.name    ?: "") }
    var email   by remember { mutableStateOf(customer?.email   ?: "") }
    var address by remember { mutableStateOf(customer?.address ?: "") }
    var note    by remember { mutableStateOf(customer?.note    ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (customer == null) "Thêm khách hàng" else "Sửa khách hàng") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Mã khách hàng *") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = customer == null,
                    singleLine = true
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên khách hàng *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Địa chỉ") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Ghi chú") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(id.trim(), name, email, address, note) },
                enabled = !isLoading && name.isNotBlank() && id.isNotBlank()
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
}