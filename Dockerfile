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

# Heap is capped well below the container limit on purpose. A JVM needs metaspace,
# code cache, thread stacks and native buffers (Tomcat, the JDBC driver, image
# decoding) on top of the heap; at 75% those extras push total RSS past the limit
# and the kernel kills the process with no Java exception and no shutdown log,
# which reads as an unexplained restart and 502s. Metaspace is bounded for the
# same reason, and a genuine heap exhaustion now exits loudly instead of thrashing.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=65.0", \
  "-XX:MaxMetaspaceSize=256m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]
