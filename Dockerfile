FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=builder /app/build/libs/oms-backend-*.jar app.jar

EXPOSE 8080

# Heap is capped below the container limit because a JVM also needs metaspace,
# code cache, thread stacks and native buffers (Tomcat, the JDBC driver, image
# decoding) on top of the heap; at 75% those extras push total RSS past the
# limit and the kernel kills the process with no Java exception and no shutdown
# log, which reads as an unexplained restart and 502s.
#
# Metaspace is deliberately NOT capped. Spring Boot with Hibernate and CGLIB
# proxies loads classes heavily during startup and while the startup runners
# execute; a cap that is even slightly too low turns into an OutOfMemoryError at
# exactly that moment. Paired with ExitOnOutOfMemoryError that becomes an
# instant, silent exit — indistinguishable from an external kill — so neither
# flag is used here.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/app/app.jar"]
