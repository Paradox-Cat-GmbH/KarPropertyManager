/**
 * Copyright (c) 2025 Paradox Cat GmbH
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.example.karpropertysampleapp

import android.car.Car
import android.car.VehiclePropertyIds
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.karpropertysampleapp.ui.theme.KarPropertySampleAppTheme
import com.paradoxcat.karpropertymanager.KarPropertyManager
import kotlinx.coroutines.flow.flowOf

class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val requestPermissionLauncher =
        registerForActivityResult(RequestPermission()) { permissionsGranted = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermission()
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val kpm = remember(context, scope) {
                KarPropertyManager(
                    context = context,
                    scope = scope,
                )
            }

            var subscribed by remember { mutableStateOf(false) }

            val speedFlow = remember(subscribed, kpm, permissionsGranted) {
                if (permissionsGranted && subscribed) {
                    kpm.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0, 60F).valueFlow
                } else {
                    flowOf()
                }
            }

            val speed by speedFlow.collectAsState(initial = null)

            KarPropertySampleAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (subscribed) {
                            Text("Speed: $speed")
                        } else {
                            Text("Not subscribed")
                        }

                        Button(onClick = {
                            Log.d(TAG, "===============subscribe click===============")
                            subscribed = !subscribed
                        }) {
                            Text("Switch subscription")
                        }
                    }
                }
            }
        }
    }

    private fun requestPermission() {
        if (checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Car.PERMISSION_SPEED)
        } else {
            permissionsGranted = true
        }
    }

    companion object {
        const val TAG = "SampleApp"
    }
}
