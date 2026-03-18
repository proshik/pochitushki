# Build stage
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

RUN ./gradlew dependencies

COPY src src

RUN ./gradlew bootJar

# Run stage
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar app.jar

# Install Playwright Chromium browser and OS dependencies (needed when pdf.engine=playwright).
# To skip: docker build --build-arg INSTALL_PLAYWRIGHT=false
ARG INSTALL_PLAYWRIGHT=true
RUN if [ "$INSTALL_PLAYWRIGHT" = "true" ]; then \
      java -cp app.jar com.microsoft.playwright.CLI install --with-deps chromium; \
    fi

RUN addgroup --system spring && adduser --system --ingroup spring spring

USER spring:spring

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
