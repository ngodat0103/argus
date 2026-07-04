plugins {
    java
    id("org.springframework.boot") version "3.5.15"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.sonarqube") version "7.3.0.8198"
    id("com.diffplug.spotless") version "8.5.1"
}

group = "dev.datrollout"
version = "0.3.0"
description = "argus"
val embabelAgentVersion = "0.5.0"
val jjwtVersion = "0.13.0"
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}
sonar {
    properties {
        property("sonar.projectKey", "Argus")
        property("sonar.projectName", "Argus")
        property("sonar.host.url", "https://sonarqube.datrollout.dev")
    }
}
spotless {
    java { palantirJavaFormat() }
}
tasks.named("compileJava") {
    dependsOn(tasks.named("spotlessApply"))
}
repositories {
    mavenCentral()
    maven {
        name = "embabel-releases"
        url = uri("https://repo.embabel.com/artifactory/libs-release")
        mavenContent {
            releasesOnly()
        }
    }
    maven {
        name = "Spring Milestones"
        url = uri("https://repo.spring.io/milestone")
    }
}
configurations.all {
    resolutionStrategy {
        force("io.modelcontextprotocol.sdk:mcp:0.17.2")
    }
}


dependencies {
    // Embabel
    implementation("com.embabel.agent:embabel-agent-starter-deepseek:${embabelAgentVersion}")


    // chat
    implementation("org.telegram:telegrambots-springboot-longpolling-starter:9.6.0")
    implementation("org.telegram:telegrambots-client:9.5.0")

    // TOTP two-factor authentication
    implementation("dev.samstevens.totp:totp:1.7.1")


    // Persistence
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    //web
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Metrics
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Kubernetes API library
    implementation("io.fabric8:kubernetes-client:7.3.2")

    // Github Integration
    implementation("org.kohsuke:github-api:1.330")

    // JWT signing (JJWT)
    implementation("io.jsonwebtoken:jjwt-api:${jjwtVersion}")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:${jjwtVersion}")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:${jjwtVersion}")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Utils
    implementation("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
