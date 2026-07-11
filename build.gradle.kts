plugins {
    java
    id("com.gradleup.shadow") version "9.3.2"
}

group = "com.theosfera"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("com.theosfera:TheosferaProtocol:0.1.0-SNAPSHOT")

    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName.set("TheosferaProxy")
        archiveClassifier.set("")

        relocate(
            "com.google.gson",
            "com.theosfera.proxy.libs.gson"
        )

        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }

    build {
        dependsOn(shadowJar)
    }
}