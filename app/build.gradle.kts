plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
val debugKeystore = rootProject.file(".tools/debug.keystore")
val createDebugKeystore by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Creates the local standard Android debug key, never a release key."
    outputs.file(debugKeystore)
    onlyIf { !debugKeystore.exists() }
    doFirst { debugKeystore.parentFile.mkdirs() }
    val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
    commandLine("${System.getProperty("java.home")}/bin/$executable", "-genkeypair", "-noprompt",
        "-keystore", debugKeystore.absolutePath, "-storetype", "JKS", "-storepass", "android",
        "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US")
}
tasks.matching { it.name == "validateSigningDebug" || it.name == "validateSigningDebugAndroidTest" }
    .configureEach { dependsOn(createDebugKeystore) }
android {
    namespace = "com.jobscout"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jobscout.community"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.14.0-public"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    // Stable per-workspace debug identity even when ANDROID_USER_HOME changes between tools.
    signingConfigs.getByName("debug") { storeFile = debugKeystore }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { jvmToolchain(21) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(libs.coil.compose)
    implementation(project(":domain"))
    implementation(libs.core)
    implementation(libs.activity)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.compose.material)
    implementation(libs.compose.icons)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation)
    implementation(libs.serialization)
    implementation(libs.hilt.android)
    implementation(libs.hilt.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.work)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.jsoup)
    implementation(libs.pdfbox)
    implementation(libs.bouncycastle.provider)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.util)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.android.junit)
    androidTestImplementation(libs.android.runner)
    androidTestImplementation(libs.espresso)
}
