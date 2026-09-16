plugins {
    alias(libs.plugins.hilt) apply (false)
    alias(libs.plugins.kotlin.ksp) apply (false)
    alias(libs.plugins.kotlin.serialization) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
    dependencies {
        classpath(libs.gradle)
        classpath(kotlin("gradle-plugin", libs.versions.kotlin.get()))
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            if (project.findProperty("enableComposeCompilerReports") == "true") {
                arrayOf("reports", "metrics").forEach {
                    freeCompilerArgs.add("-P")
                    freeCompilerArgs.add("plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=${project.layout.buildDirectory}/compose_metrics")
                }
            }
        }
    }

    // Hilt 2.59.2 bundles a kotlin-metadata-jvm that only reads metadata format up to 2.3.0,
    // but Kotlin 2.3.21 (this project's version) emits format 2.4.0 - hiltJavaCompile*
    // fails with "Provided Metadata instance has version 2.4.0, while maximum supported
    // version is 2.3.0" on every module. This is a known, currently-unresolved upstream gap
    // (github.com/google/dagger/issues/5190, same Hilt version, same error) - nothing to do
    // with this project's own code. Since Dagger 2.57 unshaded kotlin-metadata-jvm
    // specifically so this can be overridden without waiting for a Dagger release, force it
    // here to a version that understands 2.4.0.
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
        }
    }
}
