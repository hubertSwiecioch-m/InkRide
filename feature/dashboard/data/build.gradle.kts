plugins {
    id("inkride.android.library")
}

android {
    namespace = "com.speedevand.inkride.dashboard.data"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":feature:dashboard:domain"))
}
