import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  defaultConfig {
    applicationId = "com.aistudio.vpn.hxpfbq"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk {
      abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
    }
    externalNativeBuild {
      cmake {
        arguments("-DANDROID_STL=c++_shared")
      }
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))

  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)

  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Firebase Auth with Google Sign-In requires all of the following to be uncommented together.
  // If you are using Firebase Auth with other providers (e.g. Email/Password), you may only need
  // firebase-auth.
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)

  implementation(libs.firebase.appcheck.recaptcha)
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
  implementation("com.google.android.gms:play-services-cronet:18.0.1")
  
  

  
  
  
  

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)

  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

// Define an abstract, configuration-cache compliant task class to compile the Rust engine using cargo-ndk
abstract class CompileRustTask : DefaultTask() {
    @get:InputDirectory
    abstract val cargoDir: org.gradle.api.file.DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDir: org.gradle.api.file.DirectoryProperty

    @TaskAction
    fun compile() {
        val cargoDirFile = cargoDir.get().asFile
        val jniLibsDirFile = jniLibsDir.get().asFile
        
        val cargoExecutable = try {
            val process = ProcessBuilder("which", "cargo").start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }

        if (cargoExecutable != null) {
            println("Cargo found at: $cargoExecutable. Initiating Rust compilation via cargo-ndk...")
            
            val process = ProcessBuilder(
                "cargo", "ndk",
                "-t", "arm64-v8a",
                "-t", "armeabi-v7a",
                "build",
                "--release"
            )
                .directory(cargoDirFile)
                .redirectErrorStream(true)
                .start()
            
            process.inputStream.bufferedReader().forEachLine { println("[Cargo] $it") }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw org.gradle.api.GradleException("Rust compilation failed with exit code: $exitCode")
            }
            
            // Map target triples to Android ABI directory structures
            val targets = mapOf(
                "aarch64-linux-android" to "arm64-v8a",
                "armv7-linux-androideabi" to "armeabi-v7a"
            )
            
            val cargoTargetDir = cargoDirFile.resolve("target")
            
            targets.forEach { (cargoTarget, androidAbi) ->
                val sourceFile = cargoTargetDir.resolve("$cargoTarget/release/libfluxdns.so")
                val destDir = jniLibsDirFile.resolve(androidAbi)
                val destFile = destDir.resolve("libfluxdns.so")
                
                if (sourceFile.exists()) {
                    destDir.mkdirs()
                    sourceFile.copyTo(destFile, overwrite = true)
                    println("Successfully injected compiled binary: $sourceFile -> $destFile")
                } else {
                    println("WARNING: Expected Rust binary was not found at $sourceFile")
                }
            }
            println("Rust compilation and output injection completed successfully!")
        } else {
            println("WARNING: 'cargo' not found in system PATH. Skipping Rust compilation and using precompiled jniLibs in build environment.")
        }
    }
}

// Register the custom task and configure its inputs and outputs using directory properties
val compileRust = tasks.register<CompileRustTask>("compileRust") {
    group = "build"
    description = "Compiles the Rust fluxdns-engine using cargo-ndk"
    cargoDir.set(layout.projectDirectory.dir("../fluxdns-engine"))
    jniLibsDir.set(layout.projectDirectory.dir("src/main/jniLibs"))
}

// Wire the custom compileRust task into the standard Android lifecycle
tasks.named("preBuild") {
    dependsOn(compileRust)
}

