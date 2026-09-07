# 연동 지표와 알람 설계

부분 실패가 정상 흐름인 시스템에서는 "전체 요청이 성공했는가"만으로는 아무것도 알 수 없습니다. 응답이
200이어도 그 안에서 특정 공급사가 조용히 계속 실패하고 있을 수 있으므로, 공급사별 성공률·응답 지연·타임아웃
비율을 분리 관측하는 것이 유일한 이상 감지 수단입니다. 타임아웃(5초)·동시성 상한(16)처럼 "실측 후
조정"으로 미뤄 둔 결정들도 전부 이 지표를 전제로 합니다. 결정 과정의 상세한 흐름은 `JOURNAL.md`에
있습니다.

## 지표 스택 — Micrometer + Prometheus

지표는 Micrometer로 수집하고 `/actuator/prometheus`로 노출합니다. Micrometer는 이미 스택에 포함된
actuator에 내장되어 있어 한계비용이 Prometheus 레지스트리 의존성 하나뿐이고, Prometheus 텍스트 포맷은
수집기가 없어도 curl로 즉시 읽을 수 있어 로컬·리뷰 환경에서도 유효하며, 수집기를 붙이면 그대로
대시보드·알람으로 이어집니다.

검토했다가 택하지 않은 것 — actuator 기본 metrics만(순간값 조회용이라 시계열·알람으로 이어지지 않음), 로그
기반 집계(비율·분포 계산을 로그 파이프라인에 의존하는 우회로), 외부 APM(이 규모에 과함).

노출 보안: 현재 `/actuator/prometheus`는 서비스 포트(8080)에 그대로 열립니다. 실운영이라면 management
포트 분리(`management.server.port`)와 망 수준 접근 제한이 원칙이지만, 인증·인프라가 범위 밖인 현
구성에서는 이 사실의 기록으로 갈음합니다.

## 계측 설계

### 핵심 타이머 — `supplier.stayproducts.fetch`

계측의 우선 대상은 검색 핫패스, 즉 공급사 재고·요금 조회 1건입니다.

```
supplier.stayproducts.fetch (Timer, 백분위 히스토그램 활성)
  supplier = A | B
  outcome  = success | failure | timeout | rate_limited | circuit_open
```

- `outcome`은 어댑터가 통일한 공통 예외의 분류에서 유도합니다 — 무응답이면 `timeout`, 한도
  초과(429/`E429`)는 `rate_limited`(429 관측이 대규모 대응의 전환 트리거로 정의되어 있어
  — [INTEGRATION.md](INTEGRATION.md) — `failure`에 묻으면 트리거를 관측할 수 없습니다), 서킷이 차단한
  호출은 `circuit_open`(원격에 나가지 않았어도 셉니다 — 차단은 곧 상품 노출 축소라 빈도 자체가 신호),
  그 외 실패는 `failure`.
- **기록 단위는 시도(attempt)입니다** — 계측이 서킷 밖·재시도 안에 위치해 재시도의 각 시도가 개별
  기록됩니다. 재시도 대기 시간이 지연 분포에 섞이지 않아 타임아웃 조정용 p95/p99가 공급사의 실제 응답
  분포를 말하고, 재시도로 복구된 순단도 실패 시도로 남습니다.
- 백분위 히스토그램을 켭니다 — 응답 타임아웃 5초를 실측 p95/p99로 조정하기로 한 결정의 전제이며,
  카디널리티(공급사 2 × outcome 5)가 작아 비용이 미미합니다.
- 재시도·서킷 자체의 지표(`resilience4j_retry_*`, `resilience4j_circuitbreaker_*` — 서킷 상태 포함)는
  resilience4j 가 자동 등록해 같은 경로로 노출됩니다.

핵심 3종(공급사별 성공률·응답 지연·타임아웃 비율)은 전부 이 타이머 하나에서 유도됩니다 — 별도 카운터를
두면 타이머 count 와 이중 집계가 되어 어긋날 수 있는 두 원천이 생기므로, 비율·분포는 조회 시점에
계산합니다.

```promql
# 공급사별 성공률 (5분 창)
sum(rate(supplier_stayproducts_fetch_seconds_count{outcome="success"}[5m])) by (supplier)
  / sum(rate(supplier_stayproducts_fetch_seconds_count[5m])) by (supplier)
# 공급사별 응답 지연 p95
histogram_quantile(0.95, sum(rate(supplier_stayproducts_fetch_seconds_bucket[5m])) by (supplier, le))
# 공급사별 타임아웃 비율 (5분 창)
sum(rate(supplier_stayproducts_fetch_seconds_count{outcome="timeout"}[5m])) by (supplier)
  / sum(rate(supplier_stayproducts_fetch_seconds_count[5m])) by (supplier)
```

### 보조 카운터 — 미매핑 스킵, 가용성 판정 분포, 결함 격리

```
supplier.stayproducts.unmapped (Counter)      supplier = A | B, level = property | roomType
supplier.stayproducts.availability (Counter)  supplier = A | B, result = available | sold_out | undetermined
supplier.stayproducts.quarantined (Counter)   supplier = A | B, reason = INVALID_PRICE | MISSING_FIELD | INVALID_INVENTORY | INVALID_OCCUPANCY | DUPLICATE_DATE
```

세 카운터는 상품이 응답에서 빠지는 서로 다른 사유를 가릅니다 — **미매핑**은 "우리 매핑의 공백",
**미확정**은 "재고 데이터 누락", **격리**는 "공급사 데이터의 결함". 층위가 다르므로 운영 액션도 다릅니다.

- **미매핑 스킵**: 숙소 목록에 없던 상품이 재고 응답에 나타나 검색에서 빠질 때 셉니다. 오르면 "동기화가
  밀렸다 — 팔 수 있는 상품이 빠지고 있다"는 신호라, 수동 동기화 트리거라는 운영 액션으로 직결됩니다.
- **가용성 판정 분포**: 엄격 판정이 미확정 상품을 조용히 응답에서 빼는 구조라, 이 분포가 보수적 노출
  정책의 기회비용을 정량화하는 유일한 창입니다. `undetermined` 비율 상승은 공급사 재고 데이터 품질
  저하의 조기 신호입니다.
- **결함 격리**: 변환 관문([QUARANTINE.md](QUARANTINE.md))이 계약 밖 데이터(음수 가격·빈 이름·정원 0·
  중복 날짜 등)를 버릴 때 사유별로 셉니다. 검색 경로(`admit`)와 동기화 경로(`defectOf`) 양쪽 드롭이 여기
  모입니다 — 특히 검색 경로 결함은 이 카운터 없이는 warn 로그로만 남아 대시보드에서 보이지 않았습니다.
  특정 `reason` 급증은 공급사 응답 스키마·데이터 변경의 신호라 공급사 문의로 직결됩니다. (원시 페이로드를
  보존하는 전체 격리 저장은 미구현 — 카운터만 우선 도입.)

지표명의 `availability`는 용어집의 가용성 판정(결과 3종: 가용·매진·미확정)과 정확히 같은 뜻일 때만 씁니다 — 판정 분포 카운터가
그 경우이고, 반대로 조회 타이머는 공급사 A의 원시 엔드포인트명과 혼동될 수 있어
`fetchStayProducts`(용어집의 조회 서술)와 1:1로 맞춘 이름을 씁니다.

### 자동 계측의 함정 — 커스텀 타이머가 필수인 이유

Spring Boot가 WebClient에 자동으로 붙이는 `http.client.requests` 지표는 HTTP 상태 기준이라, **공급사 B의
실패(HTTP 200 + `resultCode`)가 성공으로 집계됩니다.** 실패 판정이 어댑터에서 일어나는 우리 구조에서는 그
판정 결과를 아는 커스텀 타이머만이 진실을 말합니다.

### 계측하지 않는 것

- 숙소 목록 동기화(`fetchProperties`)는 하루 몇 번의 저빈도 배치라 구조화 로그로 충분합니다. 지표는 필요가
  확인되면 추가합니다.
- JVM·GC·톰캣·HTTP 서버 지표는 actuator가 기본 제공하므로 별도 설계 없이 따라옵니다.

## 알람 설계

수집기·알람 시스템을 붙이는 것은 범위 밖이며, 여기서는 "무엇이 이상인지"의 정의(신호와 임계값)를
남깁니다. 임계값은 전부 출발점이고, 운영 분포가 쌓이면 그 근거로 조정합니다.

| 신호 | 조건 | 근거 |
|---|---|---|
| 공급사 성공률 저하 | 5분 창에서 `success` 비율 < 95% | 순단이 아닌 지속 저하의 신호. 계측이 시도 단위라 재시도로 복구된 순단도 실패 시도로 집계됨을 감안해 100%가 아닌 95% |
| 응답 지연 상승 | p95 > 4초 (= 응답 타임아웃 5초의 80%) | 대량 타임아웃의 전조를 미리 알리고, 동시에 타임아웃 값 재검토의 신호로 쓴다 |
| 타임아웃 비율 | 5분 창에서 `timeout` 비율 > 5% | 무응답은 건당 5초씩 검색을 묶는 가장 비싼 실패라 별도 신호로 분리 |
| 서킷 open | open 즉시 (`circuit_open` 발생 또는 `resilience4j_circuitbreaker_state`) | 공급사 차단은 곧 상품 노출 축소 — 즉시 인지 대상 |
| 미매핑 스킵 발생 | 5분 창에서 `unmapped` 증가 지속 | 팔 수 있는 상품이 검색에서 빠지는 중 — 수동 동기화 트리거 검토 |
| 미확정 비율 상승 | 5분 창에서 `undetermined` 비율 > 10% | 공급사 재고 데이터 품질 저하의 조기 신호 — 보수 노출의 기회비용이 커지는 중 |
| 결함 격리 급증 | 특정 `reason` 의 `quarantined` 가 평소 대비 급증 | 공급사 응답 스키마·데이터 변경의 신호 — 어댑터 검증 조정 또는 공급사 문의 대상 |

검색 API 자체의 5xx율·지연 같은 표준 웹 신호는 actuator 기본 지표(`http.server.requests`)로 커버되므로
관례 임계값을 적용합니다. 대시보드는 공급사별 패널(성공률·지연·타임아웃)을 기본 단위로 구성합니다.
