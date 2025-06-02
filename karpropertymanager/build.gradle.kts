plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.jetbrains.dokka)
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    publishing {
        singleVariant("release")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

val releaseVersion: String? by project
val libVersion = releaseVersion ?: "SNAPSHOT"

tasks.register<Jar>("dokkaJavadocJar") {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    dependsOn(tasks.dokkaJavadoc)
    from(tasks.dokkaJavadoc.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.paradoxcat"
                artifactId = "karpropertymanager"
                version = libVersion
                artifact(tasks["dokkaJavadocJar"])
                pom {
                    packaging = "aar"
                    name = "KarPropertyManager"
                    description = "Kotlin wrapper over default Java CarPropertyManager API"
                    url = "https://github.com/Paradox-Cat-GmbH/KarPropertyManager"
                    licenses {
                        license {
                            name = "MIT License"
                            url = "http://www.opensource.org/licenses/mit-license.php"
                        }
                    }
                    organization {
                        name = "Paradox Cat GmbH"
                        url = "https://paradoxcat.com"
                    }
                    developers {
                        developer {
                            id = "Paradox-Cat-GmbH"
                            name = "Paradox Cat GmbH"
                            email = "info@paradoxcat.com"
                        }
                    }
                    scm {
                        connection =
                            "scm:git:git://github.com/Paradox-Cat-GmbH/KarPropertyManager.git"
                        developerConnection =
                            "scm:git:ssh://github.com/Paradox-Cat-GmbH/KarPropertyManager.git"
                        url = "https://github.com/Paradox-Cat-GmbH/KarPropertyManager"
                    }
                }
                from(components["release"])
            }
        }
        repositories {
            maven {
                name = "ossrh-staging-api"
                url =
                    uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                credentials {
                    username = System.getenv("MAVEN_CENTRAL_USERNAME")
                    password = System.getenv("MAVEN_CENTRAL_TOKEN")
                }
            }
        }
    }

    signing {
        useInMemoryPgpKeys(
            System.getenv("GPG_PRIVATE_KEY"),
            System.getenv("GPG_PASSPHRASE")
        )
        sign(publishing.publications["release"])
    }
}


tasks.register<Zip>("generateUploadPackage") {
    // Take the output of our publishing
    val publishTask = tasks.named(
        "publishReleasePublicationToMavenRepository",
        PublishToMavenRepository::class.java
    )


    from(publishTask.map { it.repository.url })

// Exclude maven-metadata.xml as Sonatype fails upload validation otherwise
    exclude {
        // Exclude left over directories not matching current version
        // That was needed otherwise older versions empty directories would be include in our ZIP
        if (it.file.isDirectory && it.path.matches(Regex(""".*\d+\.\d+.\d+$""")) && !it.path.contains(
                libVersion
            )
        ) {
            return@exclude true
        }

        // Only take files inside current version directory
        // Notably excludes maven-metadata.xml which Maven Central upload validation does not like
        (it.file.isFile && !it.path.contains(libVersion))
    }

    // Name of zip file
    archiveFileName.set("karpropertymanager-$libVersion.zip")
}