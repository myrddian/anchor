plugins {
    `java-library`
}

val jacksonVersion: String by project

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
}
