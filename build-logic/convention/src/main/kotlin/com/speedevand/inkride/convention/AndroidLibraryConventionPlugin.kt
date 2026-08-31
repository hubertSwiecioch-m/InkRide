package com.speedevand.inkride.convention

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.kotlin
import org.gradle.kotlin.dsl.withType

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlinx.kover")
                // kotlin-android is now built-in in AGP 9+
            }

            extensions.configure<LibraryExtension> {
                compileSdk = 36
                defaultConfig {
                    minSdk = 26
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
                // Unmocked Android framework calls (e.g. android.util.Log) would
                // otherwise throw in plain JVM unit tests. Returning defaults lets
                // production code call them without every test needing a mock.
                testOptions {
                    unitTests {
                        isReturnDefaultValues = true
                    }
                }
            }

            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }

            dependencies {
                add("testImplementation", kotlin("test"))
                add("testImplementation", libs.findLibrary("junit-jupiter-api").get())
                add("testRuntimeOnly", libs.findLibrary("junit-jupiter-engine").get())
                add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
                add("testImplementation", libs.findLibrary("assertk").get())
                add("testImplementation", libs.findLibrary("turbine").get())
                add("testImplementation", libs.findLibrary("mockito-kotlin").get())
                add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            }
        }
    }
}
