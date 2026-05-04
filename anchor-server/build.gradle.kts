plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

val mapstructVersion: String by project
val pgvectorJdbcVersion: String by project
val pdfboxVersion: String by project
val tikaVersion: String by project
val okhttpVersion: String by project
val jacksonVersion: String by project
val flywayVersion: String by project
val testcontainersVersion: String by project
val springdocVersion: String by project

dependencies {
    implementation(project(":anchor-protocol"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    // OTLP exporter — sends spans to any OTel collector (Tempo, Jaeger,
    // Honeycomb, Datadog, …). Default endpoint is localhost:4318 over HTTP;
    // when nothing's listening the exporter logs and drops, doesn't crash.
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // Prometheus registry — turns Micrometer counters/timers into the
    // /actuator/prometheus text format that prom servers scrape.
    implementation("io.micrometer:micrometer-registry-prometheus")

    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    implementation("org.postgresql:postgresql")
    implementation("com.pgvector:pgvector:$pgvectorJdbcVersion")

    implementation("org.apache.pdfbox:pdfbox:$pdfboxVersion")

    // Tika handles every file format that isn't PDF (EPUB, DOCX, RTF, HTML,
    // plain text, …). Standard-package pulls the per-format parsers in.
    implementation("org.apache.tika:tika-core:$tikaVersion")
    implementation("org.apache.tika:tika-parsers-standard-package:$tikaVersion")

    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.squareup.okhttp3:okhttp-sse:$okhttpVersion")

    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    implementation("org.mapstruct:mapstruct:$mapstructVersion")
    annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")

    // OpenAPI 3 spec generation + Swagger UI. Auto-discovers REST controllers
    // and DTO records. Spec at /v3/api-docs, UI at /swagger-ui/index.html.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
}
