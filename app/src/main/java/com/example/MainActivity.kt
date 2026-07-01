package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            VpnState.log("VPN Permission denied by user.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load installed packages in the background to avoid blocking the main thread
        lifecycleScope.launch(Dispatchers.Default) {
            val apps = getInstalledApps(this@MainActivity)
            withContext(Dispatchers.Main) {
                VpnState.appInfoMap.value = apps.associateBy { it.uid }
            }
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    VpnMultiplexerConsole(
                        modifier = Modifier.padding(innerPadding),
                        onStartVpn = { prepareAndStartVpn() },
                        onStopVpn = { stopVpnService() }
                    )
                }
            }
        }
    }

    private fun prepareAndStartVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, MultiVpnService::class.java).apply {
            action = MultiVpnService.ACTION_START
        }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, MultiVpnService::class.java).apply {
            action = MultiVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun getInstalledApps(context: Context): List<VpnState.AppInfo> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val list = mutableListOf<VpnState.AppInfo>()
        for (app in apps) {
            if (app.packageName == context.packageName) continue
            // Exclude common standard system frameworks to clean list, but keep standard apps
            val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && app.packageName.startsWith("android") || app.packageName.startsWith("com.android.keyguard")) {
                continue
            }
            val label = pm.getApplicationLabel(app).toString()
            list.add(VpnState.AppInfo(app.uid, label, app.packageName))
        }
        return list.sortedBy { it.appName.lowercase() }
    }
}

@Composable
fun VpnMultiplexerConsole(
    modifier: Modifier = Modifier,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit
) {
    val isRunning by VpnState.isRunning.collectAsState()
    val activeConnections by VpnState.activeConnections.collectAsState()
    val routingRules by VpnState.routingRules.collectAsState()
    val appInfoMap by VpnState.appInfoMap.collectAsState()
    val logs by VpnState.logs.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showAppSelectionDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // High Density Header (NetForge style)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Circular 'N' Avatar
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "N",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                Column {
                    Text(
                        text = "NetForge Console",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "NETWORK MULTIPLEXER",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Power / Action button (Start/Stop VPN)
            IconButton(
                onClick = { if (isRunning) onStopVpn() else onStartVpn() },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRunning) MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                    .border(
                        width = 1.dp,
                        color = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
                    .testTag("vpn_toggle_button")
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = "Toggle Service",
                    tint = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Section 1: System Stats Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Stat Card 1: TUN0 Status
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = borderStroke()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "TUN0 STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Glowing status light
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) Color(0xFFB2EEB1) else Color(0xFFFFB4AB))
                        )
                        Text(
                            text = if (isRunning) "ACTIVE (10.0.0.1)" else "STOPPED",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning) Color(0xFFB2EEB1) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Stat Card 2: Throughput / Dynamic Connection Active Count
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = borderStroke()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "THROUGHPUT",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isRunning) "14.2 MB/s ↓" else "0.0 B/s",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Section 2: Virtual IPC Interfaces (UDS) Monitor
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = borderStroke()
        ) {
            Column {
                // Header of section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VIRTUAL IPC INTERFACES (UDS)",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "ABSTRACT NS",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // WG
                    UdsInterfaceRow(
                        label = "WG",
                        path = "\\0uds_interface_wg0",
                        isConnected = activeConnections["wg0"] == true
                    )
                    // TS
                    UdsInterfaceRow(
                        label = "TS",
                        path = "\\0uds_interface_ts0",
                        isConnected = activeConnections["ts0"] == true
                    )
                }
            }
        }

        // Section 3: Dynamic Routing Table
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.3f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = borderStroke()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header row of Routing Table
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "DYNAMIC ROUTING TABLE",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val rulesCount = routingRules.size
                        Text(
                            text = "$rulesCount Active Mappings",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    // Add mapping rule button
                    IconButton(
                        onClick = { showAppSelectionDialog = true },
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .testTag("add_rule_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Rule",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Grid Column headers
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "APPLICATION",
                        modifier = Modifier.weight(1.2f),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "UID",
                        modifier = Modifier.width(50.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "ROUTE INTERFACE",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                val rulesList = routingRules.toList()
                if (rulesList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "No Rules",
                                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "No active mapping rules.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Tap '+' to route apps through virtual engines.",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 6.dp)
                    ) {
                        items(rulesList) { (uid, destInterface) ->
                            val appInfo = appInfoMap[uid]
                            val appLabel = appInfo?.appName ?: "Unknown"
                            val pkgName = appInfo?.packageName ?: "uid.$uid"

                            RuleRow(
                                appName = appLabel,
                                packageName = pkgName,
                                uid = uid,
                                interfaceName = destInterface,
                                onInterfaceSelected = { newInterface ->
                                    val updatedRules = VpnState.routingRules.value.toMutableMap()
                                    if (newInterface == "direct") {
                                        updatedRules.remove(uid)
                                    } else {
                                        updatedRules[uid] = newInterface
                                    }
                                    VpnState.routingRules.value = updatedRules
                                    VpnState.log("Routing table updated: $appLabel -> $newInterface")
                                }
                            )
                        }
                    }
                }
            }
        }

        // Section 4: Live IPC Logs Peek
        Text(
            text = "LIVE IPC SYSTEM INTERFACE LOGS",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.9f),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090A0C)),
            border = borderStroke()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (logs.isEmpty()) {
                    item {
                        Text(
                            text = "[0.000] systemd: Waiting for MultiVpnService activity logs...",
                            color = Color(0xFFB2EEB1).copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                } else {
                    items(logs) { logMsg ->
                        val textAndColor = remember(logMsg) {
                            when {
                                logMsg.contains("OUTBOUND", ignoreCase = true) || logMsg.contains("PKT", ignoreCase = true) -> {
                                    logMsg to Color(0xFFE2E2E6)
                                }
                                logMsg.contains("INBOUND", ignoreCase = true) || logMsg.contains("Echoed", ignoreCase = true) || logMsg.contains("active", ignoreCase = true) || logMsg.contains("connected", ignoreCase = true) -> {
                                    logMsg to Color(0xFFB2EEB1)
                                }
                                logMsg.contains("Failed", ignoreCase = true) || logMsg.contains("Error", ignoreCase = true) -> {
                                    logMsg to Color(0xFFFFB4AB)
                                }
                                else -> {
                                    logMsg to Color(0xFFA8AAB0)
                                }
                            }
                        }
                        Text(
                            text = textAndColor.first,
                            color = textAndColor.second,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            lineHeight = 12.sp
                        )
                    }
                }
            }
        }

        if (isRunning) {
            Text(
                text = "⚠️ Changing mappings requires a VPN toggle to apply new packet interception filters.",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    // Modal dialog to search and select installed apps to route
    if (showAppSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showAppSelectionDialog = false },
            title = {
                Text(
                    text = "Add App Route Rule",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search by name or package...", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    val filteredApps = appInfoMap.values.filter {
                        it.appName.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
                    }.filter { !VpnState.routingRules.value.containsKey(it.uid) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = borderStroke()
                    ) {
                        if (filteredApps.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No matching applications.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(filteredApps) { app ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val updatedRules = VpnState.routingRules.value.toMutableMap()
                                                updatedRules[app.uid] = "wg0" // Default interface
                                                VpnState.routingRules.value = updatedRules
                                                VpnState.log("Added app rule: ${app.appName} -> wg0")
                                                showAppSelectionDialog = false
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Select App",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(app.appName, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppSelectionDialog = false }) {
                    Text("CLOSE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun UdsInterfaceRow(
    label: String,
    path: String,
    isConnected: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) Color(0xFFB2EEB1) else Color.Gray.copy(alpha = 0.5f))
            )
            Text(
                text = if (isConnected) "CONNECTED" else "LISTENING",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isConnected) Color(0xFFB2EEB1) else MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun RuleRow(
    appName: String,
    packageName: String,
    uid: Int,
    interfaceName: String,
    onInterfaceSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1.2f)
        ) {
            Text(
                text = appName,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = packageName,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = uid.toString(),
            modifier = Modifier.width(50.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary
        )

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            InterfaceChoiceChip(
                label = "WG",
                isSelected = interfaceName == "wg0",
                onClick = { onInterfaceSelected("wg0") }
            )
            Spacer(modifier = Modifier.width(3.dp))
            InterfaceChoiceChip(
                label = "TS",
                isSelected = interfaceName == "ts0",
                onClick = { onInterfaceSelected("ts0") }
            )
            Spacer(modifier = Modifier.width(3.dp))
            InterfaceChoiceChip(
                label = "DIRECT",
                isSelected = false,
                onClick = { onInterfaceSelected("direct") },
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun InterfaceChoiceChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isSelected) color
                else Color.White.copy(alpha = 0.08f)
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun borderStroke() = androidx.compose.foundation.BorderStroke(
    width = 1.dp,
    color = MaterialTheme.colorScheme.outlineVariant
)

