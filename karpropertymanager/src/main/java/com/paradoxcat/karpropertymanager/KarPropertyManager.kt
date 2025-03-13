package com.paradoxcat.karpropertymanager

import android.car.Car
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarInternalErrorException
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.PropertyAccessDeniedSecurityException
import android.car.hardware.property.PropertyNotAvailableAndRetryException
import android.car.hardware.property.PropertyNotAvailableException
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class KarPropertyManagerException(override val cause: Exception): Exception(cause = cause)

enum class CarStatus{ CONNECTED, DISCONNECTED }
@Suppress("unused")
@OptIn(ExperimentalCoroutinesApi::class)
class KarPropertyManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        const val TAG = "KarPropertyManager"
        const val CAR_TIMEOUT_MS = 10000L
        const val CAR_PROPERTY_TIMEOUT_MS = 10000L
    }

    private val carStatusFlowInternal = MutableStateFlow(CarStatus.DISCONNECTED)
    val carStatusFlow = carStatusFlowInternal.asStateFlow()

    private val carFlow = MutableStateFlow<Car?>(null)
    private val carPropertyManagerFlow = carFlow
        .map {
            it?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        }
        .shareIn(scope, SharingStarted.Lazily)

    /**
     * Starts observing the Car Service. No values from other methods
     * will be returned unless this method is called beforehand.
     */
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
                    carStatusFlowInternal.value = CarStatus.DISCONNECTED
                    carFlow.value = null
                    return@createCar
                }

                Log.d(TAG, "Car is ready, pushing it further")
                carFlow.value = car
                carStatusFlowInternal.value = CarStatus.CONNECTED
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
                                @Suppress("DEPRECATION")
                                try {
                                    car.connect()
                                } catch (e: IllegalArgumentException) {
                                    Log.e(TAG, "Exception during connect", e)
                                }

                            }
                        }
                    }
                    Log.d(TAG, "creating car")

                    @Suppress("DEPRECATION")
                    serviceConnection.car = Car.createCar(context, serviceConnection)

                    Log.d(TAG, "car is created and is connected: ${serviceConnection.car.isConnected}")

                    if (serviceConnection.car.isConnected) {
                        trySend(serviceConnection.car)
                    } else {
                        Log.d(TAG, "car is not connected, connecting")

                        @Suppress("DEPRECATION")
                        try {
                            serviceConnection.car.connect()
                        } catch (e: IllegalArgumentException) {
                            Log.e(TAG, "Exception during connect", e)
                        }
                    }

                    awaitClose {
                        serviceConnection.stop.set(true)
                        serviceConnection.car.disconnect()
                    }
                }.collect {
                    carFlow.value = it
                    carStatusFlowInternal.value =  if (it != null) CarStatus.CONNECTED else CarStatus.DISCONNECTED
                }
            }
        }
    }

    /**
     * Returns a single property value or null on a timeout. Uses property Ids from
     * [android.car.VehiclePropertyIds]
     *
     * @param   propertyId  Id of a property
     * @param   areaId      vehicle area type for property
     * @param   timeout     timeout, default 10 seconds
     *
     * @throws KarPropertyManagerException
     *
     * @return  value of a property or null on a timeout
     */
    suspend fun <T> getProperty(propertyId: Int, areaId: Int, timeout: Long = CAR_PROPERTY_TIMEOUT_MS): T? {
        return withTimeoutOrNull(timeout) {
            val carPropertyManager = carPropertyManagerFlow.filterNotNull().first()
            try {
                carPropertyManager.getProperty<T>(propertyId, areaId)?.value
            } catch (e: CarInternalErrorException) {
                throw KarPropertyManagerException(e)
            } catch (e: PropertyAccessDeniedSecurityException) {
                throw KarPropertyManagerException(e)
            } catch (e: PropertyNotAvailableAndRetryException) {
                throw KarPropertyManagerException(e)
            } catch (e: PropertyNotAvailableException) {
                throw KarPropertyManagerException(e)
            } catch (e: IllegalArgumentException) {
                throw KarPropertyManagerException(e)
            }
        }
    }

    /**
     * Returns flow of the property values. Uses property Ids from [android.car.VehiclePropertyIds]
     *
     * @param   propertyId      Id of a property
     * @param   areaId          vehicle area type for property
     * @param   updateRateHz    property update rate
     *
     * @throws SecurityException if the caller does not have read permission to the properties
     * registered for this callback
     * @throws KarPropertyManagerException if there are overlapping areaIds or the executor is
     * registered to another callback or one of the properties are
     * not supported.
     *
     * @return  flow of property values
     */
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
            @Suppress("DEPRECATION")
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
            try {
                subscribePropertyEvents(
                    propertyId,
                    areaId,
                    updateRateHz,
                    listener,
                )
            } catch (e: IllegalArgumentException) {
                throw KarPropertyManagerException(e)
            }
        } else {
            @Suppress("DEPRECATION")
            registerCallback(
                listener,
                propertyId,
                updateRateHz,
            )
        }
    }
}