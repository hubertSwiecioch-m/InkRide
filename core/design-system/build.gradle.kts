plugins {
    id("inkride.android.library")
    id("inkride.compose")
}

android {
    namespace = "com.speedevand.inkride.core.design_system"
}

dependencies {
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.mudita.mmd)
}
