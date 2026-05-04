plugins {
    java
}

allprojects {
    group = "io.aeyer"
    version = "0.1.0-SNAPSHOT"
}

/**
 * Read the project-root .env into a Map<String,String>. Values already set in
 * the parent process environment win — .env is the file-of-record but a one-off
 * `LLM_BASE_URL=… ./gradlew :anchor-server:bootRun` should still
 * override. Comments (#) and blank lines ignored. Quotes around values are
 * stripped so `KEY="value with spaces"` works.
 */
val dotEnv: Map<String, String> = run {
    val file = rootProject.file(".env")
    if (!file.exists()) emptyMap() else file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else {
                val key = line.substring(0, idx).trim()
                var value = line.substring(idx + 1).trim()
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length - 1)
                }
                key to value
            }
        }
        .toMap()
}

/**
 * Apply .env entries to a task's environment without overriding parent-process
 * env or anything already set on the task — caller intent (export VAR=…) wins.
 */
fun org.gradle.process.ProcessForkOptions.applyDotEnv() {
    dotEnv.forEach { (k, v) ->
        if (System.getenv(k) == null && environment[k] == null) {
            environment(k, v)
        }
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing", "-Xlint:-serial", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
        applyDotEnv()
    }

    // Spring Boot's BootRun is a JavaExec task; wire .env into every JavaExec
    // (covers bootRun, run, application-plugin run, etc.) so a fresh
    // `./gradlew :anchor-server:bootRun` reads LLM_* / ANCHOR_DB_* from
    // .env without the operator having to source it first.
    tasks.withType<JavaExec>().configureEach {
        applyDotEnv()
    }
}
