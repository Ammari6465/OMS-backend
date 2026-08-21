plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.spring") version "1.9.23"
    kotlin("plugin.jpa") version "1.9.23"
}

group = "com.sunrich"
version = "1.0.0"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
}

dependencies {
    // Core Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // JWT (matches existing custom auth pattern)
    implementation("io.jsonwebtoken:jjwt-api:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")

    // Renders SVG plans to raster so a vision engine can read them. Vector
    // plans are common and vision models take images only, so without this an
    // SVG upload has no path to detection at all.
    // xml-apis ships an ancient javax.xml that shadows the JDK's own and
    // removes XMLConstants.ACCESS_EXTERNAL_DTD, which the plan sanitiser relies
    // on to lock down SVG parsing. Batik works fine against the JDK classes.
    implementation("org.apache.xmlgraphics:batik-transcoder:1.17") {
        // Only the xml-apis module itself shadows the JDK; xml-apis-ext carries
        // the SVG DOM interfaces Batik genuinely needs, so it has to stay.
        exclude(group = "xml-apis", module = "xml-apis")
    }
    implementation("org.apache.xmlgraphics:batik-codec:1.17") {
        // Only the xml-apis module itself shadows the JDK; xml-apis-ext carries
        // the SVG DOM interfaces Batik genuinely needs, so it has to stay.
        exclude(group = "xml-apis", module = "xml-apis")
    }

    // Integrations used in later phases (FTP, CSV, PDF export)
    implementation("commons-net:commons-net:3.10.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.43.0")
    implementation("org.eclipse.angus:jakarta.mail")
    implementation("com.opencsv:opencsv:5.9")
    implementation("com.github.librepdf:openpdf:1.3.35")

    // Database
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("com.h2database:h2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
