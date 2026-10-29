// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Плагин Firebase: читает app/google-services.json (push-уведомления)
    id("com.google.gms.google-services") version "4.4.2" apply false
}