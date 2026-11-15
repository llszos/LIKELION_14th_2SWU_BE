# 상황 종류·기타 문구 컬럼 적용 준비 (#60)

- 승인된 신규 코드: HOSPITAL_VISIT, RECOVERY, ABSENCE, OTHER. 기존 situation_type 문자열 컬럼과 과거 저장값은 변경하지 않습니다.
- 신규 컬럼 후보: situations.custom_situation_type VARCHAR(15) NULL. 기존 행은 NULL을 유지합니다.
- OTHER 문구는 앞뒤 공백 제거 후 1~15자, 그 외 유형의 non-null 문구는 INVALID_INPUT으로 거부합니다. 이는 앞서 승인된 임시 검증 기준이며 Notion 후속 반영이 필요합니다.

## 적용 순서 (실행하지 않음)

1. #47 담당자가 실제 DB의 SHOW CREATE TABLE, Flyway 이력, V1 확정·적용 여부를 확인합니다. 이 브랜치와 확인한 origin/develop에는 V1/V2가 없고 Flyway도 비활성입니다. 실제 DB 적용 여부는 DB에 접근하지 않아 확인하지 않았습니다.
2. V1 초기 스키마 → #116 시간 모델 V2 순서와 컬럼 추가 버전을 담당자와 확정합니다. V1이 아직 미적용·미확정이면 초기 스키마 포함 여부를 협의하고, 이미 적용됐다면 수정하지 않고 다음 미사용 버전을 배정합니다. 임의의 V1/V2/V3 파일을 만들지 않습니다.
3. 아래 검토 SQL을 승인된 마이그레이션 파일로 옮기고 격리 MySQL에서 검증합니다. 이 문서는 운영 실행 승인이 아닙니다.

```sql
-- 검토용이며 실행하지 않았습니다. 실제 스키마·이력 확인 후 승인된 버전에 포함합니다.
-- ALTER TABLE situations ADD COLUMN custom_situation_type VARCHAR(15) NULL;
```

4. 행 수·PK·기존 situation_type·기간 불변, 기존 행의 새 컬럼 NULL, OTHER 정규화 문구 저장·재조회, 일반 유형 NULL 저장을 확인합니다. #116의 start_at/end_at 이관과 NOT NULL 호환 조치도 함께 검증합니다.
5. 스키마가 준비된 뒤 새 Entity/API를 배포합니다. prod ddl-auto:validate는 컬럼 추가 전 실패할 수 있으며, local ddl-auto:update를 수동 이관 검증의 대안으로 사용하지 않습니다.

## 복구 및 미검증

- 이전 코드로 복귀할 때 새 nullable 컬럼과 저장된 문구는 보존합니다. DROP이나 기존 값 덮어쓰기로 복구하지 않습니다.
- 신규 문구를 이전 코드에서는 표시하지 못하므로 쓰기 재개·롤백 절차는 배포 담당자가 승인해야 합니다.
- 실제 DDL, Flyway 적용, DB 저장·롤백은 실행하지 않았습니다. Testcontainers create-drop 테스트도 Flyway 이관 검증을 대체하지 않습니다.
- 목록과 지난 상황 명세에는 customSituationType 추가 요구가 명시되지 않았습니다. 이번에는 요청한 진행 중 상세에만 추가하고 #63/공통 카드 표시는 별도 계약으로 확인합니다.
