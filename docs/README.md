# 프로젝트 위키

이 디렉토리는 supplier-sync의 설계와 동작을 주제별로 설명하는 위키입니다. 루트 `README.md`가 프로젝트
소개와 빌드·실행 방법, 설계 결정의 요약을 담는다면, 여기서는 각 주제를 깊이 있게 다룹니다. 개발
규칙과 컨벤션은 `CLAUDE.md`에, 시간 순 의사결정 과정은 `JOURNAL.md`에 있습니다.

## 문서 목록

| 문서 | 내용 |
|---|---|
| [TECH_STACK.md](TECH_STACK.md) | 기반 스택과 각 선택의 근거, 택하지 않은 대안 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 레이어 구조, 두 단계 어댑터 경계, 핵심 흐름, Mock 구성 |
| [DOMAIN_MODEL.md](DOMAIN_MODEL.md) | 통합 숙박 상품 모델 — 교집합 원칙, 요금·식별자·가용성 판정, 용어집 |
| [ERD.md](ERD.md) | 영속 스키마 — 무엇을 저장하고 무엇을 저장하지 않는가, 제약과 근거 |
| [API.md](API.md) | 검색 API 계약 — 검증 규칙, 응답 구조, 부분 실패 표현, 오류 포맷 |
| [INTEGRATION.md](INTEGRATION.md) | 공급사 연동 — 실패 판정 통일, 타임아웃·병렬 정책, 신규 공급사 런북 |
| [MONITORING.md](MONITORING.md) | 지표 수집과 알람 설계 |
| [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) | 보류된 결정 모음 — 재시도·서킷, 캐시·확장, 병합, 환율 |

## 권장 읽기 순서

- **처음 보는 분**: TECH_STACK → ARCHITECTURE → DOMAIN_MODEL → ERD → API 순서로 읽으면 전체 그림이
  잡힙니다.
- **공급사 연동을 다루는 분**: INTEGRATION과 MONITORING을 먼저 읽으세요. 신규 공급사 추가 런북이
  INTEGRATION에 있습니다.
- **확장을 검토하는 분**: DESIGN_DECISIONS에 보류된 결정들과 도입 시점의 출발점이 정리되어 있습니다.
