plugins {
    id("inkride.android.library")
}

android {
    namespace = "com.speedevand.inkride.tracking.data"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)
}
