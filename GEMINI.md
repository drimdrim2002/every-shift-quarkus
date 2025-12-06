# Project Context: every-shift-quarkus

## Communication Rules
- **Language:** All responses and documentation should be provided in **Korean (한국어)**, as specified in `QWEN.md`.

## Overview
`every-shift-quarkus` is a Java application built with the **Quarkus** framework ("Supersonic Subatomic Java"). It is configured as a RESTful web service. The project is currently in a starter state (based on the default Quarkus `getting-started` archetype) but aims to be a shift management system.

**Tech Stack:**
*   **Language:** Java 21
*   **Framework:** Quarkus 3.26.2
*   **Build Tool:** Maven (Wrapper included: `./mvnw`)
*   **Containerization:** Docker (files in `src/main/docker/`)

## Key Files & Directories
*   `pom.xml`: Project dependencies and build configuration. Key dependencies include `quarkus-arc`, `quarkus-rest`, and `quarkus-junit5`.
*   `src/main/java/org/acme/GreetingResource.java`: Sample REST resource file showing JAX-RS usage (`@Path`, `@GET`).
*   `src/test/java/org/acme/`: Integration (`GreetingResourceIT.java`) and unit tests (`GreetingResourceTest.java`).
*   `QWEN.md` & `.lingma/rules.md`: Project-specific context files. **Note:** `QWEN.md` specifies a preference for Korean language in documentation and responses.
*   `src/main/docker/`: Dockerfiles for JVM, Native, and Legacy JAR builds.

## Building and Running

### Development Mode
Run the application in dev mode (enables live coding):
```bash
./mvnw compile quarkus:dev
```
*   **Dev UI:** [http://localhost:8080/q/dev/](http://localhost:8080/q/dev/)
*   **App Endpoint:** [http://localhost:8080/hello](http://localhost:8080/hello)

### Packaging
Package the application:
```bash
./mvnw package
```
*   Artifacts are produced in `target/quarkus-app/`.
*   Run with: `java -jar target/quarkus-app/quarkus-run.jar`

### Native Build
Create a native executable (requires GraalVM or Docker):
```bash
./mvnw package -Dnative
```

### Testing
Run the test suite:
```bash
./mvnw test
```

## Development Conventions
*   **Package Structure:** `org.acme` (Default, likely to change as project evolves).
*   **REST API:** Uses Jakarta REST (JAX-RS) annotations (`@Path`, `@GET`, `@Produces`).
*   **Testing:** Uses `@QuarkusTest` for integration testing and RestAssured for API verification.
