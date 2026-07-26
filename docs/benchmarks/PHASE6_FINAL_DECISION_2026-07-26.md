# Phase 6 최종 결정 (2026-07-26)

## 결정

사용자 합의에 따라 Phase 6 연구를 종료한다. 승리 수의 확대보다 **사전식 0패(non-loss)**
를 우선하며, 독립 60초 동등비용 검증의 `3승 / 5무 / 0패`을 Phase 6 완료 기준으로
인정한다.

이 결정은 production 기본값 변경, 배포, 점수 의미 변경 또는 잠긴 holdout 재실행을
승인하지 않는다. 모든 후보는 test-only로 유지한다.

## 최종 근거

최종 후보는 `ALNS → ordered VND → preceptor-prefix reassign`이다.

| 항목 | 결과 |
|---|---|
| 독립 60초 paired 검증 | 8쌍, 3승 / 5무 / 0패 |
| feasible / hard | Opta·POJO 모두 8/8 feasible, hard=0 |
| 최초 승리 목적식 | fairness soft[2] 1승, preceptor soft[0] 1승·soft[1] 1승 |
| 정확성·상태 | rollback failure, score mismatch, state corruption, execution failure 모두 0 |
| 전체 테스트 | 355 tests, failures 0, errors 0, skipped 12 |

원인 진단 결과, preceptor의 기존 loss는 원자적 개선 move 부재가 아니라 기존 VND가
5,400개 reassign 중 256개만 탐색해 witness를 놓친 coverage 병목이었다. 새 selector는
soft[0]·soft[1] 기여 shift를 안정 순서로 우선하되 모든 수락 전 full score의 엄격한
사전식 비교를 적용한다.

## 보류한 사항

- production 기본값 또는 solver 선택 변경
- Phase 7 승격
- locked holdout `101..110` 재실행
- witness가 없었던 7일 3-cycle move 구현

## 보존 artifact

- 진단 보고서: `/private/tmp/every-shift-quarkus-preceptor-diagnostic/docs/benchmarks/PHASE6_PRECEPTOR_PREFIX_LOSS_DIAGNOSTIC_2026-07-26.md`
- 독립 검증: `/private/tmp/every-shift-quarkus-preceptor-diagnostic/benchmark-artifacts/phase6/preceptor-diagnostic/validation/wall-60s-20260726T142800Z/`
- bounded exhaustive 진단: `/private/tmp/every-shift-quarkus-preceptor-diagnostic/benchmark-artifacts/phase6/preceptor-diagnostic/20260726T142000Z/`

## Phase 7 전제

Phase 7은 사용자가 별도로 요청할 때만 시작한다. 시작하더라도 이 Phase 6 결론은
production 승격이 아니라 test-only 후보와 재현 가능한 증거의 보존으로 해석한다.
