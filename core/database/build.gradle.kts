plugins {
    id("inkride.android.library")
    id("inkride.room")
}

android {
    namespace = "com.speedevand.inkride.core.database"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.koin.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)
}
