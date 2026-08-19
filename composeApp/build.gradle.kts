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

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
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
    commandLine("python3", checker.asFile.absolutePath, migrations.asFile.absolutePath)
}

tasks.matching { it.name.startsWith("generate") && it.name.contains("AppDatabase") }
    .configureEach { dependsOn(checkSchemaSnapshots) }

val ambiguousAndroidReleaseTasks = setOf("assembleRelease", "bundleRelease")
val requestedAndroidTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }.toSet()
val ambiguousRequested = requestedAndroidTasks.intersect(ambiguousAndroidReleaseTasks)
if (ambiguousRequested.isNotEmpty()) {
    throw GradleException(
        "Ambiguous Android release task is forbidden: ${ambiguousRequested.sorted().joinToString()}. " +
            "Use an explicit profile task such as assemblePlaySafeRelease or assembleEnterpriseRelease.",
    )
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

    flavorDimensions += "distribution"
    productFlavors {
        create("playSafe") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION_PROFILE_ID", "\"PLAY_SAFE\"")
            manifestPlaceholders["appLabel"] = "Kotlin Auto WebView"
        }
        create("enterprise") {
            dimension = "distribution"
            applicationIdSuffix = ".enterprise"
            versionNameSuffix = "-enterprise"
            buildConfigField("String", "DISTRIBUTION_PROFILE_ID", "\"ENTERPRISE_SIDELOAD\"")
            manifestPlaceholders["appLabel"] = "Kotlin Auto WebView Enterprise"
        }
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

    val observedSourceSets = sourceSets
    val observedProductFlavors = productFlavors
    tasks.register("reportAndroidDistributionSourceSets") {
        group = "verification"
        description = "Write the resolved AGP distribution flavor/source-set graph for evidence."
        val outputFile = layout.buildDirectory.file("reports/android-distribution/source-sets.txt")
        outputs.file(outputFile)
        doLast {
            val lines = buildList {
                add(
                    "AGP productFlavors=" +
                        observedProductFlavors.map { it.name }.sorted().joinToString(","),
                )
                observedSourceSets.sortedBy { it.name }.forEach { sourceSet ->
                    add(
                        "AGP sourceSet=${sourceSet.name};" +
                            "manifest=${sourceSet.manifest.srcFile.invariantSeparatorsPath};" +
                            "java=${sourceSet.java.srcDirs.map { it.invariantSeparatorsPath }.sorted().joinToString("|")};" +
                            "assets=${sourceSet.assets.srcDirs.map { it.invariantSeparatorsPath }.sorted().joinToString("|")}",
                    )
                }
            }
            val file = outputFile.get().asFile
            file.parentFile.mkdirs()
            file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
            lines.forEach(::println)
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
