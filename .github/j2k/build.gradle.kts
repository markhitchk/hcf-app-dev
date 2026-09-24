plugins {
    kotlin("jvm") version "1.3.31"
    id("org.jetbrains.intellij") version "0.4.8"
    application
}

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib"))
}

intellij {
    version = "191.5109.14"
    setPlugins("Kotlin")
    downloadSources = false
}

configurations {
    runtimeClasspath {
        extendsFrom(idea.get())
    }
}

application {
    mainClassName = "me.eugeniomarletti.ConvertJavaFilesToKotlinKt"
}
