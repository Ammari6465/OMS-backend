FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine

# Java2D needs these natively, and this image does not carry them. Floor plan
# recognition rasterises SVG plans through Batik, which drives Java2D; without
# fontconfig and freetype on musl the JVM does not throw, it dies — SIGSEGV,
# no Java exception, no shutdown log, which reads as an unexplained restart
# and 502s mid-scan. A font must be present too, because Batik measures text
# while building the render tree even when the drawing has no visible labels.
RUN apk add --no-cache fontconfig freetype ttf-dejavu

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
# Lowered from 65% because floor plan recognition renders images. Batik and
# Java2D allocate native buffers that sit outside the heap and outside
# MaxRAMPercentage's reckoning, so a heap sized to 65% left too little of the
# container for them: RSS crossed the limit mid-scan and the kernel killed the
# process. At 50% the heap fills first, which raises a catchable
# OutOfMemoryError and fails that one request instead of taking the app down
# for everyone.
# Headless is stated rather than assumed: Java2D reaching for a display in a
# container is another way rasterising fails at runtime instead of at boot.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50.0", "-Djava.awt.headless=true", "-jar", "/app/app.jar"]
