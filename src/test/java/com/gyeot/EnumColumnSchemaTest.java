package com.gyeot;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * enum 컬럼이 MySQL ENUM이 아니라 CHECK 없는 VARCHAR(length)로 생성되는지 실제 MySQL(Testcontainers)에서 확인합니다. (#36)
 *
 * 배경 (#36 실험 결과, Hibernate 7.4 + MySQL 8.4)
 * - @Enumerated(STRING)만 쓰면 MySQL ENUM('A','B')로 생성되어 허용 값 목록이 DB에 남습니다.
 * - @JdbcTypeCode(SqlTypes.VARCHAR)는 VARCHAR가 되지만 CHECK (col in ('A','B'))가 자동으로 붙고,
 *   enum 값을 추가해도 ddl-auto: update가 CHECK를 갱신하지 않아 저장에 실패합니다.
 * - columnDefinition = "VARCHAR(n)"만 CHECK 없이 생성되어, enum 값을 추가해도 DB 수정 없이 저장됩니다.
 *
 * 그래서 이 테스트는 엔티티의 모든 @Enumerated 필드를 찾아 아래 3가지를 검사합니다.
 * 새 enum 필드에 columnDefinition을 빠뜨리거나, length와 VARCHAR(n) 길이가 어긋나면 CI에서 실패합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class EnumColumnSchemaTest {

    @Autowired private EntityManagerFactory entityManagerFactory; // 등록된 엔티티 목록을 꺼내는 데 사용
    @Autowired private JdbcTemplate jdbcTemplate;                 // INFORMATION_SCHEMA 조회용

    /** 검사 대상 컬럼 1개 (어느 엔티티의 어느 필드인지 실패 메시지에 표시) */
    private record EnumColumn(String entity, String field, String table, String column, int length) {
        String label() {
            return entity + "." + field + " (" + table + "." + column + ")";
        }
    }

    @Test
    void enum_컬럼은_CHECK_없는_VARCHAR_length로_생성된다() {
        // 1. 엔티티의 @Enumerated 필드를 모두 찾음 (개수를 고정하지 않아 새 엔티티·필드도 자동으로 포함)
        List<EnumColumn> enumColumns = findEnumColumns();
        assertThat(enumColumns).as("@Enumerated 필드를 하나도 찾지 못하면 검사가 무의미하므로 실패").isNotEmpty();

        SoftAssertions softly = new SoftAssertions(); // 하나가 틀려도 멈추지 않고 전체 결과를 한 번에 보여줌
        for (EnumColumn enumColumn : enumColumns) {
            // 2. 실제 생성된 컬럼의 타입·길이를 INFORMATION_SCHEMA에서 조회
            Map<String, Object> actual = jdbcTemplate.queryForMap("""
                    select data_type, character_maximum_length
                    from information_schema.columns
                    where table_schema = database() and table_name = ? and column_name = ?
                    """, enumColumn.table(), enumColumn.column());

            // 3-1. ENUM이 아니라 varchar여야 함
            softly.assertThat(actual.get("DATA_TYPE"))
                    .as(enumColumn.label() + " 타입").isEqualTo("varchar");
            // 3-2. 길이가 @Column(length)와 같아야 함 (columnDefinition의 VARCHAR(n)과 length를 두 번 적어서 어긋날 수 있음)
            softly.assertThat(((Number) actual.get("CHARACTER_MAXIMUM_LENGTH")).intValue())
                    .as(enumColumn.label() + " 길이").isEqualTo(enumColumn.length());
            // 3-3. 이 컬럼을 검사하는 CHECK 제약이 없어야 함 (있으면 enum 값을 추가할 때 저장 실패)
            softly.assertThat(findCheckClauses(enumColumn))
                    .as(enumColumn.label() + " CHECK 제약").isEmpty();
        }
        softly.assertAll();
    }

    /** 등록된 모든 엔티티에서 @Enumerated 필드를 찾아 테이블·컬럼·길이를 모음 (상속받은 필드 포함) */
    private List<EnumColumn> findEnumColumns() {
        List<EnumColumn> result = new ArrayList<>();
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> entityClass = entity.getJavaType();
            Table table = entityClass.getAnnotation(Table.class);
            for (Class<?> type = entityClass; type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!field.isAnnotationPresent(Enumerated.class)) {
                        continue;
                    }
                    // 팀 규칙상 엔티티는 @Table(name), 컬럼은 @Column(name)을 명시하므로 그 이름으로 조회
                    assertThat(table).as(entityClass.getSimpleName() + "에 @Table이 없음").isNotNull();
                    Column column = field.getAnnotation(Column.class);
                    assertThat(column).as(entityClass.getSimpleName() + "." + field.getName() + "에 @Column이 없음").isNotNull();
                    result.add(new EnumColumn(entityClass.getSimpleName(), field.getName(),
                            table.name(), column.name(), column.length()));
                }
            }
        }
        return result;
    }

    /** 해당 테이블의 CHECK 제약 중 이 컬럼 이름이 들어간 조건식을 조회 */
    private List<String> findCheckClauses(EnumColumn enumColumn) {
        return jdbcTemplate.queryForList("""
                select cc.check_clause
                from information_schema.table_constraints tc
                join information_schema.check_constraints cc
                  on cc.constraint_schema = tc.constraint_schema and cc.constraint_name = tc.constraint_name
                where tc.table_schema = database() and tc.table_name = ? and tc.constraint_type = 'CHECK'
                  and cc.check_clause like ?
                """, String.class, enumColumn.table(), "%`" + enumColumn.column() + "`%");
    }
}
