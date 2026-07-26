#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:-smoke}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

cd "$ROOT_DIR"

case "$MODE" in
  smoke)
    DATASETS="${DATASETS:-fairness.json}"
    TRAINING_SEEDS="${TRAINING_SEEDS:-201}"
    VALIDATION_SEEDS="${VALIDATION_SEEDS:-301}"
    TRAINING_EVALUATIONS="${TRAINING_EVALUATIONS:-20}"
    VALIDATION_EVALUATIONS="${VALIDATION_EVALUATIONS:-50}"
    VALIDATION_CANDIDATES="${VALIDATION_CANDIDATES:-3}"
    COMPARISON_CANDIDATES="${COMPARISON_CANDIDATES:-1}"
    COMPARISON_POJO_EVALUATIONS="${COMPARISON_POJO_EVALUATIONS:-50}"
    OPTAPLANNER_EVALUATIONS="${OPTAPLANNER_EVALUATIONS:-20000}"
    WALL_CLOCK_SECONDS="${WALL_CLOCK_SECONDS:-0}"
    WARMUP="${WARMUP:-false}"
    ;;
  tune)
    DATASETS="${DATASETS:-fairness.json,preceptor.json,request.json,sample.json}"
    TRAINING_SEEDS="${TRAINING_SEEDS:-201,202}"
    VALIDATION_SEEDS="${VALIDATION_SEEDS:-301,302}"
    TRAINING_EVALUATIONS="${TRAINING_EVALUATIONS:-1000}"
    VALIDATION_EVALUATIONS="${VALIDATION_EVALUATIONS:-5000}"
    VALIDATION_CANDIDATES="${VALIDATION_CANDIDATES:-6}"
    COMPARISON_CANDIDATES="${COMPARISON_CANDIDATES:-2}"
    COMPARISON_POJO_EVALUATIONS="${COMPARISON_POJO_EVALUATIONS:-5000}"
    OPTAPLANNER_EVALUATIONS="${OPTAPLANNER_EVALUATIONS:-2296836}"
    WALL_CLOCK_SECONDS="${WALL_CLOCK_SECONDS:-10}"
    WARMUP="${WARMUP:-true}"
    ;;
  *)
    echo "사용법: $0 {smoke|tune}" >&2
    exit 2
    ;;
esac

OUTPUT_DIR="${OUTPUT_DIR:-benchmark-artifacts/phase6/tuning/${MODE}/${TIMESTAMP}}"

echo "Phase 6 SA/ALNS successive racing"
echo "  mode: $MODE"
echo "  datasets: $DATASETS"
echo "  training seeds: $TRAINING_SEEDS"
echo "  validation seeds: $VALIDATION_SEEDS"
echo "  training/validation eval: $TRAINING_EVALUATIONS/$VALIDATION_EVALUATIONS"
echo "  comparison ALNS/Opta eval: $COMPARISON_POJO_EVALUATIONS/$OPTAPLANNER_EVALUATIONS"
echo "  wall-clock seconds: $WALL_CLOCK_SECONDS"
echo "  output: $OUTPUT_DIR"

./mvnw \
  -Dtest=Phase6AlnsHyperparameterTuningTest \
  -Dphase6.alns-tuning.enabled=true \
  -Dphase6.alns-tuning.datasets="$DATASETS" \
  -Dphase6.alns-tuning.training-seeds="$TRAINING_SEEDS" \
  -Dphase6.alns-tuning.validation-seeds="$VALIDATION_SEEDS" \
  -Dphase6.alns-tuning.training-evaluation-limit="$TRAINING_EVALUATIONS" \
  -Dphase6.alns-tuning.validation-evaluation-limit="$VALIDATION_EVALUATIONS" \
  -Dphase6.alns-tuning.validation-candidates="$VALIDATION_CANDIDATES" \
  -Dphase6.alns-tuning.comparison-candidates="$COMPARISON_CANDIDATES" \
  -Dphase6.alns-tuning.comparison-pojo-evaluation-limit="$COMPARISON_POJO_EVALUATIONS" \
  -Dphase6.alns-tuning.optaplanner-evaluation-limit="$OPTAPLANNER_EVALUATIONS" \
  -Dphase6.alns-tuning.wall-clock-seconds="$WALL_CLOCK_SECONDS" \
  -Dphase6.alns-tuning.warmup="$WARMUP" \
  -Dphase6.alns-tuning.output-directory="$OUTPUT_DIR" \
  test

echo "완료"
echo "  raw: $OUTPUT_DIR/raw.jsonl"
echo "  summary: $OUTPUT_DIR/summary.json"
echo "  report: $OUTPUT_DIR/report.md"
