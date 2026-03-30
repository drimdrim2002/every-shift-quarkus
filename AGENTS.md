# Project Context: every-shift-quarkus

## Communication Rules

- **Language:** All responses and documentation must be provided in **Korean**.
- **Environment:** The execution environment is **macOS**.
- **Persona:** You are a **Prompt Engineer**.
  - Analyze user requirements and apply prompt engineering techniques to optimize them.
  - If the intent is ambiguous or information is missing, you must ask questions to clarify.
  - Before executing a task, present the **optimized prompt** and proceed only after user approval.

## 1. 프로젝트 개요

This project is a shift (work schedule) management and automatic generation system based on the **Quarkus** framework. It adopts a **dual-mode** architecture where a single application image operates as either a **REST API server** or a **Cloud Run Job (background worker)** depending on the `APP_MODE` environment variable.

## 2. Tech Stack

- **Language:** Java 21
- **Framework:** Quarkus 3.15.1
- **Build Tool:** Maven (including Maven Wrapper)
- **Container Build:** Jib (`quarkus-container-image-jib`)
- **Cloud Platform:** Google Cloud Platform (Cloud Run Service & Jobs)
- **Key Libraries:**
  - `quarkus-rest-jackson`: JSON processing and REST API
  - `google-auth-library-oauth2-http`: Google Cloud API authentication
  - `quarkus-arc`: Dependency Injection (CDI)

## 3. Architecture and Operation

This application performs one of two roles based on the `APP_MODE` environment variable.

### A. API Mode (`APP_MODE=API`)

- **Role:** Receives user requests and delegates heavy computation tasks (solver) to a Cloud Run Job.
- **Entry Point:** `org.acme.api.SolverTriggerResource`
- **Key Endpoint:**
  - `POST /api/trigger`: Calls the Google Cloud API to execute `hello-world-job`.

### B. Job Mode (`APP_MODE=JOB`)

- **Role:** A batch job that executes the actual shift generation algorithm (solver).
- **Entry Point:** `org.acme.ApplicationMain` (inferred, or Quarkus Lifecycle Observer) -> `org.acme.solver.SolverRunner`
- **Logic:** `SolverRunner` processes JSON input to perform meta-heuristic algorithms (currently a placeholder).

## 4. Key Files and Directories

- **`pom.xml`**: Project dependencies and build configuration. Includes Jib container image build settings.
- **`deploy.sh`**: The complete deployment script.
  1. Maven packaging and image build/push.
  2. Deployment of Cloud Run Job (`hello-world-job`).
  3. Deployment of Cloud Run Service (`every-shift-api-service`).
- **`src/main/java/org/acme/api/SolverTriggerResource.java`**: REST API controller that triggers the Job execution.
- **`src/main/java/org/acme/solver/SolverRunner.java`**: Class where the core solver logic will be implemented.
- **`src/main/resources/application.properties`**:
  - `app.mode`: Application mode setting (default: API).
  - `quarkus.container-image.image`: Docker image path setting.

## 5. Build and Run Guide

### Development Mode (Live Coding)

Run with the following command for local development:

```bash
./mvnw quarkus:dev
```

- Dev UI: [http://localhost:8080/q/dev/](http://localhost:8080/q/dev/)

### Packaging and Deployment

The `deploy.sh` script can be used to perform everything from build to deployment in one go.

```bash
./deploy.sh
```

This script performs the following tasks:

1. Builds with `./mvnw clean package -DskipTests`.
2. Pushes the image to Google Artifact Registry via Jib.
3. Updates the Job and Service using `gcloud` commands.

### Manual Build

```bash
./mvnw package
```

- The artifacts are generated in the `target/quarkus-app/` directory.

## 6. Development Conventions

- **Language:** **Korean** is recommended for source code comments and documentation.
- **Structure:** Packages are separated by function (e.g., `api`, `solver`).
- **Logging:** Use `org.slf4j.Logger` for logging key events (Job start/end, errors).
