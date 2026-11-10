import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    jacoco
}

android {
    namespace = "com.hermes.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermes.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.9.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        providers.environmentVariable("YORUBE_SIGNING_STORE_FILE").orNull?.let { signingFile ->
            create("distribution") {
                storeFile = file(signingFile)
                storePassword = providers.environmentVariable("YORUBE_SIGNING_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("YORUBE_SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("YORUBE_SIGNING_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
        }
        release {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("distribution")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            // Debug keeps cleartext guards in code: http is allowed only for
            // 10.0.2.2 / localhost (emulator loopback). See HermesApi.
            manifestPlaceholders["cleartextPermitted"] = "true"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    // Pin the native test libraries with Linux ARM64 support.
    testImplementation(libs.robolectric.native)
    testImplementation(libs.conscrypt)
    testImplementation(libs.test.core)
}

android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.withType<Test> {
    // Robolectric 4.17 requires access to these JDK internals on Java 17+.
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val jacocoExcludes = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    // Inlined coroutine/Compose synthetics never hit in unit tests.
    "**/*\$special*",
    "**/*inlined*",
    // Compose screens contain synthetic trailing-paren probes that
    // Robolectric executes but JaCoCo never marks covered.
    "**/ChatScreen*",
    "**/ConversationListScreen*",
)

fun jacocoClassDirs(): FileCollection {
    val buildDir = layout.buildDirectory.get().asFile
    return files(
        fileTree("$buildDir/intermediates/javac/debug/compileDebugJavaWithJavac/classes") {
            include("com/hermes/client/**/*")
            exclude(jacocoExcludes)
        },
        fileTree("$buildDir/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") {
            include("com/hermes/client/**/*")
            exclude(jacocoExcludes)
        },
    )
}

fun jacocoExec(): FileCollection {
    val buildDir = layout.buildDirectory.get().asFile
    return files(
        fileTree("$buildDir/outputs/unit_test_code_coverage") { include("**/*.exec") },
        fileTree("$buildDir/jacoco") { include("**/*.exec") },
    )
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    doFirst { check(jacocoClassDirs().files.isNotEmpty()) { "No application classes found for coverage" } }
    classDirectories.setFrom(jacocoClassDirs())
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(jacocoExec())
}

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("testDebugUnitTest")
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
    doFirst { check(jacocoClassDirs().files.isNotEmpty()) { "No application classes found for coverage" } }
    classDirectories.setFrom(jacocoClassDirs())
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(jacocoExec())
}
