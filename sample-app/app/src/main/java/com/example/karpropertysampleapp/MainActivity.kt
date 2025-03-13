package com.example.karpropertysampleapp

import android.car.Car
import android.car.VehicleAreaType
import android.car.VehiclePropertyIds.GEAR_SELECTION
import android.car.hardware.property.CarPropertyManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.karpropertysampleapp.ui.theme.KarPropertySampleAppTheme

class MainActivity : ComponentActivity() {

    private var car: Car? = null
    private var carPropertyManager: CarPropertyManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            car = Car.createCar(this)
            carPropertyManager = car?.getCarManager(CarPropertyManager::class.java)

            KarPropertySampleAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Gear Selection: " + getGearSelection())
                    }
                }
            }
        }
    }

    private fun getGearSelection(): Int? {
        val gear = carPropertyManager?.getProperty<Int?>(
            GEAR_SELECTION,
            VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL
        )?.value
        return gear
    }
}
