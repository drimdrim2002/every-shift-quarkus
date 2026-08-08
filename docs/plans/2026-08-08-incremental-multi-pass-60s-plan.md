# Incremental Multi-Pass 60s Workflow Plan

> **For agentic workers:** 구현 시 이 문서를 정본으로 한다. 인터뷰(deep-interview)로 고정한 결정만 구현하고, 열린 질문은 구현 전 짧게 확인하거나 권장 기본값을 사용한다.

**Goal:** Cloud Run Job 경로의 `solveIncremental` 을 “60초 패스 × (1+최대 5)회” 정책으로 정렬하고, hard 위반·점수 개선 여부에 따라 재실행/종료하며, 회차 종료 시 중간 결과를 저장한다. 설정은 `application.properties` 의 `solver.incremental.*` 로 제어한다.

**Architecture:** 신규 엔진을 추가하지 않는다. `SolverRunner.solveIncremental` 루프와 `determineTerminationReason` 정책만 교체·확장한다. Job 진입점(`WorkerResource`)은 콜백 시점을 “회차 종료”에 맞게 조정한다.

**Tech Stack:** Java 21, Quarkus 3.15, 기존 POJO Hybrid 솔버, Firestore `JobExecutionService`.

---

## 1. 인터뷰 결정사항 요약

| 항목 | 결정 |
|------|------|
| 구현 방식 | **기존 `solver.incremental` 확장** (신규 multi-pass 엔진 없음) |
| 패스 길이 | 매 회차 **60초** (`spent-limit` per pass) |
| 횟수 | **1회차 + 추가 최대 5회 = 총 최대 6회** |
| 최소 회수 | **강제 최소 2회 없음** (1회차만으로 hard≥0 이면 종료 가능) |
| 전역 best | 매 패스 후 사전식 비교로 갱신, **warmStart 로 다음 패스에 전달** |
| 개선 | 전역 best 대비 **엄격 개선** (`compareTo > 0`) |
| hard 위반 | 전역 best `hardScore < 0` |
| CONTINUE | 엄격 개선 **OR** hard&lt;0 |
| STOP | 이번 패스에서 best **비개선** **AND** hard≥0 |
| 중간 저장 | **회차 종료 시** 전역 best 만 `saveIntermediateResult` (내부 best 리스너 매 저장 아님) |
| 최종 저장 | 루프 종료 후 기존 `saveResult` 유지 |

### 1.1 의사코드 (정본)

```text
best = null
for pass in 1 .. maxIterations:          # maxIterations = 6
    result = solve(spentLimit=passSeconds, warmStart=best, seed=...)
    if result == null:
        if best == null: fail
        else: break                      # complete 해 없음 → 보유 best 로 종료

    previousBest = best
    if best == null OR result.score > best.score:   # 엄격 개선(또는 최초)
        best = result

    # 회차 종료 저장 (전역 best)
    saveIntermediate(best)

    improved = (previousBest != null AND best.score > previousBest.score)
             OR (previousBest == null)   # 1회차는 “개선” 개념 대신 hard 로만 판단
    hardViolated = best.score.hard < 0

    if pass == 1:
        if hardViolated: continue
        else: break                      # hard≥0 → 1회만으로 STOP

    if improved OR hardViolated:
        if pass >= maxIterations: break
        continue
    else:
        break                            # 비개선 AND hard≥0 → STOP

    if wallClock >= maxTotalMinutes: break

return best
```

**1회차 규칙 (인터뷰 확정):**

- `previousBest == null` 이므로 “비개선”만으로 종료하지 않고,
- **hard≥0 → STOP**, **hard&lt;0 → CONTINUE**.

**2회차 이후:**

- 이번 패스 종료 후 전역 best 가 **직전 전역 best 대비 엄격 개선**이면 CONTINUE  
- 또는 hard&lt;0 이면 CONTINUE  
- 둘 다 아니면 STOP  
- `pass == maxIterations` 이면 무조건 종료

---

## 2. 현재 코드와의 차이

| | 현재 `solveIncremental` | 목표 |
|--|-------------------------|------|
| 2회차 이후 초 | `iteration-seconds=30` | **60** |
| max iterations | 30 | **6** |
| min iterations | 2 (동일 점수여도 2회까지) | **1** (강제 2회 없음) |
| 종료 | `iteration>=min && score.equals(previous)` | **비개선 ∧ hard≥0** / hard&lt;0 이면 계속 |
| hard 고려 | 없음 | **hard&lt;0 이면 상한까지 재실행** |
| 중간 저장 | SolveListener **내부 best마다** | **패스 종료 시 1회** |
| warmStart | 있음 | 유지 |

관련 파일:

- `src/main/java/org/acme/solver/SolverRunner.java` — 루프·종료 정책
- `src/main/java/org/acme/resource/WorkerResource.java` — 콜백 연결
- `src/main/resources/application.properties` — 설정 기본값
- `src/test/java/org/acme/solver/SolverRunnerTerminationPolicyTest.java` — 정책 단위 테스트

---

## 3. Config (`application.properties`)

### 3.1 권장 프로덕션 기본값

```properties
# Incremental multi-pass (60s × up to 6)
solver.incremental.enabled=true
solver.incremental.first-iteration-seconds=60
solver.incremental.iteration-seconds=60
solver.incremental.max-iterations=6
solver.incremental.min-iterations=1
# wall-clock 안전장치: 6×60s=6min 보다 여유 있게
solver.incremental.max-total-minutes=10
# 회차 종료 시에만 중간 저장 (true=권장)
solver.incremental.save-on-pass-end=true
# (선택) 엔진 내부 best 리스너로 Firestore 쓰지 않음
solver.incremental.save-on-inner-best=false
```

### 3.2 의미

| 키 | 역할 |
|----|------|
| `enabled` | false 면 기존 `solve()` 1-shot (`spent-limit` 단일) |
| `first-iteration-seconds` | 1회차 벽시계 |
| `iteration-seconds` | 2회차 이후 벽시계 (요청상 first 와 동일 60) |
| `max-iterations` | 총 패스 상한 (**6**) |
| `min-iterations` | 레거시 필드. 새 정책에서는 **1** 로 두고, “최소 2회 강제” 로직 제거 또는 no-op |
| `max-total-minutes` | 전체 wall-clock hard cap (패스 합이 넘치면 조기 종료) |
| `save-on-pass-end` | 패스 종료 시 intermediate 콜백 1회 |
| `save-on-inner-best` | 엔진 내부 best 리스너 → Firestore (기본 false) |

### 3.3 프로필

```properties
# Dev: 짧은 패스로 루프 검증
%dev.solver.incremental.first-iteration-seconds=10
%dev.solver.incremental.iteration-seconds=10
%dev.solver.incremental.max-iterations=3
%dev.solver.incremental.min-iterations=1

# Test: 단위 테스트는 mock/짧은 값; 정책 테스트는 초 단위 mock
%test.solver.incremental.first-iteration-seconds=2
%test.solver.incremental.iteration-seconds=2
%test.solver.incremental.max-iterations=6
%test.solver.incremental.min-iterations=1
```

`solver.termination.spent-limit` 는 **단일 `solve()` 경로**용으로 유지. incremental 패스 길이는 **incremental.*-seconds** 가 정본.

---

## 4. 구현 설계

### 4.1 `determineTerminationReason` 재정의

입력 시그니처 확장 권장:

```java
TerminationReason determineTerminationReason(
    int pass,                    // 1-based, 방금 끝난 패스
    int maxIterations,
    RosterScore bestAfterPass,   // 전역 best (갱신 후)
    RosterScore bestBeforePass,  // 패스 시작 전 전역 best (1회차는 null)
    long nowNanos,
    long deadlineNanos)
```

규칙 우선순위:

1. `now >= deadline` → `DEADLINE_REACHED`
2. `pass >= maxIterations` → `MAX_ITERATIONS_REACHED`
3. `bestAfterPass.hard < 0` → `CONTINUE` (상한 전제)
4. `bestBeforePass == null` (1회차) → hard≥0 이면 종료용 사유 `CONVERGED` (또는 `COMPLETED`)
5. `bestAfterPass.compareTo(bestBeforePass) > 0` → `CONTINUE`
6. 그 외 → `CONVERGED` (비개선 ∧ hard≥0)

> 기존 `equals(previous)` + `minIterations` 기반 CONVERGED 는 **삭제 또는 위 규칙으로 대체**.

### 4.2 루프 본문 변경 포인트 (`solveIncremental`)

1. 패스 시작 전 `bestBefore = bestSolution` 스냅샷  
2. `solve` (warmStart, spentLimit, **deadlineNanos 전체 cap 유지**)  
3. 전역 best 갱신 (엄격 개선 또는 최초)  
4. **패스 종료 콜백** `passEndCallback.accept(toEmployeeSchedule(best))`  
5. 종료 사유 판단 → break / continue  
6. 로그: `pass`, `bestBefore`, `bestAfter`, `hard`, `improved`, `reason`

### 4.3 WorkerResource 콜백

```text
solveIncremental(..., passEndCallback)
  → saveIntermediateResult(executionId, schedule)  // 패스당 1회

내부 SolveListener
  → save-on-inner-best=false 이면 no-op
  → true 이면 기존처럼 intermediate (옵션)
```

API 시그니처:

- `solveIncremental(request, executionId, Consumer<EmployeeSchedule> onPassEnd)`  
- 필요 시 overload: `onPassEnd`, `onInnerBest` 분리

### 4.4 Seed (권장 기본값 — 열린 질문 기본 채택)

- `randomSeed + pass` (또는 `randomSeed + 31L * pass`) 로 패스마다 약간 다른 탐색  
- warmStart 가 있으므로 동일 seed 도 가능하나, **다회차  Diversification** 을 위해 pass-offset 권장  
- 구현 시 `solver.incremental.seed-mode=BASE|BASE_PLUS_PASS` (기본 `BASE_PLUS_PASS`)

### 4.5 hard 판정 기준

- **전역 best** 의 `hardScore()` 사용 (방금 패스 해가 더 나빠 hard 만 좋아진 경우 등은 사전식 갱신 규칙에 따름)  
- 패스 해가 null 이면 best 유지 후 종료 판단

### 4.6 Job / Cloud Run 시간

- 최악 wall ≈ 6 × 60s = **6분** (+ JVM/IO)  
- `max-total-minutes=10` 과 Cloud Run Job timeout 이 **≥ 10분** 인지 배포 설정 확인 (구현 체크리스트)

### 4.7 최종 validation

- 6회 후에도 hard&lt;0 이면 현재와 같이 validation 실패 가능 (NOD hard 정책 유지)  
- multi-pass 는 “더 기회”를 줄 뿐 hard 를 soft 로 바꾸지 않음

---

## 5. 테스트 계획

### 5.1 단위: `SolverRunnerTerminationPolicyTest` (확장)

| 케이스 | 기대 |
|--------|------|
| pass=1, hard=0 | STOP (CONVERGED/COMPLETED) |
| pass=1, hard&lt;0 | CONTINUE |
| pass=2, hard=0, score 동일 | STOP |
| pass=2, hard=0, score 엄격 개선 | CONTINUE |
| pass=2, hard&lt;0, score 동일 | CONTINUE |
| pass=6, 아무 score | MAX_ITERATIONS_REACHED |
| deadline 초과 | DEADLINE_REACHED |

### 5.2 통합/슬라이스 (mock engine)

- Fake `SolverEngine` 이 패스마다 정해진 score 시퀀스 반환  
- 콜백 호출 횟수 = **패스 수** (내부 best 가 여러 번이어도 pass-end 만)  
- warmStart 가 이전 best assignment 인지 검증

### 5.3 회귀

- `enabled=false` → 기존 `solve()` 1-shot 동작  
- `SolverRunnerTest` / Job 경로 스모크 (가능하면 short seconds)

---

## 6. 구현 Task 체크리스트

- [x] **Task 1:** `application.properties` 기본값·dev/test 프로필 정렬 + 신규 키 (`save-on-pass-end`, `save-on-inner-best`, 선택 `seed-mode`)
- [x] **Task 2:** `determineTerminationReason` 정책 교체 + 단위 테스트 전면 갱신
- [x] **Task 3:** `solveIncremental` 루프: bestBefore/After, pass-end 콜백, seed, 로그
- [x] **Task 4:** `WorkerResource` 콜백을 pass-end 에 연결; inner-best 저장 기본 off
- [x] **Task 5:** mock 엔진 통합 테스트 (콜백 횟수·CONTINUE/STOP 시나리오)
- [x] **Task 6:** Cloud Run Job timeout / `max-total-minutes` 문서·배포 스크립트 확인 (`deploy.sh` `--task-timeout 900s` ≥ 10min)
- [x] **Task 7:** `Claude.md` / 운영 메모에 soft 레벨·incremental 정책 한 줄 반영 (선택)

---

## 7. 완료 기준 (Definition of Done)

1. properties 만으로 **60s × 최대 6패스**, **1회차 hard≥0 즉시 종료**, **hard&lt;0 시 상한까지 재시도**, **비개선∧hard≥0 종료** 가 동작한다.  
2. Firestore 중간 저장은 **패스 종료당 최대 1회**.  
3. 전역 best warmStart 가 다음 패스에 전달된다.  
4. 종료 정책 단위 테스트가 위 표 케이스를 모두 고정한다.  
5. 기존 1-shot `solve()` / `incremental.enabled=false` 가 깨지지 않는다.  
6. 배포 시 Job 타임아웃이 최악 6분+ 여유를 허용한다.

---

## 8. 제외 범위

- soft[0] 목표 달성 시까지 **무기한** 실행 (spent/max pass 무시) — 하지 않음  
- hard 제약을 soft 로 되돌리기  
- OptaPlanner 복구  
- 패스 사이 다른 엔진/모드 자동 전환  
- UI 변경

---

## 9. 열린 질문 (구현 시 기본값으로 진행 가능)

| # | 질문 | 권장 기본 |
|---|------|-----------|
| 1 | 패스별 random seed | `BASE_PLUS_PASS` |
| 2 | 1회차 hard≥0 종료 시 TerminationReason 이름 | `CONVERGED` 유지 |
| 3 | `min-iterations` 키 삭제 vs 1로 고정 | **키 유지, 기본 1, 로직 no-op** |
| 4 | `runWithResult`/로컬 JOB 파일 실행도 incremental 쓸지 | Job/Worker 만 강제, 로컬은 enabled 플래그 따름 |

---

## 10. 리스크

| 리스크 | 완화 |
|--------|------|
| Job timeout &lt; 6분 | 배포 설정 확인, max-iterations/seconds 프로파일 |
| hard 불가로 6회 전부 실패 | 로그에 pass별 hard 기록; 최종 validation 메시지 유지 |
| 중간 저장 비용 | pass-end only 로 상한 6회 |
| 동일 seed 정체 | `BASE_PLUS_PASS` |

---

## 11. 구현 착수 명령 (승인 후)

```bash
# 정책 테스트
./mvnw -Dtest=SolverRunnerTerminationPolicyTest test

# (구현 후) incremental 슬라이스 테스트
./mvnw -Dtest=SolverRunnerIncremental*,SolverRunnerTerminationPolicyTest test
```

---

**문서 상태:** deep-interview 결정 반영 완료. **구현 완료 (2026-08-08).**
```
