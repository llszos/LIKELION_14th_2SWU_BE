# 상황 시간 모델 전환 — Flyway V3

2026-11-17 기준 구현과 병합 전 확인 사항입니다. 실제 실행 파일은
`src/main/resources/db/migration/V3__convert_situation_time_model.sql`입니다.
이 문서와 `migration-review.sql`은 자동 실행되지 않으며, 학교 컴퓨터에서는 DB 검증을 실행하지 않았습니다.

## 시간 계약

- `startAt` / `start_at`: 상황 시작 예정 시각.
- `endAt` / `end_at`: 상황 종료 예정 시각. 실제 종료 처리 시각이 아닙니다.
- `endedAt` / `ended_at`: 직접 종료 또는 자동 종료가 실제 처리된 시각. V3는 이 값을 변경하지 않습니다.
- 예정 기간은 Asia/Seoul 현지 날짜시각의 `[startAt, endAt)`이며 `startAt < endAt`이어야 합니다.
- MySQL 타입은 `DATETIME(6)`, Java 타입은 오프셋 없는 `LocalDateTime`입니다. 서울 Clock/JDBC 설정을 유지합니다.
- C 요청 일정은 `상황.startAt <= 요청.startsAt < 요청.endsAt <= 상황.endAt`입니다. 요청 종료는 endAt과 같아도 됩니다.
- Reader는 endAt 경과만으로 저장된 ACTIVE/ENDED 상태나 참여 상태를 변경하지 않습니다. 자동 종료 주기와 지연 중 접근 정책은 별도 확인 대상입니다.

## V3의 실제 동작

1. 임시 guard 테이블로 잘못된 날짜, 역전 기간, 다음 날 계산 overflow를 거부합니다.
2. nullable `start_at/end_at DATETIME(6)` 컬럼을 추가합니다.
3. 기존 `start_date`는 당일 00:00, `end_date`는 다음 날 00:00으로 변환합니다. 기존 종료일 전체를 포함하는 의미를 보존하며 UTC 변환은 하지 않습니다.
4. 변환 결과의 NULL·기간·원본 대응을 검사한 뒤 새 컬럼을 NOT NULL로 변경합니다.
5. 기존 CHECK를 `chk_situations_time_period CHECK (start_at < end_at)`로 교체하고 **구 날짜 컬럼을 제거합니다.** 구 컬럼 보존·nullable 전환은 현재 구현이 아닙니다.
6. guard 테이블을 제거합니다. ID, 행 수, ended_at 및 다른 업무 컬럼은 변경하지 않습니다.

MySQL CHECK가 강제되는 버전이 필요하며 서버 대상은 MySQL 8.4입니다. DDL은 암묵적으로 커밋되고 이 마이그레이션은 재실행용 SQL이 아닙니다. 실패하면 부분 적용 상태와 Flyway 이력을 확인해야 합니다.

## Flyway 및 의존성

- #131의 V1은 develop에 병합됐습니다. 최신 develop의 local/test는 `ddl-auto: validate`이며 스키마 생성은 Flyway가 담당합니다. Flyway 비활성·create-drop을 적용 지침으로 사용하지 않습니다.
- 현재 #116 브랜치는 #122 미병합 때문에 최신 develop을 아직 반영하지 않았습니다. 이 브랜치에 남은 이전 설정과 V1/V2 부재는 통합 대기 상태이며 최종 배포 설정이 아닙니다.
- 순서: **#122(V2) develop 병합 → #116에 최신 develop 반영 → C 담당 #137을 #116 브랜치에 통합 → #116 전체 CI·민주 승인 → #116(V3) develop 병합 → #118(V4)**.
- #122가 미병합이면 develop 반영을 보류합니다. #137 통합은 별도 담당 작업이며 이 문서 수정에 포함하지 않습니다.
- #116과 #137이 함께 변경하는 `RequestScheduleValidator`의 ID·SituationSummary overload를 모두 보존해야 합니다. 생성·상세 응답과 날짜 fixture도 새 시간 계약에 맞춰야 합니다.
- #115 참여자 ID 조회와 B #94/#95/#103/#118의 Reader·Repository·fixture 변경도 기능을 보존하며 통합합니다.

## 최종 검증 계획

- GitHub CI에서 실제 V1 → V2 → V3 적용 및 Hibernate validate 기동 확인.
- `SchemaConstraintTest`: start_at/end_at 사용, 같은 시각·역전 거부, 같은 날의 시작 < 종료 허용. 기존 관계·참여·지정·수정안 제약 검증 보존.
- 빈 DB와 기존 데이터에 대한 변환 결과, 행 수·PK·ended_at 보존, 구 컬럼 제거, 새 INSERT·조회 확인.
- 자정·월말·연말·마이크로초 경계, 오전/오후 시간 보존과 C 요청 기간 검증 확인.
- #137 통합 후 전체 컴파일·회귀 및 상세 응답 명세/FE 공유 확인.

로컬 컴파일·비DB 테스트는 MySQL 제약, Flyway 적용, 데이터 변환 검증의 증거가 아닙니다. Docker/Testcontainers/MySQL 테스트는 학교 컴퓨터에서 실행하지 않습니다.

## 적용 및 복구

공유 DB 적용은 팀의 배포 절차에 따라 관련 쓰기를 중지하고 백업·복구 방법을 확인한 뒤 진행합니다. 구·신 애플리케이션을 혼재하지 않습니다.
V3는 구 컬럼을 제거하므로 구 이미지 재배포만으로 복구되지 않습니다. MySQL DDL의 일반 ROLLBACK을 가정하지 않으며, 실패 시 쓰기를 중지하고 백업/PITR 및 새 변경분 보존을 포함한 복구 계획으로 처리합니다.

## 과거 검토 자료

`migration-review.sql`은 Flyway V3 확정 전의 수동 매핑·구 컬럼 보존 검토 초안입니다. 실행본이나 현재 적용 절차가 아니며, 실제 V3와 충돌하면 V3를 기준으로 합니다.
