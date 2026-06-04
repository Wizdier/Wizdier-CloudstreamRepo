import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// ─────────────────────────────────────────────────────────────────────────────
// Root Gradle config for Wizdier-CloudstreamRepo.
//
// Version pins mirror the recloudstream reference templates so builds
// reproduce reliably on CI:
//   • AGP    8.7.3
//   • Kotlin 2.3.0   ← MUST match the Kotlin version used to compile the
//                      pre-built `cloudstream.jar` stubs that the recloudstream
//                      gradle plugin downloads into
//                      ~/.gradle/caches/cloudstream/cloudstream/.
//                      The current upstream stubs are built with Kotlin 2.3.0;
//                      using 2.1.0 here triggers ~70 "Class … was compiled
//                      with an incompatible version of Kotlin. The actual
//                      metadata version is 2.3.0, but the compiler version
//                      2.1.0 can read versions up to 2.2.0." errors, which
//                      then cascade into Unresolved reference 'MainAPI' /
//                      'mainPageOf' / 'TvType' etc.
//   • recloudstream gradle plugin – `-SNAPSHOT`
//
// Note: `namespace` is intentionally NOT set in this file. AGP 8.7's
// LibraryVariantBuilderImpl reads the namespace from the module's own
// LibraryExtension BEFORE this root subprojects { android { ... } } lambda
// finishes resolving, so it has to be declared per-module — see each
// `*/build.gradle.kts`.
// ─────────────────────────────────────────────────────────────────────────────

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // GITHUB_REPOSITORY is set automatically inside GitHub Actions runs.
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/Wizdier/Wizdier-CloudstreamRepo")
    }

    android {
        compileSdkVersion(35)

        defaultConfig {
            minSdk = 21
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Compile-time stubs for all Cloudstream classes (not bundled into .cs3).
        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")   // HTTP client
        implementation("org.jsoup:jsoup:1.18.3")               // HTML parser
        // IMPORTANT: do NOT bump Jackson above 2.13.1 — newer versions break
        // on older Android devices, per the recloudstream extension template.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
