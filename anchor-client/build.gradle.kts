plugins {
    `java-library`
}

val okhttpVersion: String by project
val jacksonVersion: String by project

dependencies {
    api(project(":anchor-protocol"))

    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.squareup.okhttp3:okhttp-sse:$okhttpVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
}
