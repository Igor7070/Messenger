plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.unlimitedpossibilities12"
    compileSdk = 34

    defaultConfig {
        // Имя приложения в системе и в Google Play — по домену проекта.
        // namespace (пакеты Java) остаётся прежним: он на публикацию не влияет.
        applicationId = "org.unlimitedpossibilities12.messenger"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.0")
    implementation("com.google.android.gms:play-services-auth:19.2.0")
    implementation("com.github.bumptech.glide:glide:4.12.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.infobip:google-webrtc:1.0.43591")
    implementation("com.github.NaikSoftware:StompProtocolAndroid:1.6.6")
    implementation("com.squareup.okhttp3:okhttp:4.9.0")
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.9.0")
    implementation("com.goterl:lazysodium-android:5.0.2@aar")
    implementation("net.java.dev.jna:jna:5.8.0@aar")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.core:core:1.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.google.android.gms:play-services-nearby:19.0.0")
    // Локальный перевод для секретных чатов: модели на устройстве, текст не покидает телефон
    implementation("com.google.mlkit:translate:17.0.3")
    // Push-уведомления: сообщение будит приложение, даже когда оно закрыто
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.mlkit:language-id:17.0.6") // определение языка текста на устройстве
    // Распознавание речи на устройстве: в отличие от системного, принимает готовый
    // поток звука (не спорит за микрофон со звонком) и работает с моделью, которую
    // скачивает само приложение — пользователю не нужно искать её в настройках
    implementation("com.alphacephei:vosk-android:0.3.47")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}