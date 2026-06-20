plugins {
    id("inkride.android.library")
}

android {
    namespace = "com.speedevand.inkride.ble.data"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)
}
