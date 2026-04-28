plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

val springShellVersion: String by project

dependencies {
    implementation(project(":anchor-client"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.shell:spring-shell-starter:$springShellVersion")
}
