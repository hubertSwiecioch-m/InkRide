plugins {
    id("inkride.android.feature")
}

android {
    namespace = "com.speedevand.inkride.settings.presentation"
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.mudita.mmd)
    implementation(libs.androidx.compose.ui)
}
