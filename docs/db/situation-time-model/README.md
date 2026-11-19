# 상황 시간 모델 전환 — Flyway V3

현재 V3 구현과 적용·검증 절차를 설명합니다. 실제 실행 파일은
`src/main/resources/db/migration/V3__convert_situation_time_model.sql`입니다.
이 문서와 `migration-review.sql`은 자동 실행 대상에서 제외됩니다.

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
5. 기존 CHECK를 `chk_situations_time_period CHECK (start_at < end_at)`로 교체하고 **구 날짜 컬럼을 제거합니다.**
6. guard 테이블을 제거합니다. ID, 행 수, ended_at 및 다른 업무 컬럼은 변경하지 않습니다.

MySQL CHECK가 강제되는 버전이 필요하며 서버 대상은 MySQL 8.4입니다. DDL은 암묵적으로 커밋되고 이 마이그레이션은 재실행용 SQL이 아닙니다. 실패하면 부분 적용 상태와 Flyway 이력을 확인해야 합니다.

## Flyway 및 의존성

- V1(#131)과 V2(#122)는 이미 `develop`에 병합됐으며, Flyway는 **V1 → V2 → V3** 순서로 적용합니다. Hibernate는 local/test/prod에서 `ddl-auto: validate`로 스키마와 엔티티의 일치 여부를 확인합니다.
- #116에는 #143까지 병합된 `develop`의 변경과 #137의 C 일정 검증 변경이 반영돼 있습니다. `RequestScheduleValidator`의 상황 ID 기반 및 `SituationSummary` 기반 검증은 같은 기간 규칙을 사용합니다.
- 후속 V4(#118)는 V3(#116)가 `develop`에 병합된 뒤 적용합니다.

## 검증 항목

- GitHub CI에서 실제 V1 → V2 → V3 적용 및 Hibernate validate 기동 확인.
- `SchemaConstraintTest`: start_at/end_at 사용, 같은 시각·역전 거부, 같은 날의 시작 < 종료 허용. 기존 관계·참여·지정·수정안 제약 검증 보존.
- 빈 DB와 기존 데이터에 대한 변환 결과, 행 수·PK·ended_at 보존, 구 컬럼 제거, 새 INSERT·조회 확인.
- 자정·월말·연말·마이크로초 경계, 오전/오후 시간 보존과 C 요청 기간 검증 확인.
- #137을 포함한 전체 컴파일과 회귀 테스트를 확인합니다.

로컬 컴파일·비DB 테스트는 MySQL 제약, Flyway 적용, 데이터 변환 검증의 증거가 아닙니다.

## 적용 및 복구

공유 DB 적용은 팀의 배포 절차에 따라 관련 쓰기를 중지하고 백업·복구 방법을 확인한 뒤 진행합니다. 구·신 애플리케이션을 혼재하지 않습니다.
V3는 구 컬럼을 제거하므로 구 이미지 재배포만으로 복구되지 않습니다. MySQL DDL의 일반 ROLLBACK을 가정하지 않으며, 실패 시 쓰기를 중지하고 백업/PITR 및 새 변경분 보존을 포함한 복구 계획으로 처리합니다.

## 과거 검토 자료

`migration-review.sql`은 Flyway V3 확정 전의 수동 매핑·구 컬럼 보존 검토 초안입니다. 실행본이나 현재 적용 절차가 아니며, 실제 V3와 충돌하면 V3를 기준으로 합니다.
