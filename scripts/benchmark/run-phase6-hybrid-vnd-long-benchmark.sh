#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:-pilot}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

case "$MODE" in
  pilot)
    PARTITION="${PARTITION:-development-pilot}"
    DATASETS="${DATASETS:-fairness.json,preceptor.json,request.json,sample.json}"
    SEEDS="${SEEDS:-901,902}"
    PROFILE="wall"
    WALL_SECONDS="${WALL_SECONDS:-3}"
    OPTA_EVALUATIONS="${OPTA_EVALUATIONS:-50000}"
    POJO_EVALUATIONS="${POJO_EVALUATIONS:-5000}"
    WARMUP="${WARMUP:-true}"
    ;;
  wall-60)
    PARTITION="${PARTITION:-validation-60s}"
    DATASETS="${DATASETS:-fairness.json,preceptor.json,request.json,sample.json}"
    SEEDS="${SEEDS:-1001,1002}"
    PROFILE="wall"
    WALL_SECONDS="${WALL_SECONDS:-60}"
    OPTA_EVALUATIONS="${OPTA_EVALUATIONS:-50000}"
    POJO_EVALUATIONS="${POJO_EVALUATIONS:-50000}"
    WARMUP="${WARMUP:-true}"
    ;;
  wall-120)
    PARTITION="${PARTITION:-validation-120s-extra-compute}"
    DATASETS="${DATASETS:-fairness.json,preceptor.json,request.json,sample.json}"
    SEEDS="${SEEDS:-1001,1002}"
    PROFILE="wall"
    WALL_SECONDS="${WALL_SECONDS:-120}"
    OPTA_EVALUATIONS="${OPTA_EVALUATIONS:-50000}"
    POJO_EVALUATIONS="${POJO_EVALUATIONS:-50000}"
    WARMUP="${WARMUP:-true}"
    ;;
  wall-180)
    PARTITION="${PARTITION:-validation-180s-extra-compute}"
    DATASETS="${DATASETS:-fairness.json,preceptor.json,request.json,sample.json}"
    SEEDS="${SEEDS:-1001,1002}"
    PROFILE="wall"
    WALL_SECONDS="${WALL_SECONDS:-180}"
    OPTA_EVALUATIONS="${OPTA_EVALUATIONS:-50000}"
    POJO_EVALUATIONS="${POJO_EVALUATIONS:-50000}"
    WARMUP="${WARMUP:-true}"
    ;;
  fixed)
    PARTITION="${PARTITION:-development-fixed}"
    DATASETS="${DATASETS:-fairness.json,preceptor.json,request.json,sample.json}"
    SEEDS="${SEEDS:-901,902}"
    PROFILE="fixed"
    WALL_SECONDS="${WALL_SECONDS:-60}"
    : "${OPTA_EVALUATIONS:?fixed에는 Opta score-calculation 예산이 필요합니다.}"
    : "${POJO_EVALUATIONS:?fixed에는 POJO complete-candidate 예산이 필요합니다.}"
    WARMUP="${WARMUP:-true}"
    ;;
  *)
    echo "사용법: $0 {pilot|wall-60|wall-120|wall-180|fixed}" >&2
    exit 2
    ;;
esac

OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/benchmark-artifacts/phase6/hybrid-vnd-long/$MODE/$TIMESTAMP}"
CANDIDATES="${CANDIDATES:-ALNS_ONLY,ALNS_THEN_CHANGE_SWAP,ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS}"
mkdir -p "$OUTPUT_DIR"

cd "$ROOT_DIR"
echo "Phase 6 ALNS → Change/Swap → ordered VND benchmark"
echo "  mode/profile: $MODE / $PROFILE"
echo "  partition: $PARTITION"
echo "  datasets: $DATASETS"
echo "  seeds: $SEEDS"
echo "  candidates: $CANDIDATES"
echo "  output: $OUTPUT_DIR"

./mvnw \
  -Dtest=Phase6HybridVndLongBenchmarkTest \
  -Dphase6.hybrid-vnd.enabled=true \
  -Dphase6.hybrid-vnd.profile="$PROFILE" \
  -Dphase6.hybrid-vnd.datasets="$DATASETS" \
  -Dphase6.hybrid-vnd.seeds="$SEEDS" \
  -Dphase6.hybrid-vnd.candidates="$CANDIDATES" \
  -Dphase6.hybrid-vnd.wall-seconds="$WALL_SECONDS" \
  -Dphase6.hybrid-vnd.opta-evaluations="$OPTA_EVALUATIONS" \
  -Dphase6.hybrid-vnd.pojo-evaluations="$POJO_EVALUATIONS" \
  -Dphase6.hybrid-vnd.warmup="$WARMUP" \
  -Dphase6.hybrid-vnd.partition="$PARTITION" \
  -Dphase6.hybrid-vnd.output="$OUTPUT_DIR/raw.jsonl" \
  -Dphase6.hybrid-vnd.summary="$OUTPUT_DIR/summary.json" \
  -Dphase6.hybrid-vnd.report="$OUTPUT_DIR/report.md" \
  test

echo "완료: $OUTPUT_DIR/{raw.jsonl,summary.json,report.md}"
