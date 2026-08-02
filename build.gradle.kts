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
    implementation("io.lettuce:lettuce-core:7.6.0.RELEASE")

    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(
        "com.velocitypowered:velocity-api:3.5.0-SNAPSHOT"
    )
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    test {
        useJUnitPlatform()
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName.set("TheosferaProxy")
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        relocate(
            "com.google.gson",
            "com.theosfera.proxy.libs.gson"
        )
        relocate(
            "io.lettuce",
            "com.theosfera.proxy.libs.lettuce"
        )
        relocate(
            "io.netty",
            "com.theosfera.proxy.libs.netty"
        )
        relocate(
            "reactor",
            "com.theosfera.proxy.libs.reactor"
        )
        relocate(
            "org.reactivestreams",
            "com.theosfera.proxy.libs.reactivestreams"
        )
        relocate(
            "redis.clients.authentication",
            "com.theosfera.proxy.libs.redisauth"
        )

        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        mergeServiceFiles()

        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }

    build {
        dependsOn(shadowJar)
    }
}
