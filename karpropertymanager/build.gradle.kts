plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
}

android {
    namespace = "com.paradoxcat.karpropertymanager"
    compileSdk = 35

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    useLibrary("android.car", required = false)

    packaging { resources.excludes.add("META-INF/*") }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

val libVersion = "1.0.0"

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.paradoxcat"
            artifactId = "karpropertymanager"
            version = libVersion

            afterEvaluate {
                from(components["release"])
            }

        }
    }

    repositories {
        maven {
            name = "maven"
            url = uri(layout.buildDirectory.dir("maven"))
        }
    }
}

signing {
    // Use installed GPG rather than built-in outdated version
    useGpgCmd()
// Sign all publications I guess
    sign(publishing.publications)
//sign(publishing.publications["release"])
}

tasks.register<Zip>("generateUploadPackage") {
    // Take the output of our publishing
    val publishTask = tasks.named(
        "publishReleasePublicationToMavenRepository",
        PublishToMavenRepository::class.java)

    from(publishTask.map { it.repository.url })

// Exclude maven-metadata.xml as Sonatype fails upload validation otherwise
    exclude {
        // Exclude left over directories not matching current version
        // That was needed otherwise older versions empty directories would be include in our ZIP
        if (it.file.isDirectory && it.path.matches(Regex(""".*\d+\.\d+.\d+$""")) && !it.path.contains(libVersion)) {
            return@exclude true
        }

        // Only take files inside current version directory
        // Notably excludes maven-metadata.xml which Maven Central upload validation does not like
        (it.file.isFile && !it.path.contains(libVersion))
    }

    into("karpropertymanager")
    // Name of zip file
    archiveFileName.set("karpropertymanager.zip")
}