android {
    namespace = "com.wizdier.wizplay"
    compileSdkVersion(35)
    defaultConfig { minSdk = 21; targetSdk = 35 }
}
dependencies {
    val implementation by configurations
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
