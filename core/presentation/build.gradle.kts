plugins {
    id("inkride.android.library")
    id("inkride.compose")
}

android {
    namespace = "com.speedevand.inkride.core.presentation"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
