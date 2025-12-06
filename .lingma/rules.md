# Project Context: every-shift-quarkus

## Communication Rules
- All responses and documentation should be provided in Korean (한국어).

## Project Overview
This is a Quarkus-based Java application named "every-shift-quarkus". It's a RESTful web service built using Quarkus, the "Supersonic Subatomic Java Framework". The project appears to be a starter template that has been customized for shift management functionality, though currently it contains only the default Quarkus greeting endpoint.
**Project Details:**
- **Framework:** Quarkus 3.26.2
- **Language:** Java 21
- **Build Tool:** Maven
- **Architecture:** REST API
- **Purpose:** Shift management system (in development)
## Folder Structure
```
every-shift-quarkus/
├── .dockerignore
├── .gitignore
├── mvnw (Maven wrapper)
├── mvnw.cmd (Windows Maven wrapper)
├── pom.xml (Maven configuration)
├── README.md (Project documentation)
├── .mvn/ (Maven wrapper files)
├── src/
│   ├── main/
│   │   ├── docker/ (Docker configuration files)
│   │   ├── java/ (Java source code)
│   │   │   └── org/acme/
│   │   │       └── GreetingResource.java
│   │   └── resources/ (Configuration files)
│   └── test/ (Test files)
│       └── java/
│           └── org/acme/
│               ├── GreetingResourceTest.java (Unit tests)
│               └── GreetingResourceIT.java (Integration tests)
└── target/ (Build output)
```

## Building and Running
### Prerequisites
- Java 21+
- Maven (or use the provided Maven wrapper `./mvnw`)
### Development Mode
```bash
./mvnw compile quarkus:dev
```
This enables live coding with automatic reloads. The Dev UI is available at http://localhost:8080/q/dev/
### Packaging
```bash
# Standard packaging (not an über-jar)
./mvnw package
# Build an über-jar
./mvnw package -Dquarkus.package.jar.type=uber-jar
```
### Running the Application
```bash
# Run the standard package
java -jar target/quarkus-app/quarkus-run.jar

# Run the über-jar
java -jar target/*-runner.jar
```
### Native Compilation
```bash
# Build native executable
./mvnw package -Dnative

# Build native executable in container (if GraalVM not installed)
./mvnw package -Dnative -Dquarkus.native.container-build=true

# Run native executable
./target/every-shift-quarkus-1.0.0-SNAPSHOT-runner
```

### Testing
```bash
# Run unit tests
./mvnw test

# Run integration tests
./mvnw verify

# Or run both with a single command
./mvnw clean install
```

## Docker Configuration
Multiple Dockerfiles are provided for different deployment strategies:
- `Dockerfile.jvm` - JVM-based deployment
- `Dockerfile.legacy-jar` - Legacy JAR deployment
- `Dockerfile.native` - Native executable deployment
- `Dockerfile.native-micro` - Minimal native executable deployment

## Development Conventions

### Code Structure
- Java source files follow the package structure: `org.acme`
- REST endpoints use JAX-RS annotations
- Tests are located in `src/test/java` with similar package structure

### Testing
- Unit tests use `@QuarkusTest` annotation
- Integration tests use `@QuarkusIntegrationTest` annotation
- Tests follow standard JUnit 5 patterns
- REST API testing uses RestAssured framework

### Configuration
- Application configuration stored in `application.properties`
- Profile-specific configurations can be managed through Maven profiles
- Environment-specific configuration can be externalized
