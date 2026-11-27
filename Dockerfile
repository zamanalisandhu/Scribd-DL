# Stage 1: Build Java Fat JAR
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY scribd-downloader-java/pom.xml .
RUN mvn dependency:go-offline -B
COPY scribd-downloader-java/src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime Image with Chromium Headless support
FROM eclipse-temurin:17-jre
WORKDIR /app

# Install Chromium and fonts for 1:1 Vector PDF printing
RUN apt-get update && apt-get install -y --no-install-recommends \
    chromium \
    fonts-liberation \
    fonts-dejavu-core \
    fonts-freefont-ttf \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV PORT=8080
EXPOSE 8080

RUN mkdir -p cache

COPY --from=build /app/target/scribd-downloader-*.jar app.jar

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
