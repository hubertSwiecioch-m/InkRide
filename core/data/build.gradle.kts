plugins {
    id("inkride.android.library")
}

android {
    namespace = "com.speedevand.inkride.core.data"
}

dependencies {
    implementation(project(":core:domain"))
}
