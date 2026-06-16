package com.speedevand.inkride.convention

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType

class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            val libraryExtension = extensions.findByType<LibraryExtension>()
            if (libraryExtension != null) {
                libraryExtension.buildFeatures {
                    compose = true
                }
            }

            val applicationExtension = extensions.findByType<ApplicationExtension>()
            if (applicationExtension != null) {
                applicationExtension.buildFeatures {
                    compose = true
                }
            }

            dependencies {
                val bom = libs.findLibrary("androidx-compose-bom").get()
                add("implementation", platform(bom))
                add("androidTestImplementation", platform(bom))
                add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
            }
        }
    }
}
