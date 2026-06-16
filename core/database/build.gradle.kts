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
}
