package com.example.hearingaidstreamer.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hearingaidstreamer.permissions.missingPermissions
import com.example.hearingaidstreamer.telecom.CallLifecycleState
import com.example.hearingaidstreamer.telecom.CallRouteFormatter
import com.example.hearingaidstreamer.telecom.readableStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val context = LocalContext.current
                    val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(context))
                    val callState by viewModel.callState.collectAsStateWithLifecycleCompat()
                    val endpoints by viewModel.availableEndpoints.collectAsStateWithLifecycleCompat()
                    val activeEndpoint by viewModel.activeEndpoint.collectAsStateWithLifecycleCompat()

                    val requiredPermissions = remember {
                        buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                add(Manifest.permission.BLUETOOTH_CONNECT)
                                add(Manifest.permission.BLUETOOTH_SCAN)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                add(Manifest.permission.MANAGE_OWN_CALLS)
                            }
                        }
                    }

                    var missing by rememberSaveable { mutableStateOf(missingPermissions(context, requiredPermissions)) }

                    LaunchedEffect(requiredPermissions) {
                        missing = missingPermissions(context, requiredPermissions)
                    }

                    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { result ->
                        val stillMissing = missingPermissions(context, requiredPermissions)
                        missing = stillMissing
                        val granted = result.values.all { it }
                        if (granted && stillMissing.isEmpty()) {
                            viewModel.startCall()
                        }
                    }

                    MainScreen(
                        state = callState,
                        endpoints = endpoints,
                        activeEndpoint = activeEndpoint,
                        missingPermissions = missing,
                        onRequestPermissions = {
                            permissionLauncher.launch(requiredPermissions.toTypedArray())
                        },
                        onStart = { viewModel.startCall() },
                        onStop = { viewModel.stopCall() },
                        onSelectEndpoint = { endpoint -> viewModel.requestEndpoint(endpoint) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    state: CallLifecycleState,
    endpoints: List<android.telecom.CallEndpoint>,
    activeEndpoint: android.telecom.CallEndpoint?,
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelectEndpoint: (android.telecom.CallEndpoint) -> Unit
) {
    val context = LocalContext.current
    val isActive = state is CallLifecycleState.Active
    val isConnecting = state is CallLifecycleState.Connecting

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = state.readableStatus(context),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            when (state) {
                is CallLifecycleState.Connecting -> {
                    CircularProgressIndicator()
                }

                is CallLifecycleState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> Unit
            }

            if (missingPermissions.isNotEmpty()) {
                Card(modifier = Modifier.padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = context.getString(com.example.hearingaidstreamer.R.string.microphone_permission_rationale),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = onRequestPermissions) {
                            Text(text = context.getString(com.example.hearingaidstreamer.R.string.grant_permissions))
                        }
                    }
                }
            } else {
                val canStart = state is CallLifecycleState.Idle || state is CallLifecycleState.Error
                Button(
                    onClick = onStart,
                    enabled = canStart && missingPermissions.isEmpty()
                ) {
                    Text(text = context.getString(com.example.hearingaidstreamer.R.string.start_stream))
                }
                val canStop = state is CallLifecycleState.Active || state is CallLifecycleState.Connecting || state is CallLifecycleState.Ending
                Button(
                    onClick = onStop,
                    enabled = canStop
                ) {
                    Text(text = context.getString(com.example.hearingaidstreamer.R.string.stop_stream))
                }

                if (endpoints.isNotEmpty()) {
                    Text(
                        text = context.getString(com.example.hearingaidstreamer.R.string.request_route),
                        style = MaterialTheme.typography.titleMedium
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(endpoints, key = { it.identifier }) { endpoint ->
                            val selected = activeEndpoint?.identifier == endpoint.identifier
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectEndpoint(endpoint) },
                                label = { Text(CallRouteFormatter.label(context, endpoint)) }
                            )
                        }
                    }

                    activeEndpoint?.let { endpoint ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = context.getString(
                                com.example.hearingaidstreamer.R.string.active_route,
                                CallRouteFormatter.label(context, endpoint)
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateWithLifecycleCompat(): androidx.compose.runtime.State<T> {
    val stateFlow = this
    val initialValue = remember(stateFlow) { stateFlow.value }
    val state = remember { mutableStateOf(initialValue) }
    LaunchedEffect(stateFlow) {
        stateFlow.collect { value -> state.value = value }
    }
    return state
}
