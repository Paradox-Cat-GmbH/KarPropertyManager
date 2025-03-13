# KarPropertyManager

Access data on android cars using modern Kotlin API.

## Why KarPropertyManager?

This library is a Kotlin wrapper over default Java [CarPropertyManager API](https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager).

CarPropertyManager is the official Google API to access car data via [Vehicle Properties](https://source.android.com/docs/automotive/vhal/previous/properties) on any variant of Android Automotive OS (AAOS).

Why KarPropertyManager?

* Modern Kotlin API
* No need to manually manage `Car` object lifecycle
* Supports all Android versions starting from API level 30

## Usage Example

```kotlin
val kmp = remember(context, scope) { KarPropertyManager(context, scope) }
LaunchedEffect(kmp) {
    kmp.startObservingCar()
}
val speedFlow = remember(kmp) {
    kmp.flowOfProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0, 0.5F)
        .stateIn(scope, SharingStarted.Eagerly, 0.0F)
}
val speed by speedFlow.collectAsState()
```

## Declare dependencies

Add to `libs.versions.toml` file:

```kotlin
[versions]
karPropertyManagerVersion = "0.1.0"

[libraries]
karpropertymanager = { group = "com.paradoxcat", name = "karpropertymanager", version.ref = "karPropertyManagerVersion" }
```

Add to `build.gradle.kts`:

```kotlin
implementation(libs.karpropertymanager)
```

## Versioning

We use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Permissions

KarPropertyManager permission model does not differ from CarPropertyManager.

We recommend the following documentation resources:

* https://developer.android.com/guide/topics/permissions/overview
* https://developer.android.com/training/permissions/usage-notes

## Where to get help

Please take a look at [sample application](./sample-app) to see a full working example.

For documentation, please refer to comment blocks in the methods you are using, we do not provide generated documentation.

If you are still facing issues, feel free to create an [issue](./issues) and label it appropriately.

## Feedback

Got feedback? Let us discuss! Feel free to create a [new discussion](./discussions).

## Contribution

Contributions are welcome, simply create a [pull request](./pulls).

## Maintainers

Paradox Cat GmbH - https://paradoxcat.com

