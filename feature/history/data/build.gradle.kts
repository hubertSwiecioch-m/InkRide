plugins {
    id("inkride.android.library")
}

android {
    namespace = "com.speedevand.inkride.history.data"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.android)
}
