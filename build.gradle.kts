plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

tasks.test {
    systemProperty("perinkpheral.sdk", file(
        (providers.gradleProperty("perinkpheralPath").orNull ?: "../perinkpheral-os") + "/sdk").absolutePath)
}

dependencies {
    testImplementation(kotlin("test-junit"))
    // The IDE has Kotlin's library of its own.
    implementation("dev.perinkpheral:perinkpheral-core:0.1.0") {
        exclude(group = "org.jetbrains.kotlin")
    }
    intellijPlatform {
        // -PclionPath=/Applications/CLion.app builds against an installed CLion and downloads nothing.
        val installed = providers.gradleProperty("clionPath").orNull
        if (installed != null) {
            local(installed)
        } else {
            clion(providers.gradleProperty("platformVersion").get())
        }
        bundledPlugin("com.intellij.modules.json")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
