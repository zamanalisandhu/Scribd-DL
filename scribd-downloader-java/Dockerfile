# Stage 1: Build Java Fat JAR
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime Image with Official Google Chrome & Fonts
FROM eclipse-temurin:17-jre
WORKDIR /app

# Install dependencies, Google Chrome Stable & essential fonts for 1:1 Vector rendering
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    gnupg \
    ca-certificates \
    fonts-liberation \
    fonts-dejavu-core \
    fonts-freefont-ttf \
    && wget -q -O - https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /etc/apt/trusted.gpg.d/google.gpg \
    && echo "deb [arch=amd64] http://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends google-chrome-stable \
    && rm -rf /var/lib/apt/lists/*

ENV PORT=8080
EXPOSE 8080

RUN mkdir -p cache

COPY --from=build /app/target/scribd-downloader-*.jar app.jar

# Limit Java memory to ~220MB heap with SerialGC so that Chrome has 250MB+ headroom within Render 512MB RAM
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-Xms64m", "-Xmx220m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=96m", "-Xss256k", "-jar", "app.jar"]
