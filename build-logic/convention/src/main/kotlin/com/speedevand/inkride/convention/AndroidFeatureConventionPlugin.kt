package com.speedevand.inkride.convention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("inkride.android.library")
                apply("inkride.compose")
            }

            dependencies {
                add("implementation", project(":core:domain"))
                add("implementation", project(":core:presentation"))
                add("implementation", project(":core:design-system"))

                add("implementation", libs.findLibrary("koin-android").get())
                add("implementation", libs.findLibrary("koin-androidx-compose").get())
            }
        }
    }
}
