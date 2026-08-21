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

// Schema snapshots may be stored as tracked .db.gz artifacts when the transport that produced a
// branch cannot safely write binary SQLite bytes. Materialization is part of the Gradle graph —
// not a CI-only setup step — so local and hosted callers see the same expanded snapshot and digest
// checks before SQLDelight or the readable-snapshot gate consumes it.
val materializeSchemaSnapshots by tasks.registering(Exec::class) {
    group = "verification"
    description = "Materialize and digest-check compressed SQLDelight schema snapshots."
    val materializer = rootProject.layout.projectDirectory.file("scripts/ci/materialize-schema-snapshots.py")
    val migrations = layout.projectDirectory.dir("src/commonMain/sqldelight/migrations")
    commandLine("python3", materializer.asFile.absolutePath, migrations.asFile.absolutePath)
}

// An unreadable schema snapshot makes `verifyMigrations` infer an empty schema, which it then
// reports as every statement in the migration referring to something that does not exist. The
// errors all name the migration, so the migration is what gets edited — that cost three attempts
// in issue #25 while the migration was already correct.
//
// This is wired as a dependency of the generator rather than as a CI step so that every caller
// gets it: a local `./gradlew` run reaches the same gate as a runner, and nobody has to remember.
val checkSchemaSnapshots by tasks.registering(Exec::class) {
    group = "verification"
    description = "Reject an unreadable SQLDelight schema snapshot before the generator sees it."
    val checker = rootProject.layout.projectDirectory.file("scripts/ci/check-schema-snapshots.py")
    val migrations = layout.projectDirectory.dir("src/commonMain/sqldelight/migrations")
    dependsOn(materializeSchemaSnapshots)
    commandLine("python3", checker.asFile.absolutePath, migrations.asFile.absolutePath)
}

tasks.matching { it.name.startsWith("generate") && it.name.contains("AppDatabase") }
    .configureEach { dependsOn(checkSchemaSnapshots) }

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
            // The opt-in loopback MCP listener is built on com.sun.net.httpserver, which lives in
            // jdk.httpserver. Without this the packaged runtime image starts but the listener
            // cannot bind, and the failure only surfaces after a user enables the profile.
            modules("jdk.httpserver")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KotlinAutoWebView"
            packageVersion = "0.1.0"
            description = "Agent-aware multiplatform WebView shell"
            vendor = "ed3c"
        }
    }
}
