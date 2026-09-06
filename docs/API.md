# API 명세

이 문서는 공개 API의 계약과 그 의도를 설명합니다. 결정 과정의 상세한 흐름은 `JOURNAL.md`에 있습니다.

## 경로 규약

공개 API는 버전 접두사를 붙여 `/api/v1/**` 아래에 두고, 운영·관리용 엔드포인트는 버전 없이 `/internal/**`
아래에 둡니다. 호환성을 관리해야 하는 공개 계약과 그렇지 않은 운영 도구를 경로에서부터 구분하기
위해서입니다. 공개 API의 응답 형태를 바꿔야 할 때는 기존 소비자를 깨뜨리지 않도록 새 버전(`/api/v2`)을
여는 것을 원칙으로 합니다.

## 통합 검색

```
GET /api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0
```

날짜와 인원만으로 보유 숙소 전체를 여러 공급사에 병렬 조회하고, 표준 모델(`StayProduct`)로 정규화·병합해
반환합니다. 지역·키워드 필터, 정렬·페이징은 범위 밖입니다. 경로와 파라미터 이름은 외부에 약속된 최소
계약이라 고정이며, URL의 `stays`는 StayProduct들의 컬렉션을 뜻하는 짧은 복수형입니다.

### 요청 파라미터

| 파라미터 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `checkIn` | date (`YYYY-MM-DD`) | O | |
| `checkOut` | date (`YYYY-MM-DD`) | O | `checkIn`보다 뒤여야 함 — 체크아웃일은 숙박일에 포함되지 않으므로 같은 날은 0박이라 성립 불가 |
| `adults` | int | O | **1 이상** — 성인 미동반 숙박은 법령·업계 관행상 불가하다는 도메인 규칙 |
| `children` | int | X (기본 0) | 0 이상 |

위반은 전부 400으로 거부합니다(아래 오류 포맷 참고). 과거 날짜나 최대 박수 상한 같은 계약에 없는 제약은
두지 않습니다 — 공급사 보호용 방어 상한은 필요가 확인되는 시점에 추가합니다.

### 응답 예시 (200)

```json
{
  "stayProducts": [
    {
      "property": { "id": 1, "name": "Riverside Hotel Seoul" },
      "roomType": { "id": 1, "name": "Deluxe Twin", "maxOccupancy": 2 },
      "breakfastIncluded": false,
      "availability": { "isAvailable": true, "availableRooms": 1 },
      "supplier": "A",
      "price": {
        "totalAmount": 429000,
        "averageNightlyAmount": 143000,
        "currency": "KRW"
      }
    },
    {
      "property": { "id": 3, "name": "Riverside Hotel Seoul" },
      "roomType": { "id": 3, "name": "Deluxe Twin Room", "maxOccupancy": 2 },
      "breakfastIncluded": true,
      "availability": { "isAvailable": false, "availableRooms": 0 },
      "supplier": "B",
      "price": {
        "totalAmount": 452000,
        "averageNightlyAmount": 150666,
        "currency": "KRW"
      }
    }
  ],
  "errors": []
}
```

상품 항목의 중첩 구조는 표준 모델([DOMAIN_MODEL.md](DOMAIN_MODEL.md))의 네 단위 조합을 그대로
비춥니다 — 숙소(`property`) × 객실(`roomType`)이 상품의 정체성을, 요금(`price`)과
가용성(`availability`)이 검색 조건에서의 판매 정보를 담습니다.

첫 항목은 예약 가능한 상품이고, 두 번째 항목은 **확정 매진** 상품입니다 — 매진도 응답에 노출됩니다(아래
노출 정책). 같은 실제 호텔이라도 공급사가 다르면 별개 상품(별개 내부 id)으로 나란히 노출되며, 조식·총액
같은 조건 차이를 보고 고객이 직접 비교합니다(근거는 [DOMAIN_MODEL.md](DOMAIN_MODEL.md)).

### 응답 필드

| 필드 | 의미 |
|---|---|
| `stayProducts[].property.id` / `.name` | 내부 숙소 식별자(공급사 코드가 아닌 자사 대리키)와 숙소명 |
| `stayProducts[].roomType.id` / `.name` | 내부 객실 타입 식별자와 객실 타입명 |
| `stayProducts[].roomType.maxOccupancy` | 객실 1실의 최대 수용 인원(성인+아동 합산) |
| `stayProducts[].supplier` | 출처 공급사 (`A` / `B`) |
| `stayProducts[].breakfastIncluded` | 조식 포함 여부 — 돈이 아니라 상품의 조건이라 `price` 밖, 가격 비교 시 조건 차이를 드러냄 |
| `stayProducts[].availability.isAvailable` | 예약 가능 여부 — **서버가 보장하는 편의 파생값** (진실은 `availableRooms`) |
| `stayProducts[].availability.availableRooms` | 요청 기간 전체를 통으로 예약할 수 있는 객실 수. **0이면 확정 매진** |
| `stayProducts[].price.totalAmount` | 숙박 기간 전체의 세금 포함 총액 — **정산·결제 금액의 기준** |
| `stayProducts[].price.averageNightlyAmount` | 평균 1박가 = 총액 ÷ 박수(내림). 표시용 파생값이라 평균×박수 ≠ 총액일 수 있음 |
| `stayProducts[].price.currency` | ISO 4217 통화 코드. 환산하지 않고 원 통화 그대로(현재 범위는 KRW) |
| `errors[]` | 조회에 실패한 공급사와 사유. 비어 있으면 전체 성공 |

### 가용성 노출 정책

도메인의 가용성 판정은 3상태이며, wire에는 그 결과가 `availability.availableRooms` 숫자 하나로
직렬화됩니다.

| 도메인 상태 | 조건 | 응답에서 |
|---|---|---|
| `AVAILABLE` | 날짜별 잔여의 최소값 ≥ 1 | `availableRooms ≥ 1`로 노출 |
| `SOLD_OUT` | 모든 날짜 데이터가 있고 최소값 = 0 | `availableRooms: 0`으로 **노출** — 취소 알림 등 후속 기능의 진입점 |
| `UNDETERMINED` | 요청 기간의 날짜 누락 | **응답에서 제외** — 매진이라 단정하는 것도 거짓이므로 |

wire에서 상태가 숫자로 완전히 유도되므로 별도 status 필드는 두지 않고, 프론트 편의를 위한
`isAvailable`(boolean)만 파생값으로 함께 내려갑니다.

### 부분 실패의 표현

여러 공급사를 병렬 조회하므로 일부 실패는 정상 흐름입니다. 성공한 공급사의 결과는 그대로 내려가고, 실패
사실은 `errors`에 구조화되어 드러납니다.

```json
{
  "stayProducts": [ { "supplier": "B", "...": "..." } ],
  "errors": [ { "supplier": "A", "reason": "timeout" } ]
}
```

- `reason`은 짧은 분류 문자열입니다(예: `timeout`, `HTTP 503`, `resultCode E503`). 공급사 실패는 외부
  사실이라 노출하되, 내부 구현 상세(스택 트레이스 등)는 담지 않습니다.
- **전 공급사가 실패해도 HTTP는 200**입니다 — `stayProducts: []`에 `errors`가 전원 기록됩니다. 부분과
  전체는 정도의 차이일 뿐이라 상태 코드를 가르면 클라이언트 처리 경로만 늘어납니다. 이상 감지는 상태
  코드가 아니라 지표로 합니다.
- 검색 대상 매핑이 비어 있을 때도 200 + 빈 배열입니다 — 오류가 아니라 "결과 없음"입니다.

## 오류 응답

요청 자체가 잘못됐거나 서버가 예기치 못하게 실패한 경우는 모두 아래 한 가지 포맷입니다. 검증 실패,
파라미터 누락, 타입 불일치, 예기치 못한 예외가 전부 같은 모양으로 나갑니다. 존재하지 않는 경로(404)나
허용되지 않은 메서드(405)처럼 프레임워크가 이미 상태를 판정한 경우도 상태 코드는 보존한 채 같은 포맷으로
변환됩니다 — 클라이언트 실수를 500으로 뭉개지 않습니다.

```json
// 400 — 클라이언트가 고칠 수 있으므로 구체적 사유
{ "status": 400, "error": "Bad Request", "message": "checkOut은 checkIn보다 뒤여야 합니다" }
{ "status": 400, "error": "Bad Request", "message": "필수 파라미터가 누락되었습니다: 'adults'" }

// 500 — 내부 상세는 로그에만, 응답은 불투명
{ "status": 500, "error": "Internal Server Error", "message": "내부 오류가 발생했습니다" }
```

## 운영 엔드포인트

### 매핑 수동 재동기화

```
POST /internal/properties/sync
```

공급사 숙소 목록을 다시 받아 내부 `property`/`room_type` 매핑을 갱신합니다. 멱등하며, 공급사별 결과 요약을
반환합니다. 계약 밖 데이터(빈 이름 등)는 그 레코드만 건너뛰고 `skipped`로 집계합니다 — 깨진 표시를
노출하느니 그 숙소가 검색에서 빠지는 쪽을 택한, 가용성 판정과 같은 보수 원칙입니다.

```json
[
  { "supplier": "A", "ok": true, "properties": 2, "roomTypes": 2, "skipped": 0, "error": null },
  { "supplier": "B", "ok": true, "properties": 1, "roomTypes": 1, "skipped": 0, "error": null }
]
```

### 헬스 체크

`GET /actuator/health` — 기동 확인용. API 문서 자동화와 지표 노출은 해당 기능 논의 때 결정합니다.
