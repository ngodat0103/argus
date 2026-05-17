plugins {
    java
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.datrollout"
version = "0.0.1-SNAPSHOT"
description = "argus"
val embabelAgentVersion = "0.3.5"
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
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

dependencies {
    // Embabel
    implementation("com.embabel.agent:embabel-agent-starter-deepseek:${embabelAgentVersion}")

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.fabric8:kubernetes-client:7.7.0")
    implementation("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
