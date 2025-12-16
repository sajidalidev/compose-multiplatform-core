import androidx.build.AndroidXComposePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinNativeBinaryContainer
import org.jetbrains.androidx.build.JetBrainsAndroidXPlugin

plugins {
    id("AndroidXPlugin")
    id("AndroidXComposePlugin")
    id("kotlin-multiplatform")
    id("JetBrainsAndroidXPlugin")
}

AndroidXComposePlugin.applyAndConfigureKotlinPlugin(project)
JetBrainsAndroidXPlugin.applyAndConfigure(project)

repositories {
    mavenLocal()
}

kotlin {
    val isArm64Host = System.getProperty("os.arch") == "aarch64"
    tvosArm64 {
        binaries {
            framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }
    if (isArm64Host) {
        tvosSimulatorArm64 {
            binaries {
                framework {
                    baseName = "ComposeApp"
                    isStatic = true
                }
            }
        }
    } else {
        tvosX64 {
            binaries {
                framework {
                    baseName = "ComposeApp"
                    isStatic = true
                }
            }
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":compose:foundation:foundation"))
                implementation(project(":compose:foundation:foundation-layout"))
                implementation(project(":compose:material3:material3"))
                implementation(project(":compose:runtime:runtime"))
                implementation(project(":compose:mpp"))
                implementation(project(":compose:ui:ui"))
                implementation(project(":compose:ui:ui-graphics"))
                implementation(project(":compose:ui:ui-text"))
                implementation(libs.kotlinCoroutinesCore)
            }
        }
        val skikoMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.skikoCommon)
            }
        }
        val nativeMain by creating { dependsOn(skikoMain) }
        val darwinMain by creating { dependsOn(nativeMain) }
        val tvosMain by creating { dependsOn(darwinMain) }
        val tvosArm64Main by getting { dependsOn(tvosMain) }
        if (isArm64Host) {
            val tvosSimulatorArm64Main by getting { dependsOn(tvosMain) }
        } else {
            val tvosX64Main by getting { dependsOn(tvosMain) }
        }
    }
}
