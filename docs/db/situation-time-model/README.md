# 상황 날짜·시간 모델 DB 전환 준비 (실행 금지 초안)

이 자료는 자동 실행되지 않습니다. Flyway 경로가 아니며 실제 DB에서 실행하지 않았습니다.
시간 경계와 DATE 변환 규칙은 이슈 [#117](https://github.com/llszos/LIKELION_14th_2SWU_BE/issues/117)에 확정되어 있습니다. 실제 DB 상태·Flyway 버전·적용 절차는 별도 확인이 필요하며, 아래 절차와 SQL은 담당자 검토·승인 전 실행하지 않습니다.

## 코드 계약

- `start_at`, `end_at`: Asia/Seoul 현지 시각, MySQL `DATETIME(6)` (Hibernate 메타데이터 기준).
- `start_at < end_at`. `end_at`은 예정 종료, `ended_at`은 실제 처리 시각이며 이관에서 변경하지 않습니다.
- 유효 구간은 `[startAt, endAt)`이며 자동 종료 대상 판정은 `now >= endAt`입니다. 스케줄러 지연과 접근 정책은 별도이며, Reader는 시간만으로 저장된 ACTIVE/ENDED나 참여 상태를 바꾸지 않습니다.
- C 요청 일정은 `situation.startAt <= request.startsAt < request.endsAt <= situation.endAt`입니다.
- LocalDateTime은 시간대/오프셋을 저장하지 않습니다. 기존 서울 Clock/JDBC 설정을 유지합니다.
- 구 날짜 전용 코드와 새 코드의 무중단 동시 쓰기는 이 PR에서 지원하지 않습니다.

## 사전 확인

1. 실제 MySQL 버전, `SHOW CREATE TABLE situations`, 구 컬럼 타입/NULL/기본값/인덱스 확인.
2. 원본 테이블/DDL 백업과 격리된 복구 리허설. 행 수와 PK별 구 날짜·ended_at 대조 자료 확보.
3. 기존 데이터 존재·범위, 잘못된 날짜·최대 날짜(다음 날 계산 overflow 포함) 확인.
4. #117 확정 규칙은 시작일 00:00 / 종료일 다음 날 00:00입니다. 원래 시각 복원이 아니라 기존 종료일 당일 전체 포함 의미를 보존하는 변환입니다. 적용 전 실제 날짜 범위와 다음 날 계산 overflow를 검사합니다.
5. 전환 시간 동안 상황 쓰기·종료 Scheduler 등 관련 쓰기를 중지할 방법과 복구 책임자를 결정.

## 적용 순서 (별도 승인 필요)

1. 쓰기 중지 후 최종 백업 및 사전 검사. `migration-review.sql`의 주석 템플릿을 실제 DDL에 맞춰 검토합니다.
2. 구 컬럼을 보존하고 nullable `start_at/end_at` 추가. MySQL DDL은 일반 트랜잭션 ROLLBACK으로 복원된다고 가정하지 않습니다.
3. 확정 규칙으로 PK별 변환 결과를 준비하고 이상치를 검토합니다. 현재 SQL 템플릿은 자동 이관 파일이 아니며, 실제 실행본은 DB 상태·Flyway 버전 확인 뒤 작성합니다.
4. 매핑 누락·중복·NULL·역전이 0인지 확인한 뒤 트랜잭션으로 새 컬럼만 채웁니다. 원본 날짜와 ended_at은 그대로 둡니다.
5. 아래 검증을 모두 통과한 후 새 컬럼 NOT NULL 적용. 새 코드가 구 컬럼을 쓰지 않으므로 구 NOT NULL 컬럼은 nullable 전환 등 INSERT 호환 조치가 필요합니다. 임의 기본 날짜로 우회하지 않습니다.
6. 새 코드로 prod `ddl-auto: validate`, 조회·INSERT를 검증하고 쓰기를 재개합니다. 전환 중 old/new 코드 혼재 금지.
7. 구 컬럼 제거는 이 전환 범위에서 제외합니다. 원본 보존 기간과 제거는 별도 승인합니다.

## 검증

- 행 수/PK 불변, 원본 start_date/end_date/ended_at 불변.
- 새 컬럼 NULL/역전/동일 시각 0건, 승인 매핑과 PK별 시각 일치.
- 오전/오후/자정/월말/연말 및 마이크로초 저장·재조회 검증.
- 구 컬럼을 보내지 않는 새 INSERT가 제약 때문에 실패하지 않는지 확인.
- 새/구 애플리케이션 버전에 맞는 스키마 검사. 자동 종료 #94와 C #113은 적응 후 배포.

## 복구

- 새 버전 쓰기 재개 전: 쓰기를 계속 중지한 상태로 원본·승인 매핑·전환 결과를 보존합니다. 구 코드 재배포 시 구 제약과 새 컬럼 제약을 검토합니다. 추가된 NOT NULL 컬럼이 구 INSERT를 막을 수 있습니다.
- 새 버전 쓰기 재개 후: 단순 구 코드 재배포나 DATE 캐스팅으로 복구하지 않습니다. 새 데이터의 시간 정밀도와 구 날짜 대응을 별도로 결정해야 합니다. 쓰기를 중지하고 백업/PITR와 새 변경분 보존을 포함한 복구 계획을 승인받습니다.
- 본 자료는 DROP/DELETE 또는 원본 날짜를 덮어쓰는 복구 SQL을 제공하지 않습니다.

## 환경

- local은 `ddl-auto: update`지만 컬럼 rename/데이터 이관을 보장하지 않습니다. 데이터가 있으면 같은 승인 절차가 필요합니다.
- prod는 `ddl-auto: validate`, Flyway는 현재 비활성입니다. 수동 전환의 버전·적용자·백업·승인 매핑·검증 결과를 기록합니다.
- Testcontainers는 격리된 일회용 DB에서 `create-drop`합니다. 이관 검증을 대체하지 않습니다.

## 2026-11-16 MUST 점검 및 차단 사항

- #116 head는 3226b4e이며, 최신 develop은 5fba2a2입니다. 다른 브랜치를 병합하지 않았습니다.
- Flyway V1은 PR [#131](https://github.com/llszos/LIKELION_14th_2SWU_BE/pull/131), head 64dcbf3 기준 Open·미병합입니다. develop에는 아직 migration 파일이 없습니다.
- 준비된 V1은 `start_date/end_date DATE NOT NULL`과 `chk_situations_period(start_date <= end_date)`를 사용합니다. 후속 시간 전환에서는 새 컬럼뿐 아니라 기간 CHECK와 구 NOT NULL의 INSERT 호환성을 함께 처리해야 합니다.
- #117은 V2 연결 방향을 정했지만, #131은 #122/#116 변경의 병합 순서에 따라 V2부터 후속 버전을 배정하도록 안내합니다. 실제 버전 번호·담당 PR은 #47 담당자와 확인 전 생성하지 않습니다.
- 실제 서버의 SHOW CREATE TABLE, flyway_schema_history, 데이터 유무는 직접 확인하지 않았습니다. #131 본문의 서버 users 0건 보고는 이번 작업의 직접 DB 검증 결과가 아닙니다. DB 초기화·DROP 승인을 의미하지 않습니다.
- B 구형 접근자는 #94 자동 종료(getEndDate 및 날짜 기반 후보/자정 스케줄), #95 지난 상황 목록(getStartDate/getEndDate), #103 참여자 브랜치의 Reader/fixture에 남아 있습니다. #116 작업 트리에는 해당 서비스/DTO가 없으므로 복사·병합하지 않습니다. 각 PR에서 새 계약에 적응해야 합니다.
- C #113 RequestScheduleValidator의 startDate()/endDate() 및 C 생성·상세의 날짜 DTO는 C 담당자가 적응해야 합니다. 시간 전환과 소비 코드가 함께 컴파일되는 병합 단위를 확인합니다.
- #115의 참여자 ID 조회와 Reader 파일 중첩은 두 기능을 모두 보존하며 해결해야 합니다. 이번에는 타 브랜치를 수정하지 않았습니다.
- #118은 #116에 의존하는 후속 PR입니다. #118의 검증된 0788087 커밋은 보존했고 가져오지 않았습니다. #116에서 HTTP DTO·상황 생성 흐름을 중복 구현하지 않습니다.
- 병합 전: 실제 DB 후속 마이그레이션·기존 데이터/제약·새 INSERT·validate 검증, B/C 소비 코드 통합 컴파일 및 최종 develop CI가 필요합니다. 단독 CI 성공은 이관·통합 검증을 대신하지 않습니다.
- 확인 질문: #47 담당자는 후속 버전/작성 PR 및 구 컬럼 보존 방식을, C 담당자는 적응 커밋과 병합 단위를, B 담당자는 자동 종료 주기와 종료 지연 중 접근 정책을 확정해야 합니다.
- 로컬 검증: Java 21, `gradlew.bat --offline --no-daemon test --rerun-tasks --tests '*SituationEntityTest' --tests '*SituationParticipantReaderImplTest'`로 29개 통과(실패·오류·스킵 0), main/test 컴파일 및 git diff --check 성공. MySQL/Testcontainers·실제 DDL/데이터 이관·전체 build·수정 후 원격 CI는 실행하지 않았습니다.
