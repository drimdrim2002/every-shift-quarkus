# Project Context: every-shift-quarkus

## Communication Rules
- All responses and documentation should be provided in Korean (한국어).

## Project Overview
This is a Quarkus-based Java application named "every-shift-quarkus". It's a RESTful web service built using Quarkus, the "Supersonic Subatomic Java Framework". The project appears to be a starter template that has been customized for shift management functionality with optimization solvers, likely for scheduling or assignment problems. The application can run in either API mode (serving REST endpoints) or JOB mode (executing optimization algorithms).

**Project Details:**
- **Framework:** Quarkus 3.15.1
- **Language:** Java 21
- **Build Tool:** Maven
- **Architecture:** REST API + Cloud Run Job for optimization tasks
- **Purpose:** Shift scheduling optimization system with Google Cloud Run integration

## Folder Structure
```
every-shift-quarkus/
├── .dockerignore
├── .geminiignore
├── .gitignore
├── deploy.sh
├── GEMINI.md
├── mvnw (Maven wrapper)
├── mvnw.cmd (Windows Maven wrapper)
├── pom.xml (Maven configuration)
├── QWEN.md (Project documentation)
├── README.md (Project documentation)
├── .git/...
├── .idea/...
├── .lingma/
│   └── rules.md
├── .mvn/
│   └── wrapper/
├── src/
│   ├── main/
│   │   ├── docker/ (Docker configuration files)
│   │   ├── java/ (Java source code)
│   │   │   └── org/acme/
│   │   │       ├── ApplicationMain.java
│   │   │       ├── api/ (REST API resources)
│   │   │       └── solver/ (Optimization solver logic)
│   │   └── resources/ (Configuration files)
│   └── test/ (Test files)
└── target/ (Build output)
```

## Building and Running
### Prerequisites
- Java 21+
- Maven (or use the provided Maven wrapper `./mvnw`)
- Google Cloud SDK (for deployment)

### Application Modes
The application supports two execution modes controlled by the `APP_MODE` environment variable:
- `API` (default): Runs as a REST service
- `JOB`: Executes optimization algorithms

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
# Run in API mode (default)
java -jar target/quarkus-app/quarkus-run.jar

# Run in JOB mode
APP_MODE=JOB java -jar target/quarkus-app/quarkus-run.jar

# Run with Base64 encoded input data for JOB mode
APP_MODE=JOB java -jar target/quarkus-app/quarkus-run.jar --input-data <BASE64_ENCODED_JSON>
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

## Architecture

### ApplicationMain
The main application entry point that decides between API mode and JOB mode based on environment variables.

### API Resources
Located in `org.acme.api`:
- `SolverTriggerResource`: Triggers Cloud Run Jobs via Google Cloud API
- `EveryShiftSolverTrigger`: Handles POST requests with JSON payload, encodes it as Base64, and passes to Cloud Run Jobs

### Solver Components
Located in `org.acme.solver`:
- `SolverRunner`: Processes JSON input and executes optimization algorithms
- `algorithme/`: Placeholder for optimization algorithms (currently empty)
- `model/`: Placeholder for optimization models (currently empty)

### Google Cloud Integration
- Deploys to Google Cloud Run as both service (API) and job (optimization)
- Uses Google Cloud authentication for API calls
- Container images pushed to Google Cloud Artifact Registry

## Deployment
Use the provided `deploy.sh` script to deploy the application to Google Cloud:

```bash
./deploy.sh
```

This script:
1. Builds and pushes the container image
2. Deploys the Cloud Run Job (solver) with configuration for optimization tasks
3. Deploys the Cloud Run Service (API) for REST endpoint access

## Development Conventions

### Code Structure
- Java source files follow the package structure: `org.acme`
- REST endpoints use JAX-RS annotations
- Tests are located in `src/test/java` with similar package structure

### Optimization Logic
- The solver expects JSON input with "organization" and "employees" fields
- The algorithm implementation is expected to be added to the `algorithme` package
- The data model for the solver is expected to be defined in the `model` package

### Configuration
- Application configuration stored in `application.properties`
- Google Cloud project configuration in the same file
- Environment-specific configurations can be managed through environment variables

### Testing
- Unit tests use `@QuarkusTest` annotation
- Integration tests use `@QuarkusIntegrationTest` annotation
- Tests follow standard JUnit 5 patterns
- REST API testing uses RestAssured framework