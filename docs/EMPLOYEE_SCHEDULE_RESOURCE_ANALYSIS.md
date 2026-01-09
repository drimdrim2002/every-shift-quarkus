# EmployeeScheduleResource Analysis

## Overview
`EmployeeScheduleResource.java` acts as the main entry point (REST Controller) for the Employee Scheduling application. It exposes a REST API to retrieve the current schedule, start/stop the solving process (OptaPlanner), and publish the final schedule.

In Spring Boot terms, this is equivalent to a `@RestController`. It orchestrates the interaction between the web client, the database (via Panache Repositories), and the OptaPlanner `SolverManager`.

## Architecture & Core Concepts

### 1. Framework: Quarkus
- **`@Path("/schedule")`**: Defines the base URL path for this resource. (Similar to Spring's `@RequestMapping`)
- **`@Inject`**: Handles Dependency Injection (CDI). (Standard Java equivalent to Spring's `@Autowired`)
- **`@Transactional`**: Manages database transactions automatically. Essential for methods that modify data (`save`, `publish`).

### 2. Data Access: Hibernate ORM with Panache
The application uses the Repository pattern via Panache.
- Repositories (`AvailabilityRepository`, `EmployeeRepository`, etc.) act as DAOs.
- Methods like `findById`, `listAll`, and `persist` are provided out-of-the-box, eliminating the need for boilerplate SQL or JPA code.

### 3. Optimization: OptaPlanner
The resource integrates with OptaPlanner to solve the employee scheduling problem asynchronously.
- **`SolverManager<EmployeeSchedule, Long>`**: Manages the background solving tasks.
- **`SolutionManager<EmployeeSchedule, HardSoftScore>`**: Helper to calculate or update scores for a given solution.

---

## Key Methods & Workflow

### `solve()` (`POST /schedule/solve`)
Starts the asynchronous solving process.
- **Mechanism**: Calls `solverManager.solveAndListen(...)`.
  - This is non-blocking; the method returns immediately while the solver runs in a background thread.
- **Parameters**:
  - `SINGLETON_SCHEDULE_ID`: Identifies the problem instance (only one schedule is supported in this quickstart).
  - `this::findById`: A function to load the current problem state from the DB.
  - `this::save`: A consumer function that OptaPlanner calls whenever a better solution is found.

### `stopSolving()` (`POST /schedule/stopSolving`)
Terminates the solving process early.
- **Mechanism**: Calls `solverManager.terminateEarly(...)`.
- Useful if the user is satisfied with the current solution before the time limit expires.

### `publish()` (`POST /schedule/publish`)
Finalizes the current draft schedule and prepares the next period.
- **Constraint**: Cannot be called while solving is in progress.
- **Logic**:
  1.  Retrieves the `ScheduleState`.
  2.  Updates `lastHistoricDate` and `firstDraftDate` (shifting the window forward).
  3.  Calls `dataGenerator.generateDraftShifts(...)` to create empty shifts for the new draft period.
- **Transactional**: This method modifies the database state, so it is marked with `@Transactional`.

### `getSchedule()` (`GET /schedule`)
Retrieves the current state of the schedule.
- **Logic**:
  1.  Gets the `SolverStatus` (solving or not solving).
  2.  Loads the full `EmployeeSchedule` from the DB via `findById`.
  3.  Updates the score using `solutionManager.update(solution)`.
  4.  Attaches the solver status and returns the object (serialized to JSON).

### `findById(Long id)`
Assembles the full `EmployeeSchedule` object (the "Problem Fact").
- Aggregates data from `ScheduleStateRepository`, `AvailabilityRepository`, `EmployeeRepository`, and `ShiftRepository`.
- This creates the in-memory representation required by OptaPlanner.

### `save(EmployeeSchedule schedule)`
Persists the solution changes back to the database.
- Iterates through the shifts in the solution and updates the assigned employee in the database (`ShiftRepository`).
- **Note**: This is called frequently by the `SolverManager` during the solving process.

---

## Configuration Mapping

The behavior of this resource is controlled by configuration in `src/main/resources/application.properties` and dependencies in `pom.xml`.

### `application.properties`
- **Solver Termination**:
  ```properties
  quarkus.optaplanner.solver.termination.spent-limit=30s
  ```
  Controls how long the `solve()` method runs in the background before automatically stopping.
- **Database Connection**:
  ```properties
  quarkus.datasource.jdbc.url=jdbc:h2:mem:employee-scheduling...
  ```
  Defines the H2 in-memory database used by the repositories.

### `pom.xml`
- **Dependencies**:
  - `optaplanner-quarkus`: Provides `SolverManager` and integration.
  - `quarkus-hibernate-orm-panache`: Provides the repository capabilities.
  - `quarkus-resteasy-jackson`: Handles REST endpoints and JSON serialization.
