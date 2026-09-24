plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Needed only so `reductxn.spring-conventions` can apply the Spring Boot plugin.
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.1")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}
