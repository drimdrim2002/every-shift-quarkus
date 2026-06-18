# Fix: Burden Fairness Score - Variance Minimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modify `yearlyNightHolidayBurdenFairness` constraint to minimize variance of *current-period* burden only, removing yearly accumulated burden from the penalty calculation so the solver distributes burden fairly within the scheduling period rather than avoiding historically burdened employees.

**Architecture:** This is a surgical change to a single OptaPlanner `ConstraintProvider` method. The penalty function changes from `(yearlyNight + yearlyHoliday + currentBurden)^2` to `currentBurden^2`. Two unit tests' expected values must be updated to match the new math. No new files are created; only existing constraint and test files are modified.

**Tech Stack:** Java 21, Quarkus 3.15.1, OptaPlanner, Maven, JUnit 5, ConstraintVerifier

---

## Background

`SOFT_BURDEN_FAIRNESS_INDEX` (soft level 4) currently minimizes the sum of squared *total* burden per employee, where total burden includes yearly historical counts:

```java
int totalBurden = employee.getYearlyNightWorkCount()
                + employee.getYearlyHolidayWorkCount()
                + currentBurden;
return totalBurden * totalBurden;
```

Because the square function penalizes additional burden on already-high-burden employees far more heavily, the solver learns to dump new burden onto employees with low historical counts. This paradoxically *increases* inequality. By removing the yearly accumulation and penalizing only `currentBurden * currentBurden`, we minimize variance of the current schedule's burden distribution (by Cauchy-Schwarz).

**Concrete example from existing analysis:**
- Employee A: yearlyNight=10, yearlyHoliday=5, current=0 → old penalty = 15² = **225**
- Employee B: yearlyNight=0, yearlyHoliday=0, current=2 → old penalty = 2² = **4**
- Old total = **229**

If we instead give 1 to each:
- A: 16² = **256**, B: 1² = **1** → total = **257**

OptaPlanner chooses 229 < 257, so B gets both shifts. After the fix, current burden only:
- A: 0² = 0, B: 2² = **4** vs A: 1² = **1**, B: 1² = **1** (total 2). The solver will now prefer the equal split.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java` | Modify | Change `yearlyNightHolidayBurdenFairness` penalty to use `currentBurden` only |
| `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java` | Modify | Update expected penalty values: `81` → `16`, and full score array soft[4] `-81` → `-16` |

**Optional (out of scope for this plan, noted for future):**
- `Employee.java`: `yearlyNightWorkCount` / `yearlyHolidayWorkCount` are no longer used by any constraint after this change. If no service layer or external serialization relies on them, they could be removed in a follow-up cleanup.

---

## Task 1: Update Constraint Penalty Calculation

**Files:**
- Modify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java:270-282`

- [ ] **Step 1: Modify the penalty lambda**

Open `EmployeeSchedulingConstraintProvider.java`, locate the `yearlyNightHolidayBurdenFairness` method (lines 270-282). Change the `.penalize(...)` lambda from:

```java
.penalize(ONE_SOFT_BURDEN_FAIRNESS,
        (employee, currentBurden) -> {
            int totalBurden = employee.getYearlyNightWorkCount()
                            + employee.getYearlyHolidayWorkCount()
                            + currentBurden;
            return totalBurden * totalBurden;
        })
```

To:

```java
.penalize(ONE_SOFT_BURDEN_FAIRNESS,
        (employee, currentBurden) -> currentBurden * currentBurden)
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
./mvnw clean compile -DskipTests
```

Expected: `BUILD SUCCESS` with no compilation errors.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java
git commit -m "fix(constraint): use current-period burden only for burden fairness penalty

Removes yearlyNightWorkCount and yearlyHolidayWorkCount from the
yearlyNightHolidayBurdenFairness penalty calculation. The solver
now minimizes variance of current-period burden distribution instead
of avoiding historically burdened employees."
```

---

## Task 2: Update Unit Test Expected Values

**Files:**
- Modify: `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java:689-713`

- [ ] **Step 1: Update `yearlyNightHolidayBurdenFairness_UsesYearlyAndCurrentBurden` expected penalty**

In `EmployeeSchedulingConstraintProviderTest.java`, find the test at line 689:

```java
@Test
void yearlyNightHolidayBurdenFairness_UsesYearlyAndCurrentBurden() {
    Employee employee = createEmployee("E1");
    employee.setYearlyNightWorkCount(2);
    employee.setYearlyHolidayWorkCount(3);

    Shift shift = createShift(1L, employee, LocalDate.of(2025, 12, 25), 9, 17);
    shift.setFairnessBurdenScore(4);

    constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::yearlyNightHolidayBurdenFairness)
            .given(shift)
            .penalizesBy(81); // (2 + 3 + 4)^2
}
```

Change `.penalizesBy(81)` to `.penalizesBy(16)` and update the comment:

```java
            .penalizesBy(16); // 4^2 (current burden only)
```

- [ ] **Step 2: Update `softScoreLevels_BurdenFairnessOnly` expected full score**

In the same file, find the test at line 703:

```java
@Test
void softScoreLevels_BurdenFairnessOnly() {
    Employee employee = createEmployee("E1");
    employee.setYearlyNightWorkCount(2);
    employee.setYearlyHolidayWorkCount(3);
    Shift shift = createShift(1L, employee, LocalDate.of(2025, 12, 5), 9, 17);
    shift.setFairnessBurdenScore(4);

    constraintVerifier.verifyThat()
            .given(shift)
            .scores(BendableScore.of(new int[] { 0 }, new int[] { 0, 0, 0, 0, -81, -1, 0 }));
}
```

Change the score array soft index 4 from `-81` to `-16`:

```java
            .scores(BendableScore.of(new int[] { 0 }, new int[] { 0, 0, 0, 0, -16, -1, 0 }));
```

- [ ] **Step 3: Run the specific tests to verify they pass**

```bash
./mvnw -Dtest=EmployeeSchedulingConstraintProviderTest#yearlyNightHolidayBurdenFairness_UsesYearlyAndCurrentBurden test
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

```bash
./mvnw -Dtest=EmployeeSchedulingConstraintProviderTest#softScoreLevels_BurdenFairnessOnly test
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java
git commit -m "test(constraint): update burden fairness expected penalties

Updates test expectations to match current-period-only penalty:
- single penalty 81 -> 16
- full score soft[4] -81 -> -16"
```

---

## Task 3: Optional - Rename Method and Constraint String

**Files:**
- Modify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`
- Modify: `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java`

> **Note:** This task is optional. The behavior is correct even without renaming. Do this only if you want the method name to reflect the new semantics. If skipped, ensure Task 1 and Task 2 are already done.

- [ ] **Step 1: Rename the constraint method and its string label**

In `EmployeeSchedulingConstraintProvider.java`:
1. Rename the method `yearlyNightHolidayBurdenFairness` → `currentPeriodBurdenFairness`
2. Change `.asConstraint("Yearly night/holiday burden fairness")` → `.asConstraint("Current period burden fairness")`
3. Update the call in `defineConstraints` (line 94) to use the new method name.

In `EmployeeSchedulingConstraintProviderTest.java`:
1. Update method references in `verifyThat(...)` calls for this constraint to use the new method name.
2. Update test method name `yearlyNightHolidayBurdenFairness_UsesYearlyAndCurrentBurden` → `currentPeriodBurdenFairness_UsesCurrentBurdenOnly` (optional but recommended).

- [ ] **Step 2: Compile and run all constraint tests**

```bash
./mvnw -Dtest=EmployeeSchedulingConstraintProviderTest test
```

Expected: All tests in the class pass (40+ tests).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java
git add src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java
git commit -m "refactor(constraint): rename burden fairness method to match new semantics

yearlyNightHolidayBurdenFairness -> currentPeriodBurdenFairness"
```

---

## Task 4: Full Verification

**Files:**
- Verify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`
- Verify: `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java`

- [ ] **Step 1: Run full unit test suite for the constraint provider**

```bash
./mvnw -Dtest=EmployeeSchedulingConstraintProviderTest test
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 2: Run integration test (`SolverRunnerTest`)**

```bash
./mvnw -Dtest=SolverRunnerTest test
```

Expected: `BUILD SUCCESS`. The integration test does not assert a specific burden-fairness score value, so it should continue to pass. Verify no hard-constraint regressions.

- [ ] **Step 3: (Optional manual) Compare before/after burden distribution**

If you have a fixed input dataset and want to observe the behavioral change:
1. Run the solver on the same input before the fix (or on a branch).
2. Record the `currentBurden` distribution per employee.
3. Run on the same input after the fix.
4. Confirm the Gini coefficient or max-min spread of `currentBurden` has decreased.

- [ ] **Step 4: Final commit (if any uncommitted changes remain)**

```bash
git diff --cached --quiet || git commit -m "chore: finalize burden fairness fix verification"
```

---

## Plan Review Loop

After writing (or in this case, reviewing/strengthening) the complete plan:

1. **Dispatch a plan-document-reviewer subagent.**
   Provide the reviewer with:
   - Path to this plan document: `docs/superpowers/plans/2026-05-23-fix-burden-fairness-score.md`
   - Path to the technical background / implicit spec: this document's Background section
2. **If ❌ Issues Found:** Fix the issues in this same session, then re-dispatch the reviewer.
3. **If ✅ Approved:** Proceed to execution handoff below.

> **Guidance:** Same agent that fixes the plan should re-dispatch. If the review loop exceeds 3 iterations, surface to the human for guidance.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-23-fix-burden-fairness-score.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
   - **REQUIRED SUB-SKILL:** `superpowers:subagent-driven-development`

**2. Inline Execution** — Execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints.
   - **REQUIRED SUB-SKILL:** `superpowers:executing-plans`

**Which approach would you like to proceed with?**

---

## Appendix: Advanced Considerations (Future Work)

The current fix focuses on **current-period equalization only**.

If you later need to minimize **total cumulative variance** (including historical yearly burden), the OptaPlanner constraint stream would need to compute the mean total burden across all employees and penalize deviations from that mean. This significantly increases constraint-stream complexity. We recommend deploying this simple fix first and evaluating real-world schedule quality before attempting the advanced model.
