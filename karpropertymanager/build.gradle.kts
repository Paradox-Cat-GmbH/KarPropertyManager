import org.jreleaser.model.Active

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.jetbrains.dokka)
    `maven-publish`
    alias(libs.plugins.jreleaser)
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
                "proguard-rules.pro",
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
val libVersion = releaseVersion ?: "v"

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
                    withXml {
                        asNode().appendNode("plugin").apply {
                            appendNode("groupId").setValue("com.simpligility.maven.plugins")
                            appendNode("artifactId").setValue("android-maven-plugin")
                            appendNode("version").setValue("4.6.0")
                            appendNode("extensions").setValue("true")
                        }
                    }
                    name = "KarPropertyManager"
                    description = "Kotlin wrapper over default Java CarPropertyManager API"
                    url = "https://github.com/Paradox-Cat-GmbH/KarPropertyManager"
                    packaging = "aar"
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
                name = "staging"
                url =
                    layout.buildDirectory
                        .dir("staging-deploy")
                        .get()
                        .asFile
                        .toURI()
            }
        }
    }

    jreleaser {
        gitRootSearch = true
        project {
            version = libVersion
        }
        signing {
            active = Active.ALWAYS
            armored = true
        }
        deploy {
            maven {
                mavenCentral {
                    create("sonatype") {
                        active = Active.ALWAYS
                        url = "https://central.sonatype.com/api/v1/publisher"
                        stagingRepository(
                            layout.buildDirectory
                                .dir("staging-deploy")
                                .get()
                                .asFile.path,
                        )
                    }
                }
            }
        }
    }
}

// tasks.register<Zip>("generateUploadPackage") {
//    // Take the output of our publishing
//    val publishTask =
//        tasks.named(
//            "publishReleasePublicationToMavenRepository",
//            PublishToMavenRepository::class.java,
//        )
//
//    from(publishTask.map { it.repository.url })
//
// // Exclude maven-metadata.xml as Sonatype fails upload validation otherwise
//    exclude {
//        // Exclude left over directories not matching current version
//        // That was needed otherwise older versions empty directories would be include in our ZIP
//        if (it.file.isDirectory &&
//            it.path.matches(Regex(""".*\d+\.\d+.\d+$""")) &&
//            !it.path.contains(
//                libVersion,
//            )
//        ) {
//            return@exclude true
//        }
//
//        // Only take files inside current version directory
//        // Notably excludes maven-metadata.xml which Maven Central upload validation does not like
//        (it.file.isFile && !it.path.contains(libVersion))
//    }
//
//    // Name of zip file
//    archiveFileName.set("karpropertymanager-$libVersion.zip")
// }
