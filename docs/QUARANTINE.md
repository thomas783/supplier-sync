# 결함 데이터 격리 (관문 검증·지표는 구현됨 · 격리 저장은 구현 예정)

이 문서는 공급사 응답의 **결함 항목 격리** 설계를 담습니다. **관문 검증**(뒤쪽 불변식의 선제 이관 —
`ConversionGate`)과 **결함 지표**(`supplier.stayproducts.quarantined` 카운터)는 구현되어 있고, **격리
저장**(원시 페이로드 보존 테이블)만 설계 확정된 구현 예정입니다. 격리 저장은 캐시([CACHING.md](CACHING.md))와
독립적으로 먼저 도입할 수 있습니다 — 트리거 대기가 아니라 다음 구현 라운드의 후보입니다.

## 남은 문제 — 결함 항목의 원시 페이로드가 보존되지 않는다

관문이 걸러낸 항목은 사유별 카운터로 집계되고 warn 로그로 남지만, **원시 페이로드**는 보존되지 않습니다 —
결함이 급증한 것은 카운터로 보이되(어느 사유인지까지), 그 구체적인 응답 원본이 없어 공급사에 문의할
증빙이 부족합니다. 이것이 격리 저장이 채울 공백입니다.

## 관문의 위치 — 각 경로의 저장 직전 경계

`ConversionGate` 는 결함 판정의 **단일 권위**이고(규칙은 `DomainInvariants` 하나), 검색·동기화 두 경로가
그 하나를 각자의 경계에서 부릅니다. "실패는 어댑터에서 통일된다"는 경계 원칙([ARCHITECTURE.md](ARCHITECTURE.md))의
연장으로, **데이터 품질의 판정도 다운스트림(캐시·저장)에 결함이 닿기 직전**에 둡니다.

```
검색:   공급사 호출 → 변환(중간 타입) → 관문(ConversionGate.admit) ─┬─ 통과 → 캐시 → 정규화 → 응답
                                                                    └─ 결함 → 격리 + 스킵
동기화: 공급사 호출 → 변환(중간 타입) → 저장 직전 PropertyMappingService(ConversionGate.defectOf)
                                                                    ├─ 통과 → upsert
                                                                    └─ 결함 → 스킵 + skipped 집계
```

관문은 원시 데이터가 아니라 **변환을 마친 중간 타입 위에서** 검증합니다 — 그래야 검사·로그가 공급사 중립
어휘(supplierPropertyCode 등)로 한 번만 작성됩니다. 검색 경로는 필터 결과가 곧장 캐시로 흐르므로 어댑터
에서 제외하고, 동기화 경로는 관측값(`skipped`)이 저장 시점에 만들어지므로 그 자리에서 제외·집계합니다 —
위치는 달라도 규칙 권위는 하나입니다. 예외는 중복 날짜 하나 — Map 으로 접히며 사라지는 결함이라 원시 날짜
목록(rawDates)을 관문에 함께 넘깁니다.

- **검증의 왼쪽 이동**: 지금 조립 시점(정규화의 값 객체 불변식)에 예외로 터질 수 있는 값 결함(가격
  음수 등)을 변환 단계의 검증으로 앞당깁니다. 그 결과 **정규화는 예외를 던지지 않는 전함수**가 됩니다 —
  남는 동작은 제외(미매핑·미확정)와 판정뿐. 이 계약이 캐시의 "변환·검증 통과 = 기록 자격"
  ([CACHING.md](CACHING.md)) 배치의 전제입니다.

## 관문의 완전성 — 뒤쪽 검증의 전수 이관 (성립 조건)

이 설계가 말이 되려면 **정규화·조립 단계가 검증하는 모든 에러 케이스를 어댑터가 response 를 내놓기
전에 선제로 잡아야 합니다.** 하나라도 뒤에 남으면 그 케이스는 격리되지 않은 채 캐시에 기록되고, 읽기
시점마다 반복해서 터집니다.

완전성은 규율이 아니라 **구조로 보장합니다** — 규칙 술어는 도메인 최내층의 `DomainInvariants` 하나에만
정의되고, **모든 검증 지점이 전부 그것을 읽습니다**: ① 검색 경로의 변환 관문(어댑터에서 `ConversionGate.admit`),
② 동기화 경로의 결함 판정(저장 직전 `PropertyMappingService` 에서 `ConversionGate.defectOf` — 제외하며
`skipped` 로 집계), ③ 값 객체 init(`Price` 등), ④ 엔티티 생성·저장. 규칙 변경은 한 곳에서 끝나고, 지점
간 규칙 어긋남은 만들 수 없습니다. (초기 구현은 동기화 경로가 Bean Validation 애노테이션으로 같은 규칙을
독립 정의했으나 — 규칙 이중화이자 검색 관문 도입 후 `skipped` 를 0으로 만드는 회귀였다 — `defectOf` 로
통합해 제거했습니다.) 인벤토리와 사유 코드:

| 이관 전 위치 | 검증 | 관문(DomainInvariants)으로 이관 | 사유 코드 |
|---|---|---|---|
| `Price.of` require | 총액 음수 금지 | `grossTotalAmount >= 0` | `INVALID_PRICE` |
| `Price.of` require | currency 공백 금지 | `currency.isNotBlank()` | `MISSING_FIELD` |
| (은폐됨 — judge 가 매진으로 흡수) | 음수 재고 | `remainingByDate` 값 `>= 0` — 지금은 던지지 않지만 **결함이 매진으로 조용히 둔갑하는 케이스**라 관문에서 격리 | `INVALID_INVENTORY` |
| 어댑터 (기존) | 중복 날짜 | Map 으로 접히기 전에만 보이는 결함이라, 어댑터가 원시 날짜 목록(rawDates)을 admit 에 함께 넘긴다 | `DUPLICATE_DATE` |
| 동기화 경로 Bean Validation (`@NotBlank`·`@Positive`) | 공백 코드·이름, `maxOccupancy > 0` | `PropertyMappingService` 가 `defectOf` 로 판정·집계(`skipped`) — 애노테이션 제거 | `MISSING_FIELD` / `INVALID_OCCUPANCY` |

두 가지 경계 사례를 명시합니다:

- **역직렬화 실패는 두 층위입니다 — 봉투는 호출 실패, 항목은 결함** ⑴ 봉투(골격) 실패: 본문이 JSON 이
  아니거나(프록시 오류 페이지·잘린 본문) 응답 구조 자체가 계약 밖 — 데이터 결함이 아니라 응답의
  불성립이므로 호출 실패(SupplierCallException, 재시도 불가)가 맞습니다. ⑵ 항목 실패: 봉투는 정상인데
  특정 항목의 필드가 계약 밖 — **개념상 값 결함과 같은 층위라 격리 대상이 맞습니다**(음수 가격과 종류가
  같고 정도만 다름). 다만 현재는 엄격 DTO 라 Jackson 이 항목 하나의 위반에도 전체 파싱을 죽여 ⑵가 ⑴로
  승격됩니다 — 개념이 아니라 파싱 방식의 부작용입니다. ⑵를 결함으로 대접하려면 **관용 파싱**(항목 DTO 를
  전 필드 nullable 로 받아 봉투를 절대 안 죽게 하고, 필수 필드의 존재·형태 검증을 관문으로 이관 —
  `MALFORMED_ITEM` 사유 추가)이 필요하며, DTO 타입 안전성 상실이라는 대가가 있어 격리 기록과 함께 구현
  예정으로 묶습니다.
- **정규화의 불변식 require 는 제거하지 않습니다** — 관문 이관 후에도 값 객체의 require 는 남겨,
  정규화에서 예외가 나면 그것을 "관문 누락 버그의 신호"로 취급합니다(error 로그 + 해당 결과 미기록).
  이중 검증이 아니라 관문 완전성의 감시자입니다 — 규칙 자체는 단일 원천이라 어긋날 수 없으므로, 이
  감시자가 잡는 것은 하나뿐입니다: **관문 호출을 빠뜨린 경로**.
- **실패와 제외의 구분** (캐시와 공유하는 원칙): 격리 대상은 **결함**(중복 날짜·값 위반·필수 누락)만
  입니다. 미매핑으로 응답에서 빠지는 상품은 결함이 아니라 판정의 결과이므로 격리하지 않습니다 —
  카운터로 관측하고 원자료는 캐시에 보존합니다.
- **왼쪽 이동의 기준 — 속성이냐 해석이냐**: 어댑터로 앞당기는 것은 **데이터의 시불변 속성**(결함
  여부 — 언제 누가 봐도, 정책이 바뀌어도 결함)뿐입니다. 가용성 판정·매핑 치환 같은 **해석**은 읽기
  시점에 남습니다 — 해석의 규칙은 바뀔 수 있어서 결과를 구워 두면 안 되고([CACHING.md](CACHING.md)
  "바뀔 수 있는 것은 굽지 않는다"), 판정의 결과는 응답에 실리는 분류(매진 표시)거나 시변 제외(미확정 —
  정책이 바뀌면 다음 조회부터 달라지는 제외)라 어느 쪽이든 읽기 시점의 몫이기 때문입니다. 그래서 AvailabilityPolicy 는 관문으로 오지 않고, 관문이 보장하는 입력
  전제(비음수 재고) 위에서 조회 시점마다 동작합니다.

## 선례 — 이름 있는 패턴이다

격리 기록은 우리가 고안한 것이 아니라 두 계보의 표준 관행이며, 설계는 그 대조 위에 서 있습니다.

- **스트리밍 계보 — Dead Letter Queue** ([Confluent — Kafka Connect Error Handling and DLQ](https://www.confluent.io/blog/kafka-connect-deep-dive-error-handling-dead-letter-queues/),
  [KIP-298](https://cwiki.apache.org/confluence/display/KAFKA/KIP-298%3A+Error+Handling+in+Connect)):
  `errors.tolerance=all` 이면 역직렬화·변환에 실패한 레코드가 태스크를 죽이는 대신 스킵되어 DLQ 토픽으로
  가고, **원본 키·값·헤더가 보존**되며 예외 메시지 등 실패 메타데이터가 헤더로 붙는다. → "봉투는 살리고
  항목만 격리"(관용 파싱)와 "원시 페이로드 + 사유 코드 보존"의 직접 선례.
- **배치 계보 — 격리 테이블 / badRecordsPath** ([Databricks — bad records](https://learn.microsoft.com/azure/databricks/ingestion/bad-records),
  [Theodo — Strategies to manage invalid records](https://www.theodo.com/en-fr/blog/data-pipeline-strategies-to-manage-invalid-records)):
  무효 행을 조용히 버리지 않고 격리 테이블로 라우팅해 조사·통계·업스트림 보고를 가능하게 한다. →
  DB 테이블 저장소 선택의 선례.
- **의도적 이탈 두 곳**: ① 표준은 재처리(재주입·재적재)를 주요 목적으로 두지만, 그것은 그 레코드가
  유일한 사본인 파이프라인의 요구다 — 조회형인 우리는 다음 검색이 자연 재시도라 재주입이 무의미하고,
  목적이 증빙·관측으로 좁혀진다. ② DLQ 는 발생 건마다 append 지만, 우리는 같은 결함이 검색마다
  재발하므로 관측 도구들의 오류 그룹핑 관행(동일 결함을 묶어 횟수·최초/최근 시각)을 가져와 upsert 로
  행 폭주를 막는다.

## 격리 레코드 — 무엇을 남기나

| 필드 | 내용 |
|---|---|
| supplier | 공급사 |
| stage | 결함이 걸린 단계 (지금은 `conversion` 하나 — 확장 대비 필드로 둠) |
| reason | 사유 코드 (`DUPLICATE_DATE`, `INVALID_PRICE`, `MISSING_FIELD` …) |
| supplierPropertyCode / supplierRoomTypeCode | 공급사 측 상품 식별자 |
| rawPayload | 원시 항목 JSON — 공급사 문의 증빙의 핵심 |
| requestContext | 기간·인원 등 재현에 필요한 요청 축 |
| firstSeenAt / lastSeenAt / occurrenceCount | 최초·최근 발생과 횟수 |

**중복 억제가 필수입니다** — 같은 결함은 검색마다 재발하므로 발생 건마다 행을 쌓으면 폭주합니다.
(supplier, stage, reason, 상품 식별자) UNIQUE 로 upsert 하여 `occurrenceCount` 와 `lastSeenAt` 만
갱신합니다. 이 레코드 구조가 "지금도 재발 중인가"를 그대로 알려 줍니다.

**기록 호출의 자리는 가짜 호출 주석으로 표시되어 있습니다** — 격리 레코드의 핵심인 원시 항목 페이로드와
요청 컨텍스트는 검색 경로의 경우 **어댑터만 알기** 때문입니다. 검색 어댑터의 `admit` 호출부 2곳(A·B 의
`fetchStayProducts`)에 `quarantineRecorder.record(supplier, rawPayload = item, requestContext = query)`
형태의 주석이 자리를 잡고 있습니다 — `admit` 이 null 을 돌려준 항목에 대한 비차단 호출입니다. 동기화
경로는 `PropertyMappingService` 가 이미 원시 레코드와 사유를 손에 쥐고 있으므로(그리고 `skipped` 로
집계하므로) 기록 도입 시 그 스킵 분기에 같은 호출을 붙입니다.

## 저장소 — MySQL 테이블

기존 인프라(MySQL)를 재사용합니다. 로그는 휘발적이고 구조 질의가 어려우며, 큐·객체 스토리지는 현
규모에 과설계입니다. upsert 제약과 보존 정리를 DB 가 자연스럽게 감당합니다. 보존은 `lastSeenAt` 기준
30일 경과 시 정리(재발이 멈춘 결함은 해소된 것)를 출발값으로 둡니다.

## 동작 원칙

- **비차단**: 격리 기록은 검색 응답 경로를 막지 않습니다. 기록 자체가 실패해도 검색은 정상 진행하고
  warn 만 남깁니다 — 격리는 관측 장치이지 파이프라인의 관문 게이트가 아닙니다(관문은 스킵이 수행).
- **재처리는 두지 않습니다**: 검색형 파이프라인은 다음 호출이 자연 재시도라 dead-letter 식 재주입이
  무의미합니다. 격리의 목적은 복구가 아니라 **증빙·관측·수정 근거**입니다 — 공급사 데이터가 고쳐지거나
  어댑터 검증이 조정되면 자연 해소되고, 레코드는 보존 기한으로 정리됩니다.
- **지표**(구현됨): `supplier.stayproducts.quarantined` 카운터(supplier × reason)가 검색·동기화 양쪽
  드롭을 사유별로 집계합니다 — 이 카운터는 전체 격리 저장과 독립적으로 **먼저 도입되어**, 특히 warn
  로그로만 남던 검색 경로 결함을 대시보드에 드러냅니다([MONITORING.md](MONITORING.md)). 미매핑 카운터와
  층위가 다릅니다 — 미매핑은 "우리 매핑의 공백", 격리는 "공급사 데이터의 결함".
