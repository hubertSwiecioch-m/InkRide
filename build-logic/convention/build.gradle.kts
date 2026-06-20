plugins {
    `kotlin-dsl`
}

group = "com.speedevand.inkride.convention"

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.room.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = "inkride.kotlin.library"
            implementationClass = "com.speedevand.inkride.convention.KotlinLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "inkride.android.library"
            implementationClass = "com.speedevand.inkride.convention.AndroidLibraryConventionPlugin"
        }
        register("compose") {
            id = "inkride.compose"
            implementationClass = "com.speedevand.inkride.convention.ComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "inkride.android.feature"
            implementationClass = "com.speedevand.inkride.convention.AndroidFeatureConventionPlugin"
        }
        register("room") {
            id = "inkride.room"
            implementationClass = "com.speedevand.inkride.convention.RoomConventionPlugin"
        }
    }
}
