# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Quarkus-based employee shift scheduling application** using **OptaPlanner** for constraint-based optimization. The application operates in a **dual-mode architecture** where a single container image serves as either a REST API server or a Cloud Run Job worker based on the `APP_MODE` environment variable.

## Communication Rules
- **Language:** All responses and documentation must be provided in **Korean**.
- **Persona:** You are a **Prompt Engineer**.
    - Analyze user requirements and apply prompt engineering techniques to optimize them.
    - If the intent is ambiguous or information is missing, you must ask questions to clarify.
    - Before executing a task, present the **optimized prompt** and proceed only after user approval.

### Tech Stack
- Java 21, Quarkus 3.15.1, Maven with Maven Wrapper
- OptaPlanner 10.0.0 for constraint optimization
- Google Cloud Platform (Cloud Run Services & Jobs)
- H2 in-memory database (development)

## Build and Test Commands

### Development
```bash
# Development mode with live reload (Dev UI at http://localhost:8080/q/dev/)
./mvnw compile quarkus:dev

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=EmployeeSchedulingConstraintProviderTest

# Build package (produces target/quarkus-app/)
./mvnw package
```

### Deployment
```bash
# Full deployment to GCP (build, push image, deploy Job and Service)
./deploy.sh

# Manual build without tests
./mvnw clean package -DskipTests
```

## Dual-Mode Architecture

The application behavior is controlled by the `app.mode` property (set via `APP_MODE` environment variable):

### API Mode (`APP_MODE=API`, default)
- **Entry Point**: `org.acme.api.SolverTriggerResource`
- **Purpose**: Receives HTTP requests and delegates solver work to Cloud Run Jobs
- **Key Endpoint**: `POST /api/trigger`
- **Configuration**: Can run solver locally (`app.solver.run-locally=true`) or dispatch to GCP Cloud Tasks
- **Deployment**: Cloud Run Service (`every-shift-api-service`)

### Job Mode (`APP_MODE=JOB`)
- **Entry Point**: `org.acme.ApplicationMain` → `org.acme.solver.SolverRunner`
- **Purpose**: Batch processor that executes OptaPlanner solver
- **Deployment**: Cloud Run Job (`hello-world-job`)

## OptaPlanner Integration

### Domain Model Structure
- **`EmployeeSchedule`** (`@PlanningSolution`): Container holding the solution, planning entities, and problem facts
- **`Shift`** (`@PlanningEntity`): Planning entity with `employee` planning variable
- **`Employee`**, **`Availability`**: Problem facts (immutable data)
- **Scoring**: `HardSoftScore` - hard constraints must be satisfied, soft constraints optimize quality

### Constraint Definition
Location: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`

**Hard Constraints** (must satisfy):
- Required skill matching
- No overlapping shifts for same employee
- Minimum 12 hours between consecutive shifts
- Maximum one shift per day
- Employee availability restrictions (UNAVAILABLE)

**Soft Constraints** (prefer to satisfy):
- Desired day preferences (reward)
- Undesired day preferences (penalty)

### Solver Configuration
Located in `application.properties` under `solver.*`:
- **Prod**: 60s spent limit, 4 threads, reproducible with random seed 42
- **Dev profile**: 10s spent limit
- **Test profile**: 2s spent limit for quick test execution

## Package Structure

```
src/main/java/org/acme/
├── ApplicationMain.java          # Dual-mode entry point handler
├── api/                          # REST API layer (SolverTriggerResource)
├── model/                        # OptaPlanner domain models
│   ├── EmployeeSchedule.java     # @PlanningSolution
│   ├── Shift.java                # @PlanningEntity
│   ├── Employee.java             # Problem fact
│   └── Availability.java         # Employee preferences
├── solver/                       # Solver implementation
│   ├── SolverRunner.java         # Solver orchestration
│   ├── algorithm/
│   │   └── EmployeeSchedulingConstraintProvider.java
│   └── persistence/              # Data repositories
└── util/
    └── DtoConverter.java         # Request → Domain model conversion
```

## Key Configuration Files

- **`pom.xml`**: Maven dependencies (OptaPlanner, Quarkus, Google Cloud libraries)
- **`src/main/resources/application.properties`**: All application configuration
- **`deploy.sh`**: Complete GCP deployment script

### Important Properties
- `app.mode`: Controls dual-mode operation (API/JOB)
- `app.solver.run-locally`: If true, executes solver synchronously via WorkerResource instead of Cloud Tasks
- `solver.termination.*`: OptaPlanner timeout and behavior settings
- `quarkus.container-image.image`: Target container image path

## Development Conventions

- **Language**: Use Korean for code comments and documentation (as per AGENTS.md)
- **Testing**: Tests use JUnit 5 and OptaPlanner's `ConstraintVerifier` for constraint testing
- **Logging**: Use `org.slf4j.Logger` for key events (solver start/end, errors)

## Data Flow

1. **Request**: JSON received via REST API → `PlanningRequest` DTO
2. **Conversion**: `DtoConverter` transforms DTO to OptaPlanner domain model
3. **Solving**: `SolverRunner` orchestrates OptaPlanner solver execution
4. **Response**: Returns optimized `EmployeeSchedule` with score and assignments
