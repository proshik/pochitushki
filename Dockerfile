# Build stage
# Must match the Java toolchain in build.gradle.kts (25): Gradle has no toolchain download
# repository configured, so a mismatched JDK here fails with "Cannot find a Java installation".
FROM eclipse-temurin:25-jdk-jammy AS build

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

RUN ./gradlew dependencies

COPY src src

RUN ./gradlew bootJar

# Run stage
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

# Extract the fat JAR so classpath works correctly at runtime (required for Playwright native driver)
RUN java -Djarmode=tools -jar app.jar extract --destination /app/extracted && rm app.jar

# Install fonts (used by the OpenHTMLToPDF engine). Chromium/Playwright is NOT installed by default
# since PDF generation runs on the pure-JVM OpenHTMLToPDF engine (pdf.playwright.enabled=false).
# To include Playwright again: docker build --build-arg INSTALL_PLAYWRIGHT=true
ARG INSTALL_PLAYWRIGHT=false
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright
RUN apt-get update && \
    apt-get install -y --no-install-recommends fonts-dejavu-core && \
    if [ "$INSTALL_PLAYWRIGHT" = "true" ]; then \
      java -cp "/app/extracted/lib/*" com.microsoft.playwright.CLI install --with-deps chromium; \
    fi && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*

RUN addgroup --system spring && adduser --system --ingroup spring spring && \
    if [ -d "$PLAYWRIGHT_BROWSERS_PATH" ]; then chown -R spring:spring "$PLAYWRIGHT_BROWSERS_PATH"; fi

USER spring:spring

# JVM memory tuning for a low-traffic bot:
#  - SerialGC: smallest footprint (no parallel GC threads / heap reservations like G1)
#  - Xmx256m: explicit max heap — predictable, works with or without a container memory limit
#  - MaxMetaspaceSize: cap class-metadata growth
#  - ExitOnOutOfMemoryError: fail fast so the orchestrator restarts instead of limping
# Total RSS ≈ heap + metaspace + thread stacks + code cache (~150m overhead). Tune -Xmx after measuring.
# Override at runtime: `docker run -e JAVA_TOOL_OPTIONS="..."`
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/extracted/app.jar"]
