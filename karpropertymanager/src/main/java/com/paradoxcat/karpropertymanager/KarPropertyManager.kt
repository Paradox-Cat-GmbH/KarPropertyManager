package com.paradoxcat.karpropertymanager

import android.car.Car
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("unused")
@OptIn(ExperimentalCoroutinesApi::class)
class KarPropertyManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        const val TAG = "KarPropertyManager"
        const val CAR_TIMEOUT_MS = 10000L
    }

    private val carFlow = MutableStateFlow<Car?>(null)
    private val carPropertyManagerFlow = carFlow
        .map {
            it?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        }
        .shareIn(scope, SharingStarted.Lazily)

    fun startObservingCar() {
        if (Build.VERSION.SDK_INT >= 30) {
            Log.d(TAG, "startObservingCar: Current route.")
            Car.createCar(
                context,
                null,
                CAR_TIMEOUT_MS,
            ) { car, ready ->
                if (!ready) {
                    Log.w(TAG, "Car is no longer ready, setting to null")
                    carFlow.value = null
                    return@createCar
                }

                Log.d(TAG, "Car is ready, pushing it further")
                carFlow.value = car
            }
        } else {
            scope.launch {
                Log.d(TAG, "startObservingCar: Legacy route.")
                callbackFlow {
                    val serviceConnection = object : ServiceConnection {
                        lateinit var car: Car
                        val stop: AtomicBoolean = AtomicBoolean(false)

                        override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
                            Log.d(TAG, "legacyServiceConnection: Car Service connected, emitting car")
                            trySend(car)
                        }

                        override fun onServiceDisconnected(componentName: ComponentName?) {
                            Log.d(TAG, "legacyServiceConnection: Car Service disconnected, emitting null, requesting new car")
                            trySend(null)
                            if (!stop.get()) {
                                car.connect()
                            }
                        }
                    }

                    serviceConnection.car = Car.createCar(context, serviceConnection)

                    awaitClose {
                        serviceConnection.stop.set(true)
                        serviceConnection.car.disconnect()
                    }
                }.collect {
                    carFlow.value = it
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> flowOfProperty(propertyId: Int, areaId: Int, updateRateHz: Float): Flow<T> =
        carPropertyManagerFlow.flatMapLatest { carPropertyManager ->
            if (carPropertyManager == null) {
                flowOf()
            } else {
                callbackFlow {
                    val listener = object : CarPropertyManager.CarPropertyEventCallback {
                        override fun onChangeEvent(carPropertyValue: CarPropertyValue<*>?) {
                            carPropertyValue
                                ?.takeIf { it.areaId == areaId }
                                ?.let { it.value as? T }
                                ?.let(::trySend)
                        }

                        override fun onErrorEvent(propertyId: Int, areaId: Int) {
                            Log.e(TAG, "error")
                        }
                    }

                    carPropertyManager.subscribeCompat(propertyId, areaId, updateRateHz, listener)

                    awaitClose {
                        carPropertyManager.unsubscribeCompat(listener)
                    }
                }
            }
        }

    private fun CarPropertyManager.unsubscribeCompat(
        listener: CarPropertyManager.CarPropertyEventCallback,
    ) {
        if (Build.VERSION.SDK_INT >= 35) {
            unsubscribePropertyEvents(listener)
        } else {
            unregisterCallback(listener)
        }
    }

    private fun CarPropertyManager.subscribeCompat(
        propertyId: Int,
        areaId: Int,
        updateRateHz: Float,
        listener: CarPropertyManager.CarPropertyEventCallback
    ) {
        if (Build.VERSION.SDK_INT >= 35) {
            subscribePropertyEvents(
                propertyId,
                areaId,
                updateRateHz,
                listener,
            )
        } else {
            registerCallback(
                listener,
                propertyId,
                updateRateHz,
            )
        }
    }
}