plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish.base")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}

android {
    namespace = "android.zero.studio.widget.editor.symbolinput"

    buildFeatures {
            compose = true // Tells AGP to enable Compose compilation hooks
        }
}
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.1")

    implementation(projects.editor)
    api(libs.androidx.annotation)
    implementation(projects.languageJava)
    implementation(projects.languageTextmate)
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    

}
