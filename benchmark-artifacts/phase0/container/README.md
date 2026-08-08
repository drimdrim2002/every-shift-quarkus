# Phase 0 컨테이너 기준선 상태

- 상태: 미측정
- 사유: 2026-07-15 현재 작업 macOS에 Docker, Podman, Colima, Finch 또는 containerd 런타임이 설치되어 있지 않음
- 금지 조건 준수: 원격 Cloud Run 배포/실행은 수행하지 않음
- 예정 artifact: `benchmark-artifacts/phase0/container/*.jsonl`
- 재실행 명령: `docs/benchmarks/PHASE0_OPTAPLANNER_BASELINE.md`의 “단일 컨테이너 기준선” 참조

컨테이너 결과를 생성할 때 `benchmark.environment=container`, CPU 8, memory 4 GiB, Java 21, 단일 solver thread 조건을 유지한다.
