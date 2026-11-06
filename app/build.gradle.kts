plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// عنوان خادم الراسبيري يُقرأ من ملف server.properties في جذر المستودع.
// إن كان فارغًا يُبنى التطبيق وتبقى المراسلة معطّلة.
val serverProps = java.util.Properties().apply {
    val f = rootProject.file("server.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val serverUrl: String = serverProps.getProperty("url", "").trim()

fun installedNdkVersion(): String? {
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
    val versions = File(sdk, "ndk").listFiles()
        ?.filter { File(it, "source.properties").isFile }
        ?.map { it.name }
        ?: return null
    return versions.maxWithOrNull(
        compareBy<String>(
            { it.split(".").getOrNull(0)?.toIntOrNull() ?: 0 },
            { it.split(".").getOrNull(1)?.toIntOrNull() ?: 0 },
            { it.split(".").getOrNull(2)?.toIntOrNull() ?: 0 },
        )
    )
}

android {
    namespace = "app.dugy"
    compileSdk = 35
    // أداة NDK مطلوبة لتنحيف مكتبات libsignal (بدونها يصل حجم التطبيق إلى أكثر من 1 غيغابايت).
    // نستخدم أحدث نسخة مثبّتة على جهاز البناء بدل نسخة ثابتة قد لا تكون موجودة.
    installedNdkVersion()?.let { ndkVersion = it }

    defaultConfig {
        applicationId = "app.dugy.messenger"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.0"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")

        // معالجات الهواتف فقط؛ نسخ المحاكيات (x86) تضاعف الحجم بلا فائدة.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // مفتاح توقيع ثابت محفوظ في المستودع، حتى تُثبَّت كل نسخة جديدة فوق السابقة دون حذفها.
    // تنبيه: يجب أن يبقى المستودع خاصًا، فمن يملك هذا المفتاح يستطيع توقيع نسخة باسم التطبيق.
    signingConfigs {
        create("dugy") {
            storeFile = file("signing/dugy-signing.p12")
            storeType = "PKCS12"
            storePassword = "2J7sihVv8QRKWXRlNrBQ-193"
            keyAlias = "dugy"
            keyPassword = "2J7sihVv8QRKWXRlNrBQ-193"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dugy")
        }
        release {
            signingConfig = signingConfigs.getByName("dugy")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // مطلوب لمكتبة libsignal-android.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // مكتبات libsignal لأنظمة الحاسوب غير لازمة داخل تطبيق أندرويد.
        resources.excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll", "libsignal_jni*.so")
        // نسخة الاختبار من libsignal لا يحتاجها التطبيق، وحجمها وحدها أكبر من 130 ميغابايت لكل معالج.
        jniLibs.excludes += "**/libsignal_jni_testing.so"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.testLogging {
                events("passed", "failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // التشفير الطرفي: مكتبة Signal الرسمية (بروتوكول PQXDH + Double Ratchet). الترخيص AGPL-3.0.
    val libsignal = "0.106.1"
    implementation("org.signal:libsignal-android:$libsignal")
    implementation("org.signal:libsignal-client:$libsignal")

    // الاتصال بخادم الراسبيري.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")

    // لا توجد مكتبات تحليلات أو تقارير أعطال عمدًا (المتطلب 9 من معايير القبول).
}
