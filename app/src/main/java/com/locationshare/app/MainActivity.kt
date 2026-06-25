package com.locationshare.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.locationshare.app.data.PreferencesManager
import com.locationshare.app.worker.LocationCheckWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled by re-reading permission state in UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        setContent {
            MaterialTheme {
                LocationShareScreen(
                    prefs = prefs,
                    hasLocationPermission = { hasLocationPermission() },
                    requestLocationPermission = {
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    triggerTestLocation = { triggerOneTimeCheck() }
                )
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun triggerOneTimeCheck() {
        // Used by "TEST LOCATION" — runs the same worker immediately instead
        // of waiting for the next scheduled flag check. Still only acts if
        // sharing is ON and a request is actually pending; it does not
        // fabricate a fake request.
        Toast.makeText(this, "Checking for a pending request…", Toast.LENGTH_SHORT).show()
        val request = OneTimeWorkRequestBuilder<LocationCheckWorker>().build()
        WorkManager.getInstance(this).enqueue(request)
    }
}

@Composable
fun LocationShareScreen(
    prefs: PreferencesManager,
    hasLocationPermission: () -> Boolean,
    requestLocationPermission: () -> Unit,
    triggerTestLocation: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isOnboarded by prefs.isOnboarded.collectAsState(initial = true)
    val sharingEnabled by prefs.sharingEnabled.collectAsState(initial = false)
    val lastRequestTime by prefs.lastRequestTime.collectAsState(initial = "Never")
    val lastSharedAddress by prefs.lastSharedAddress.collectAsState(initial = "Not shared yet")

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            MainScreenContent(
                sharingEnabled = sharingEnabled,
                lastRequestTime = lastRequestTime,
                lastSharedAddress = lastSharedAddress,
                onToggleSharing = { enabled ->
                    if (enabled && !hasLocationPermission()) {
                        requestLocationPermission()
                    }
                    scope.launch { prefs.setSharingEnabled(enabled) }
                },
                onTestLocation = triggerTestLocation,
                onRefreshStatus = { /* flows refresh automatically via collectAsState */ }
            )

            if (!isOnboarded) {
                OnboardingDialog(
                    onSubmit = { name, mobile, repo, ghToken, tgToken ->
                        scope.launch {
                            prefs.saveOnboarding(name, mobile, repo, ghToken, tgToken)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreenContent(
    sharingEnabled: Boolean,
    lastRequestTime: String,
    lastSharedAddress: String,
    onToggleSharing: (Boolean) -> Unit,
    onTestLocation: () -> Unit,
    onRefreshStatus: () -> Unit
) {
    val cardColor = if (sharingEnabled) Color(0xFFE6F4EA) else Color(0xFFEFEFEF)
    val statusLabelColor = if (sharingEnabled) Color(0xFF1E8E3E) else Color(0xFF9AA0A6)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text(text = "📍 Location Share", fontSize = 26.sp, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (sharingEnabled) "Share My Location: ON" else "Share My Location: OFF",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Switch(checked = sharingEnabled, onCheckedChange = onToggleSharing)
                }

                Spacer(Modifier.height(20.dp))
                InfoRow(label = "Last Request", value = lastRequestTime)
                Spacer(Modifier.height(8.dp))
                InfoRow(label = "Last Shared Location", value = lastSharedAddress)
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Device Status  ", fontWeight = FontWeight.Medium)
                    Text("🟢", color = statusLabelColor)
                    Text(if (sharingEnabled) " Connected" else " Idle")
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onTestLocation,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("TEST LOCATION", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(14.dp))

        OutlinedButton(
            onClick = onRefreshStatus,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("REFRESH STATUS", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        Text(text = "Version 1.0", fontSize = 13.sp, color = Color(0xFF9AA0A6))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF5F6368))
        Text(text = value, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun OnboardingDialog(
    onSubmit: (name: String, mobile: String, repo: String, ghToken: String, tgToken: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var ghToken by remember { mutableStateOf("") }
    var tgToken by remember { mutableStateOf("") }
    var dismissed by remember { mutableStateOf(false) }

    if (dismissed) return

    AlertDialog(
        onDismissRequest = { /* setup must be completed */ },
        title = { Text("Welcome to Location Share") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "One-time setup. Everything you enter stays on this device.",
                    fontSize = 13.sp,
                    color = Color(0xFF5F6368)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Your Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = mobile, onValueChange = { mobile = it }, label = { Text("Mobile Number") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("GitHub Repo (owner/repo)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = ghToken, onValueChange = { ghToken = it }, label = { Text("GitHub PAT") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = tgToken, onValueChange = { tgToken = it }, label = { Text("Telegram Bot Token") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && mobile.isNotBlank() && repo.isNotBlank() && ghToken.isNotBlank() && tgToken.isNotBlank()) {
                    onSubmit(name, mobile, repo, ghToken, tgToken)
                    dismissed = true
                }
            }) { Text("Save") }
        }
    )
}
