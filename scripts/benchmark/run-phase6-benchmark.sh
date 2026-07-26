#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:-quick}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

cd "$ROOT_DIR"

case "$MODE" in
  quick)
    PARTITION="${PARTITION:-smoke}"
    DATASETS="${DATASETS:-request.json}"
    SEEDS="${SEEDS:-991,992}"
    REPEATS="${REPEATS:-1}"
    PROFILES="wall-clock,fixed-evaluations"
    WALL_CLOCK_SECONDS="${WALL_CLOCK_SECONDS:-3}"
    OPTAPLANNER_EVALUATION_LIMIT="${OPTAPLANNER_EVALUATION_LIMIT:-20000}"
    POJO_EVALUATION_LIMIT="${POJO_EVALUATION_LIMIT:-10}"
    WARMUP="${WARMUP:-false}"
    ;;
  wall-clock)
    PARTITION="${PARTITION:-holdout}"
    DATASETS="${DATASETS:-fairness.json,preceptor.json,request.json,sample.json}"
    SEEDS="${SEEDS:-101,102,103,104,105,106,107,108,109,110}"
    REPEATS="${REPEATS:-2}"
    PROFILES="wall-clock"
    WALL_CLOCK_SECONDS="${WALL_CLOCK_SECONDS:-60}"
    OPTAPLANNER_EVALUATION_LIMIT="${OPTAPLANNER_EVALUATION_LIMIT:-1}"
    POJO_EVALUATION_LIMIT="${POJO_EVALUATION_LIMIT:-1}"
    WARMUP="${WARMUP:-true}"
    ;;
  fixed)
    PARTITION="${PARTITION:-holdout}"
    DATASETS="${DATASETS:-fairness.json,preceptor.json,request.json,sample.json}"
    SEEDS="${SEEDS:-101,102,103,104,105,106,107,108,109,110}"
    REPEATS="${REPEATS:-2}"
    PROFILES="fixed-evaluations"
    WALL_CLOCK_SECONDS="${WALL_CLOCK_SECONDS:-60}"
    : "${OPTAPLANNER_EVALUATION_LIMIT:?wall-clock report로 확정한 OPTAPLANNER_EVALUATION_LIMIT가 필요합니다.}"
    : "${POJO_EVALUATION_LIMIT:?wall-clock report로 확정한 POJO_EVALUATION_LIMIT가 필요합니다.}"
    WARMUP="${WARMUP:-true}"
    ;;
  *)
    echo "사용법: $0 {quick|wall-clock|fixed}" >&2
    exit 2
    ;;
esac

ENVIRONMENT="${ENVIRONMENT:-macos-local}"
CANDIDATE_ENGINE="${CANDIDATE_ENGINE:-POJO_ALNS_BASELINE}"
RUN_LABEL="${RUN_LABEL:-phase6-${CANDIDATE_ENGINE}-${PARTITION}-${MODE}-${TIMESTAMP}}"
OUTPUT_DIR="${OUTPUT_DIR:-benchmark-artifacts/phase6/${PARTITION}/${MODE}/${TIMESTAMP}}"
RAW_OUTPUT="$OUTPUT_DIR/raw.jsonl"
SUMMARY_OUTPUT="$OUTPUT_DIR/summary.json"
REPORT_OUTPUT="$OUTPUT_DIR/report.md"

echo "Phase 6 paired benchmark"
echo "  mode: $MODE"
echo "  partition: $PARTITION"
echo "  datasets: $DATASETS"
echo "  seeds: $SEEDS"
echo "  repeats: $REPEATS"
echo "  profiles: $PROFILES"
echo "  candidate engine: $CANDIDATE_ENGINE"
echo "  output: $OUTPUT_DIR"

./mvnw \
  -Dtest=Phase6PairedBenchmarkTest \
  -Dphase6.benchmark.enabled=true \
  -Dphase6.benchmark.datasets="$DATASETS" \
  -Dphase6.benchmark.seeds="$SEEDS" \
  -Dphase6.benchmark.repeats="$REPEATS" \
  -Dphase6.benchmark.profiles="$PROFILES" \
  -Dphase6.benchmark.wall-clock-seconds="$WALL_CLOCK_SECONDS" \
  -Dphase6.benchmark.optaplanner-evaluation-limit="$OPTAPLANNER_EVALUATION_LIMIT" \
  -Dphase6.benchmark.pojo-evaluation-limit="$POJO_EVALUATION_LIMIT" \
  -Dphase6.benchmark.candidate-engine="$CANDIDATE_ENGINE" \
  -Dphase6.benchmark.warmup="$WARMUP" \
  -Dphase6.benchmark.environment="$ENVIRONMENT" \
  -Dphase6.benchmark.partition="$PARTITION" \
  -Dphase6.benchmark.run-label="$RUN_LABEL" \
  -Dphase6.benchmark.output="$RAW_OUTPUT" \
  -Dphase6.benchmark.summary-output="$SUMMARY_OUTPUT" \
  -Dphase6.benchmark.report-output="$REPORT_OUTPUT" \
  test

echo "완료"
echo "  raw: $RAW_OUTPUT"
echo "  summary: $SUMMARY_OUTPUT"
echo "  report: $REPORT_OUTPUT"
