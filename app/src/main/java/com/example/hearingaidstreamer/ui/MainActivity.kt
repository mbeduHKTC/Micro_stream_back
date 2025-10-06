package com.example.hearingaidstreamer.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hearingaidstreamer.permissions.missingPermissions
import com.example.hearingaidstreamer.telecom.CallLifecycleState
import com.example.hearingaidstreamer.telecom.CallRouteFormatter
import com.example.hearingaidstreamer.telecom.readableStatus
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val context = this@MainActivity
                    val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(context))
                    val callState by viewModel.callState.collectAsStateWithLifecycleCompat()
                    val endpoints by viewModel.availableEndpoints.collectAsStateWithLifecycleCompat()
                    val activeEndpoint by viewModel.activeEndpoint.collectAsStateWithLifecycleCompat()
                    val waveform by viewModel.waveform.collectAsStateWithLifecycleCompat()
                    val gainPosition by viewModel.gainPosition.collectAsStateWithLifecycleCompat()
                    val includeMurmurs by viewModel.includeMurmurs.collectAsStateWithLifecycleCompat()
                    val mainsFrequency by viewModel.mainsFrequency.collectAsStateWithLifecycleCompat()

                    val requiredPermissions = remember {
                        buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                add(Manifest.permission.BLUETOOTH_CONNECT)
                                add(Manifest.permission.BLUETOOTH_SCAN)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
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
                        waveform = waveform,
                        gainPosition = gainPosition,
                        includeMurmurs = includeMurmurs,
                        mainsFrequency = mainsFrequency,
                        endpoints = endpoints,
                        activeEndpoint = activeEndpoint,
                        missingPermissions = missing,
                        onRequestPermissions = {
                            permissionLauncher.launch(requiredPermissions.toTypedArray())
                        },
                        onStart = viewModel::startCall,
                        onStop = viewModel::stopCall,
                        onSelectEndpoint = viewModel::requestEndpoint,
                        onGainChanged = viewModel::onGainPositionChanged,
                        onIncludeMurmursChanged = viewModel::onIncludeMurmursChanged,
                        onMainsFrequencyChanged = viewModel::onMainsFrequencyChanged
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    state: CallLifecycleState,
    waveform: List<Float>,
    gainPosition: Float,
    includeMurmurs: Boolean,
    mainsFrequency: Int,
    endpoints: List<android.telecom.CallEndpoint>,
    activeEndpoint: android.telecom.CallEndpoint?,
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelectEndpoint: (android.telecom.CallEndpoint) -> Unit,
    onGainChanged: (Float) -> Unit,
    onIncludeMurmursChanged: (Boolean) -> Unit,
    onMainsFrequencyChanged: (Int) -> Unit
) {
    val context = LocalContext.current
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
                is CallLifecycleState.Connecting -> CircularProgressIndicator()
                is CallLifecycleState.Error -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                else -> Unit
            }

            WaveformCard(samples = waveform, modifier = Modifier.fillMaxWidth().height(160.dp))

            GainSlider(gainPosition = gainPosition, onGainChanged = onGainChanged)

            MainsAndMurmurControls(
                includeMurmurs = includeMurmurs,
                mainsFrequency = mainsFrequency,
                onIncludeMurmursChanged = onIncludeMurmursChanged,
                onMainsFrequencyChanged = onMainsFrequencyChanged
            )

            if (missingPermissions.isNotEmpty()) {
                PermissionCard(missingPermissions = missingPermissions, onRequestPermissions = onRequestPermissions)
            } else {
                val canStart = state is CallLifecycleState.Idle || state is CallLifecycleState.Error
                Button(onClick = onStart, enabled = canStart) {
                    Text(text = context.getString(com.example.hearingaidstreamer.R.string.start_stream))
                }

                val canStop = state is CallLifecycleState.Active || state is CallLifecycleState.Connecting || state is CallLifecycleState.Ending
                Button(onClick = onStop, enabled = canStop) {
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
private fun WaveformCard(samples: List<Float>, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        val strokeColor = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (samples.isEmpty()) return@Canvas
            val maxAmp = samples.maxOrNull()?.coerceAtLeast(1e-3f) ?: 1e-3f
            val stepX = if (samples.size > 1) size.width / (samples.size - 1f) else size.width
            val path = Path()
            samples.forEachIndexed { index, value ->
                val norm = (value / maxAmp).coerceIn(0f, 1f)
                val x = index * stepX
                val y = size.height - (norm * size.height)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun GainSlider(gainPosition: Float, onGainChanged: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Gain ${MainViewModel.gainLabel(gainPosition)}",
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = gainPosition,
            onValueChange = onGainChanged,
            valueRange = 0f..1f
        )
    }
}

@Composable
private fun MainsAndMurmurControls(
    includeMurmurs: Boolean,
    mainsFrequency: Int,
    onIncludeMurmursChanged: (Boolean) -> Unit,
    onMainsFrequencyChanged: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Acoustic emphasis", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Include murmurs")
            Switch(checked = includeMurmurs, onCheckedChange = onIncludeMurmursChanged)
        }
        Text(text = "Mains frequency", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(50, 60).forEach { value ->
                FilterChip(
                    selected = value == mainsFrequency,
                    onClick = { onMainsFrequencyChanged(value) },
                    label = { Text("${value} Hz") }
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(missingPermissions: List<String>, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            missingPermissions.mapNotNull { permission ->
                when (permission) {
                    Manifest.permission.RECORD_AUDIO -> context.getString(com.example.hearingaidstreamer.R.string.microphone_permission_rationale)
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN -> context.getString(com.example.hearingaidstreamer.R.string.bluetooth_permission_rationale)
                    Manifest.permission.MANAGE_OWN_CALLS -> context.getString(com.example.hearingaidstreamer.R.string.call_permission_rationale)
                    Manifest.permission.POST_NOTIFICATIONS -> context.getString(com.example.hearingaidstreamer.R.string.notification_permission_rationale)
                    else -> null
                }
            }.distinct().forEach { rationale ->
                Text(text = rationale, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onRequestPermissions) {
                Text(text = context.getString(com.example.hearingaidstreamer.R.string.grant_permissions))
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
