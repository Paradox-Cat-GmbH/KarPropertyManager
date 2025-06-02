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
import com.paradoxcat.karpropertymanager.model.CarStatus
import com.paradoxcat.karpropertymanager.model.KarProperty
import com.paradoxcat.karpropertymanager.model.KarPropertyManagerException
import com.paradoxcat.karpropertymanager.model.KarPropertyValue
import com.paradoxcat.karpropertymanager.model.PropertyAvailability
import com.paradoxcat.karpropertymanager.model.SubscriptionUnsuccessfulException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A utility class to abstract away the API-aware management of the connection to the Android Car service and to provide a Coroutines
 * Flow based API to consume them.
 *
 * @param context                               Instance of [android.content.Context].
 * @param carConnectionRetentionTimeoutMs       (optional) How long to keep the Android Car service connection
 *                                              alive after the last listener closes. Default is 10 seconds.
 * @param scope                                 (optional) A custom [kotlinx.coroutines.CoroutineScope] to share the
 *                                              Android Car service connection in. Default is a new IO scope.
 */
@Suppress("unused")
class KarPropertyManager(
    private val context: Context,
    carConnectionRetentionTimeoutMs: Long = CAR_TIMEOUT_MS,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    companion object {
        const val TAG = "KarPropertyManager"
        const val CAR_TIMEOUT_MS = 10000L
        const val CAR_PROPERTY_TIMEOUT_MS = 10000L
    }

    private val carStatusFlowInternal = MutableStateFlow(CarStatus.DISCONNECTED)

    /**
     * Reports the status of the connection to the Android Car service
     */
    val carStatusFlow = carStatusFlowInternal.asStateFlow()

    private val carPropertyManagerFlow = callbackFlow {
        if (Build.VERSION.SDK_INT >= 30) {
            startObservingCarWithModernApi()
        } else {
            startObservingCarWithLegacyApi()
        }
    }.onEach { carStatusFlowInternal.value = if (it == null) CarStatus.DISCONNECTED else CarStatus.CONNECTED }
        .onCompletion { carStatusFlowInternal.value = CarStatus.DISCONNECTED }
        .map {
            it?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        }.shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = carConnectionRetentionTimeoutMs,
                replayExpirationMillis = 0
            ),
            replay = 1
        )

    /**
     * Returns a single property value or null on a timeout. Uses property Ids from
     * [android.car.VehiclePropertyIds]
     *
     * If a connection to the Android Car service is already established it will be reused,
     * otherwise a new one will be established and closed after a property value is provided.
     *
     * @param propertyId  Id of a property
     * @param areaId      vehicle area type for property
     * @param timeout     timeout, default 10 seconds
     *
     * @return value of a property or null on a timeout
     */
    suspend fun <T> getPropertyValue(
        propertyId: Int,
        areaId: Int,
        timeout: Long = CAR_PROPERTY_TIMEOUT_MS,
    ): KarPropertyValue<T>? = getPropertyValueInternal<T>(propertyId, areaId, timeout)?.let {
        KarPropertyValue(
            value = it.value,
            timestampNs = it.timestamp,
        )
    }

    /**
     * Create and return flows for property values and availability. Uses property Ids from [android.car.VehiclePropertyIds].
     * If this property cannot be observed, e.g. due to a missing runtime permission, the returned flow will be closed
     * with an exception.
     *
     * These flows are cold. Terminating on these flows causes a connection to the Android Car service to be established
     * unless there's an established one already, then it will be reused. The connection will remain alive as long as
     * at least one subscription to any of the flows remains alive. Once all subscriptions close the Android Car service
     * will be also closed after [carConnectionRetentionTimeoutMs].
     *
     * @param propertyId      Id of a property
     * @param areaId          vehicle area type for property
     * @param updateRateHz    property update rate
     *
     * @return flow of property values
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getProperty(
        propertyId: Int,
        areaId: Int,
        updateRateHz: Float,
    ): KarProperty<T> = carPropertyManagerFlow
        .flatMapCloseOnNull { carPropertyManager ->
            callbackFlow {
                Log.d(TAG, "getProperty: subscribing to $propertyId")
                getPropertyValueInternal<T>(propertyId, areaId)?.let(::trySend)

                val listener = object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(carPropertyValue: CarPropertyValue<*>?) {
                        carPropertyValue
                            ?.takeIf { it.propertyId == propertyId && it.areaId == areaId }
                            ?.let(::trySend)
                    }

                    override fun onErrorEvent(
                        errorPropertyId: Int,
                        errorAreaId: Int,
                    ) {
                        Log.e(TAG, "getProperty: error event on $propertyId")
                        if (errorPropertyId == propertyId && errorAreaId == areaId) {
                            trySend(null)
                        }
                    }
                }

                val isSuccess = carPropertyManager.subscribeCompat(
                    propertyId = propertyId,
                    areaId = areaId,
                    updateRateHz = updateRateHz,
                    listener = listener,
                )

                if (!isSuccess) {
                    Log.e(TAG, "getProperty: subscription to property $propertyId unsuccessful")
                    close(KarPropertyManagerException(SubscriptionUnsuccessfulException()))
                }

                awaitClose {
                    try {
                        Log.d(TAG, "getProperty: unsubscribing from $propertyId")
                        carPropertyManager.unsubscribeCompat(listener)
                    } catch (_: Exception) {
                        // carProperty manager can be dead/invalid and can throw whatever and we don't really care
                        // the car tracking logic will create a new one and the callback flow will initialize again
                    }
                }
            }
        }.shareIn(scope, SharingStarted.WhileSubscribed())
        .let { rawValueFlow ->
            KarProperty(
                propertyId = propertyId,
                areaId = areaId,
                valueFlow = rawValueFlow
                    .filterNotNull()
                    .map {
                        KarPropertyValue<T>(
                            value = it.value as T,
                            timestampNs = it.timestamp,
                        )
                    },
                availabilityFlow = rawValueFlow.map {
                    when (it?.propertyStatus) {
                        CarPropertyValue.STATUS_AVAILABLE -> PropertyAvailability.AVAILABLE
                        CarPropertyValue.STATUS_UNAVAILABLE -> PropertyAvailability.UNAVAILABLE
                        else -> PropertyAvailability.ERROR
                    }
                },
            )
        }

    private suspend fun <T> getPropertyValueInternal(
        propertyId: Int,
        areaId: Int,
        timeout: Long = CAR_PROPERTY_TIMEOUT_MS,
    ) = withTimeoutOrNull(timeout) {
        try {
            carPropertyManagerFlow.filterNotNull().map { it.getProperty<T>(propertyId, areaId) }.first()
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

    private suspend fun ProducerScope<Car?>.startObservingCarWithModernApi() {
        Log.d(TAG, "creating car with current API")
        val mainCarInstance = Car.createCar(
            context,
            null,
            CAR_TIMEOUT_MS,
        ) { car, ready ->
            if (!ready) {
                Log.w(TAG, "car is no longer ready, setting to null")
                trySend(null)
                return@createCar
            }

            Log.d(TAG, "car is ready, pushing it further")
            trySend(car)
        }

        awaitClose {
            Log.d(TAG, "modernServiceConnection: disconnecting from car")
            mainCarInstance.disconnect()
        }
    }

    private suspend fun ProducerScope<Car?>.startObservingCarWithLegacyApi() {
        val serviceConnection = object : ServiceConnection {
            lateinit var car: Car
            val stop: AtomicBoolean = AtomicBoolean(false)

            override fun onServiceConnected(
                componentName: ComponentName?,
                binder: IBinder?,
            ) {
                Log.d(TAG, "Car Service connected, emitting car")
                trySend(car)
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                Log.d(TAG, "Car Service disconnected, emitting null, requesting new car")
                trySend(null)
                if (!stop.get()) {
                    try {
                        @Suppress("DEPRECATION")
                        car.connect()
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Exception during connect", e)
                    }
                }
            }
        }
        Log.d(TAG, "creating car with legacy API")

        @Suppress("DEPRECATION")
        serviceConnection.car = Car.createCar(context, serviceConnection)

        Log.d(TAG, "car is created and is connected: ${serviceConnection.car.isConnected}")

        if (serviceConnection.car.isConnected) {
            trySend(serviceConnection.car)
        } else {
            Log.d(TAG, "car is not connected, connecting")

            try {
                @Suppress("DEPRECATION")
                serviceConnection.car.connect()
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "exception during connect", e)
            }
        }

        awaitClose {
            Log.d(TAG, "disconnecting from car")
            serviceConnection.stop.set(true)
            serviceConnection.car.disconnect()
        }
    }

    private fun CarPropertyManager.unsubscribeCompat(listener: CarPropertyManager.CarPropertyEventCallback) {
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
        listener: CarPropertyManager.CarPropertyEventCallback,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                return subscribePropertyEvents(
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
            return registerCallback(
                listener,
                propertyId,
                updateRateHz,
            )
        }
    }
}
