import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("de.mannodermaus.android-junit5")
    jacoco
}

android {
    namespace = "com.maimai.home"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maimai.home"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release builds ship with no pre-filled agent address. The
            // user must enter the LAN host:port themselves; an empty default
            // makes the input field show its placeholder hint instead of a
            // wrong-network 192.168.x.x address (R1 #17 / Plan task 17).
            buildConfigField("String", "DEFAULT_AGENT_ADDRESS", "\"\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            // JaCoCo coverage instrumentation runs against the debug variant.
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
            // Debug builds keep the historic LAN sample so developers can
            // sideload + connect immediately without retyping the address.
            buildConfigField(
                "String",
                "DEFAULT_AGENT_ADDRESS",
                "\"192.168.1.100:8765\"",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.version",
                "META-INF/proguard/*",
                "DebugProbesKt.bin",
                "kotlin/**",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    lint {
        abortOnError = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    // Disable the android-junit5 plugin's bundled Jacoco task generation.
    // We define our own `jacocoTestReport` / `jacocoVerification` tasks below
    // (Wave 2.12) targeting only the debug variant.
    junitPlatform {
        jacocoOptions.taskGenerationEnabled.set(false)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    // ui-test-manifest provides ComponentActivity registration needed by createComposeRule().
    // Must be available for both debug and release unit tests.
    testImplementation("androidx.compose.ui:ui-test-manifest")
    // ui-test-manifest provides ComponentActivity registration needed by createComposeRule().
    // Must be available for both debug and release unit tests.
    testImplementation("androidx.compose.ui:ui-test-manifest")
    // Also add for release variant explicitly.
    testReleaseImplementation("androidx.compose.ui:ui-test-manifest")
    // ui-test-manifest provides ComponentActivity registration needed by createComposeRule().
    // Must be testImplementation (not debugImplementation) so release unit tests also work.
    testImplementation("androidx.compose.ui:ui-test-manifest")

    // ----- Unit test stack (Wave 2.7) ----------------------------------------
    // JUnit 5 (Jupiter) BOM aligns api / params / engine versions.
    val junitBom = platform("org.junit:junit-bom:5.14.1")
    testImplementation(junitBom)
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")

    // JUnit 4 + Vintage engine bridge runs the existing Robolectric / JUnit4
    // test suite under the JUnit Platform.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    // Truth assertions, Turbine for Flow/StateFlow, MockK for Kotlin-native mocks.
    testImplementation("com.google.truth:truth:1.4.2")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("io.mockk:mockk:1.13.14")
    testImplementation("io.mockk:mockk-agent:1.13.14")

    // Kotlin / coroutines test helpers.
    // `kotlin-test:2.3.0` ships JUnit5 / JUnit4 / TestNG integrations as
    // optional capabilities. We use Truth + Jupiter for assertions, but a few
    // legacy tests still call `kotlin.test.assertEquals(...)`. Pulling
    // `kotlin-test` directly keeps those compiling without dragging in
    // `kotlin-test-junit`, which would conflict with `kotlin-test-junit5` on
    // the `kotlin-test-framework-impl` capability.
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // HTTP / WebSocket fakes + Robolectric (DataStore, NSD, Manifest).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")

    // Compose UI tests under Robolectric (Wave 5).
    // ui-test-junit4 brings createComposeRule(); ui-test-manifest provides the
    // ComponentActivity needed to host setContent() under Robolectric.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")

    // Mockito is preserved from the pre-Wave-2 setup because two existing
    // Wave 1 tests (AgentClientTest + DiscoveryServiceTest) use Mockito.kt
    // helpers. New tests should prefer MockK; this dep stays so the legacy
    // suite keeps compiling under the JUnit Platform / Vintage engine.
    testImplementation("org.mockito:mockito-core:5.14.2")

    // ----- Instrumentation (Compose UI) test stack (Wave 2.7 + 2.11) ---------
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("io.mockk:mockk-android:1.13.14")
}

// Wave 5: Force androidx.core to 1.13.1 in the unit-test RUNTIME classpath only.
// Robolectric 4.13 shadows don't include the tag_compat_insets_dispatch field
// added in core 1.14+, causing NoSuchFieldError in Compose UI tests.
// We use afterEvaluate so the configuration exists before we access it.
afterEvaluate {
    configurations.matching {
        it.name.contains("UnitTest", ignoreCase = true) &&
            it.name.contains("RuntimeClasspath", ignoreCase = true)
    }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "androidx.core" &&
                (requested.name == "core" || requested.name == "core-ktx")
            ) {
                useVersion("1.13.1")
                because("Robolectric 4.13 shadows are incompatible with core 1.14+")
            }
        }
    }
}


// ----- JaCoCo configuration (Wave 2.12) -------------------------------------
// Targets the `debug` unit test variant; release is shrunk + obfuscated, which
// confuses JaCoCo class-name lookup.

jacoco {
    toolVersion = "0.8.12"
}

private val coverageClassDirs: List<String> = listOf(
    "com/maimai/home/data/**",
    "com/maimai/home/ui/**",
)

// Plan Resolved Decision #2 fixes the gate to 70% line coverage on
// `com/maimai/home/data/**` and `com/maimai/home/ui/**` excluding only
// *Test*, BuildConfig, and ui/theme/Theme.kt. Anything beyond that scope
// MUST be added to the plan first.
private val coverageExclusions: List<String> = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*Test*.*",
    "com/maimai/home/ui/theme/Theme*.*",
)

afterEvaluate {
    val jacocoReportTask = tasks.register<JacocoReport>("jacocoTestReport") {
        group = "verification"
        description =
            "Generate JaCoCo coverage report for the debug unit test variant."
        dependsOn("testDebugUnitTest")

        reports {
            xml.required.set(true)
            html.required.set(true)
        }

        val classDirs = files(
            fileTree("${layout.buildDirectory.get().asFile}/intermediates/javac/debug/classes") {
                include(coverageClassDirs)
                exclude(coverageExclusions)
            },
            fileTree("${layout.buildDirectory.get().asFile}/tmp/kotlin-classes/debug") {
                include(coverageClassDirs)
                exclude(coverageExclusions)
            },
        )
        classDirectories.setFrom(classDirs)

        sourceDirectories.setFrom(
            files(
                "src/main/kotlin",
                "src/main/java",
            ),
        )

        executionData.setFrom(
            fileTree(layout.buildDirectory) {
                include(
                    "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                    "jacoco/testDebugUnitTest.exec",
                )
            },
        )
    }

    val jacocoVerificationTask =
        tasks.register<JacocoCoverageVerification>("jacocoVerification") {
            group = "verification"
            description =
                "Enforce 70% line coverage on com/maimai/home/data/** and com/maimai/home/ui/** packages."
            dependsOn(jacocoReportTask)

            val classDirs = files(
                fileTree("${layout.buildDirectory.get().asFile}/intermediates/javac/debug/classes") {
                    include(coverageClassDirs)
                    exclude(coverageExclusions)
                },
                fileTree("${layout.buildDirectory.get().asFile}/tmp/kotlin-classes/debug") {
                    include(coverageClassDirs)
                    exclude(coverageExclusions)
                },
            )
            classDirectories.setFrom(classDirs)

            sourceDirectories.setFrom(
                files(
                    "src/main/kotlin",
                    "src/main/java",
                ),
            )

            executionData.setFrom(
                fileTree(layout.buildDirectory) {
                    include(
                        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                        "jacoco/testDebugUnitTest.exec",
                    )
                },
            )

            violationRules {
                rule {
                    element = "BUNDLE"
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = "0.70".toBigDecimal()
                    }
                }
            }
        }

    tasks.named("check").configure {
        dependsOn(jacocoVerificationTask)
    }
}
