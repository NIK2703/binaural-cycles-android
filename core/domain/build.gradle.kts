plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)
    
    // Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // DateTime
    implementation(libs.kotlinx.datetime)
    
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}