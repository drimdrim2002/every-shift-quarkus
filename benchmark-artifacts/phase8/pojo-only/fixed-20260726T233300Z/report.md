# Phase 8 POJO_ONLY 운영 입력 benchmark

- 판정: **PASS**
- profile: `fixed-evaluations`
- candidate: `ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN`
- seed: `1701`
- evaluation budget: `5000`
- 비교: `hard > soft[0] > soft[1] > soft[2] > soft[3]`

| dataset | score | elapsed ms | evaluations | full verified | integrity failures |
|---|---|---:|---:|---:|---:|
| fairness.json | `[0]hard/[0/0/-7630/0]soft` | 11182 | 5000 | true | 0 |
| preceptor.json | `[0]hard/[0/-4800/-6467/0]soft` | 8031 | 5000 | true | 0 |
| request.json | `[0]hard/[0/0/-5281/0]soft` | 10972 | 5000 | true | 0 |
| sample.json | `[0]hard/[0/0/-5715/0]soft` | 11933 | 5000 | true | 0 |
