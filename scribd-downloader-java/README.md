# Scribd Downloader Pro (Java / Spring Boot 3)

High-Speed, Non-Corrupting Scribd Document Downloader & Vector Assembler built with **Java 17+** and **Spring Boot 3**.

---

## 🚀 Features

- **Spring Boot 3 REST API**: Fast, production-grade architecture.
- **24-Thread Parallel Downloader**: Uses `CompletableFuture` and `HttpClient` to download document pages simultaneously at maximum network speed.
- **Multi-Level Document Assembly Engine**:
  1. **Direct Session Download**: Instant high-speed fetch if Scribd session cookie is provided.
  2. **Headless Chrome 1:1 Vector Render**: Chrome/Edge headless rendering preserving authentic fonts, math equations, and layout.
  3. **High-Res Parallel Image Stitcher**: High-fidelity image assembly using **Apache PDFBox** & **TwelveMonkeys ImageIO (WebP support)**.
  4. **Dynamic Canvas Text Generator**: Fallback rendering for text/essay documents.
- **Formats Supported**: PDF, DOCX (Word XML), and TXT.
- **Embedded Web UI**: Serves modern responsive UI right from `src/main/resources/static/index.html`.
- **Live Progress API**: Real-time download percentage and stage updates via `/api/progress`.

---

## 🛠 Prerequisites

- **Java JDK 17** or higher (Eclipse Temurin, Oracle JDK, or Microsoft OpenJDK).
- **Maven 3.8+** (or run via IntelliJ / VS Code / Eclipse).
- *(Optional)* Google Chrome or Microsoft Edge installed on the host for 1:1 vector PDF printing.

---

## 💻 How to Run

### 1. Run via Maven
```bash
cd scribd-downloader-java
mvn spring-boot:run
```

The application will start on `http://localhost:8080`.

### 2. Package into a Standalone Fat JAR
```bash
cd scribd-downloader-java
mvn clean package -DskipTests
java -jar target/scribd-downloader-2.0.0.jar
```

### 3. Run with Docker
```bash
cd scribd-downloader-java
docker build -t scribd-downloader-java .
docker run -p 8080:8080 scribd-downloader-java
```

---

## 📡 API Endpoints

- `GET /`: Serves the Web UI.
- `GET /api/scribd?url=<url>&cookie=<optional>`: Returns parsed document metadata (page count, images, author, title).
- `GET /api/progress?doc_id=<doc_id>`: Returns live download progress JSON.
- `GET /api/download?url=<url>&format=pdf&cookie=<optional>`: Assembles and streams the complete file.
