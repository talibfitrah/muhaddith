import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// كلمتا سر التوقيع في keystore.properties بجذر المشروع (خارج git؛ انظر keystore.properties.example)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "org.murabbie.muhaddith"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.murabbie.muhaddith"
        minSdk = 26
        targetSdk = 36
        versionCode = 32
        versionName = "0.15.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("muhaddith-release.jks")
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = "muhaddith"
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // نكهتان: nas (توزيع مباشر: تحديث ذاتي بـ APK + وصول لكل الملفات) وplay (متجر Google Play: لا تثبيت APK من داخل التطبيق ولا MANAGE_EXTERNAL_STORAGE — كلاهما محظور/مقيَّد في سياسات المتجر)
    flavorDimensions += "dist"
    productFlavors {
        create("nas") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "ALL_FILES_ACCESS", "false")
        }
    }
    androidResources {
        noCompress += listOf("db", "zip")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // ضغط المكتبات الأصلية داخل الحزمة لتصغير ملف APK (تُفكّ عند التثبيت)
        jniLibs { useLegacyPackaging = true }
    }

    // حزمة مستقلة لكل معمارية لتصغير الحجم (arm64 لأغلب الهواتف الحديثة)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // البحث الدلالي على الهاتف: تشغيل نموذج التضمين ONNX
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    // محرّك SQLite مدمج يضمن FTS5 على كل الأجهزة بدل الاعتماد على نسخة النظام
    implementation("com.github.requery:sqlite-android:3.49.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation("junit:junit:4.13.2")
}
