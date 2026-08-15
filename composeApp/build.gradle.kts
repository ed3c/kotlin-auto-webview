import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KotlinAutoWebView"
            isStatic = true
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "kotlinAutoWebView"
        browser {
            commonWebpackConfig {
                outputFileName = "kotlinAutoWebView.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        add(project.rootDir.path)
                        add(project.projectDir.path)
                    }
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.compose.webview)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.async.extensions)
            implementation(libs.sqldelight.coroutines.extensions)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.sqldelight.android.driver)
        }

        val iosArm64Main by getting
        iosArm64Main.dependencies {
            implementation(libs.sqldelight.native.driver)
        }

        val iosSimulatorArm64Main by getting
        iosSimulatorArm64Main.dependencies {
            implementation(libs.sqldelight.native.driver)
        }

        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.sqldelight.sqlite.driver)
        }

        val wasmJsMain by getting
        wasmJsMain.dependencies {
            implementation(libs.sqldelight.web.worker.driver)
        }
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("dev.ed3c.autowebview.persistence.db")
            generateAsync.set(true)
            verifyMigrations.set(true)
        }
    }
}

android {
    namespace = "dev.ed3c.autowebview"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ed3c.autowebview"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
            )
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.ed3c.autowebview.MainKt"
        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED")
        if (System.getProperty("os.name").contains("Mac")) {
            jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
            jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        }
        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KotlinAutoWebView"
            packageVersion = "0.1.0"
            description = "Agent-aware multiplatform WebView shell"
            vendor = "ed3c"
        }
    }
}
