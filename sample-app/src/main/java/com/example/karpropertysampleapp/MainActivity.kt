package com.example.karpropertysampleapp

import android.Manifest
import android.car.Car
import android.car.VehiclePropertyIds
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.karpropertysampleapp.ui.theme.KarPropertySampleAppTheme
import com.paradoxcat.karpropertymanager.KarPropertyManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermission()
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val kpm = remember(context, scope) { KarPropertyManager(context, scope) }

            LaunchedEffect(kpm) {
                kpm.startObservingCar()
            }

            val speedFlow = remember(kpm) {
                kpm.flowOfProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0, 0.5F)
                    .stateIn(scope, SharingStarted.Eagerly, 0.0F)
            }

            val speed by speedFlow.collectAsState()

            KarPropertySampleAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Speed: $speed")
                    }
                }
            }
        }
    }

    private fun requestPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Car.PERMISSION_SPEED, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1
            )
        }
    }
}
