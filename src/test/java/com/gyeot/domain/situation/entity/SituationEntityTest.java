package com.gyeot.domain.situation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.MetadataImplementor;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SituationEntityTest {
    private static final LocalDate START = LocalDate.of(2026, 11, 2);
    private static final LocalDateTime JOINED = START.atTime(9, 0);
    private static final LocalDateTime ENDED = JOINED.plusHours(1);

    private Situation situation() {
        return Situation.create(1L, "임시 유형", "상황", START, START.plusDays(1));
    }

    private SituationParticipant participant(SituationParticipantRole role) {
        return SituationParticipant.create(situation(), role == SituationParticipantRole.OWNER ? 1L : 2L,
                role, JOINED);
    }

    @Test
    void createsActiveSituationWithOriginalValues() {
        Situation s = situation();
        assertEquals(1L, s.getCreatorId());
        assertEquals("임시 유형", s.getSituationType());
        assertEquals("상황", s.getTitle());
        assertEquals(START, s.getStartDate());
        assertEquals(START.plusDays(1), s.getEndDate());
        assertEquals(SituationStatus.ACTIVE, s.getStatus());
        assertNull(s.getEndedAt());
    }

    @Test
    void rejectsMissingSituationValues() {
        assertThrows(IllegalArgumentException.class, () -> Situation.create(null, "유형", "제목", START, START));
        assertThrows(IllegalArgumentException.class, () -> Situation.create(1L, "유형", "제목", null, START));
        assertThrows(IllegalArgumentException.class, () -> Situation.create(1L, "유형", "제목", START, null));
        for (String invalid : new String[]{null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> Situation.create(1L, invalid, "제목", START, START));
            assertThrows(IllegalArgumentException.class, () -> Situation.create(1L, "유형", invalid, START, START));
        }
    }

    @Test
    void validatesDateRangeAndTextBoundaries() {
        assertThrows(IllegalArgumentException.class,
                () -> Situation.create(1L, "유형", "제목", START.plusDays(1), START));
        assertDoesNotThrow(() -> Situation.create(1L, "가".repeat(30), "가".repeat(150), START, START));
        assertThrows(IllegalArgumentException.class,
                () -> Situation.create(1L, "가".repeat(31), "제목", START, START));
        assertThrows(IllegalArgumentException.class,
                () -> Situation.create(1L, "유형", "가".repeat(151), START, START));
    }

    @Test
    void endingSituationPreservesFirstTimeAndRequiresTimeOnRetry() {
        Situation s = situation();
        s.end(ENDED);
        s.end(ENDED.plusHours(1));
        assertEquals(SituationStatus.ENDED, s.getStatus());
        assertEquals(ENDED, s.getEndedAt());
        assertThrows(IllegalArgumentException.class, () -> s.end(null));
        assertEquals(ENDED, s.getEndedAt());
    }

    @Test
    void situationEndDoesNotRequireOwnerToLeave() {
        Situation s = situation();
        SituationParticipant owner = SituationParticipant.create(s, 1L, SituationParticipantRole.OWNER, JOINED);
        s.end(ENDED);
        assertEquals(SituationStatus.ENDED, s.getStatus());
        assertEquals(SituationParticipantStatus.ACTIVE, owner.getStatus());
        assertNull(owner.getLeftAt());
    }

    @Test
    void createsActiveParticipantAndMatchingOwner() {
        Situation s = situation();
        for (SituationParticipantRole role : SituationParticipantRole.values()) {
            Long userId = role == SituationParticipantRole.OWNER ? 1L : 2L;
            SituationParticipant p = SituationParticipant.create(s, userId, role, JOINED);
            assertSame(s, p.getSituation());
            assertEquals(userId, p.getUserId());
            assertEquals(role, p.getRole());
            assertEquals(JOINED, p.getJoinedAt());
            assertEquals(SituationParticipantStatus.ACTIVE, p.getStatus());
            assertNull(p.getLeftAt());
        }
    }

    @Test
    void rejectsMissingParticipantValuesAndMismatchedOwner() {
        assertThrows(IllegalArgumentException.class,
                () -> SituationParticipant.create(null, 2L, SituationParticipantRole.PARTICIPANT, JOINED));
        assertThrows(IllegalArgumentException.class,
                () -> SituationParticipant.create(situation(), null, SituationParticipantRole.PARTICIPANT, JOINED));
        assertThrows(IllegalArgumentException.class,
                () -> SituationParticipant.create(situation(), 2L, null, JOINED));
        assertThrows(IllegalArgumentException.class,
                () -> SituationParticipant.create(situation(), 2L, SituationParticipantRole.PARTICIPANT, null));
        assertThrows(IllegalArgumentException.class,
                () -> SituationParticipant.create(situation(), 2L, SituationParticipantRole.OWNER, JOINED));
    }

    @Test
    void ownerCannotLeaveOrBeRemovedAndStateIsUnchanged() {
        SituationParticipant owner = participant(SituationParticipantRole.OWNER);
        assertThrows(IllegalStateException.class, () -> owner.leave(ENDED));
        assertThrows(IllegalStateException.class, () -> owner.remove(ENDED));
        assertThrows(IllegalStateException.class, () -> owner.leave(ENDED.plusHours(1)));
        assertEquals(SituationParticipantStatus.ACTIVE, owner.getStatus());
        assertNull(owner.getLeftAt());
        assertEquals(JOINED, owner.getJoinedAt());
    }

    @Test
    void leavingPreservesFirstStateAndTimeOnBothRetries() {
        SituationParticipant p = participant(SituationParticipantRole.PARTICIPANT);
        p.leave(ENDED);
        p.leave(ENDED.plusHours(1));
        p.remove(ENDED.plusHours(2));
        assertEquals(SituationParticipantStatus.LEFT, p.getStatus());
        assertEquals(ENDED, p.getLeftAt());
    }

    @Test
    void removalPreservesFirstStateAndTimeOnBothRetries() {
        SituationParticipant p = participant(SituationParticipantRole.PARTICIPANT);
        p.remove(ENDED);
        p.remove(ENDED.plusHours(1));
        p.leave(ENDED.plusHours(2));
        assertEquals(SituationParticipantStatus.REMOVED, p.getStatus());
        assertEquals(ENDED, p.getLeftAt());
    }

    @Test
    void nullTimeIsRejectedBeforeAndAfterParticipationEnds() {
        for (SituationParticipantRole role : SituationParticipantRole.values()) {
            SituationParticipant p = participant(role);
            assertThrows(IllegalArgumentException.class, () -> p.leave(null));
            assertThrows(IllegalArgumentException.class, () -> p.remove(null));
            assertEquals(SituationParticipantStatus.ACTIVE, p.getStatus());
        }
        SituationParticipant left = participant(SituationParticipantRole.PARTICIPANT);
        left.leave(ENDED);
        assertThrows(IllegalArgumentException.class, () -> left.leave(null));
        assertThrows(IllegalArgumentException.class, () -> left.remove(null));
        SituationParticipant removed = participant(SituationParticipantRole.PARTICIPANT);
        removed.remove(ENDED);
        assertThrows(IllegalArgumentException.class, () -> removed.leave(null));
        assertThrows(IllegalArgumentException.class, () -> removed.remove(null));
        assertEquals(ENDED, left.getLeftAt());
        assertEquals(ENDED, removed.getLeftAt());
    }

    @Test
    void indexesMatchErd64AndDoNotIntroduceUniqueConstraint() {
        assertEquals(Set.of("creator_id,status"), indexColumns(Situation.class));
        assertEquals(Set.of("situation_id,status", "user_id,status"), indexColumns(SituationParticipant.class));
        assertEquals(0, SituationParticipant.class.getAnnotation(Table.class).uniqueConstraints().length);
        for (Class<?> type : new Class<?>[]{Situation.class, SituationParticipant.class}) {
            assertTrue(Arrays.stream(type.getAnnotation(Table.class).indexes()).noneMatch(i -> i.unique()));
        }
    }

    private Set<String> indexColumns(Class<?> type) {
        return Arrays.stream(type.getAnnotation(Table.class).indexes())
                .map(i -> i.columnList().replace(" ", "")).collect(Collectors.toSet());
    }

    @Test
    void immutableParticipationIdentityIsExcludedFromUpdatesButStateIsMutable() throws Exception {
        JoinColumn join = SituationParticipant.class.getDeclaredField("situation").getAnnotation(JoinColumn.class);
        assertEquals("situation_id", join.name());
        assertFalse(join.updatable());
        assertTrue(join.insertable());
        for (String name : new String[]{"userId", "role", "joinedAt"}) {
            Column column = SituationParticipant.class.getDeclaredField(name).getAnnotation(Column.class);
            assertFalse(column.updatable(), name);
            assertTrue(column.insertable(), name);
        }
        for (String name : new String[]{"status", "leftAt"}) {
            assertTrue(SituationParticipant.class.getDeclaredField(name).getAnnotation(Column.class).updatable());
        }
    }

    @Test
    void enumNamesRemainStringsWithoutExplicitSqlOverrides() throws Exception {
        for (Class<?> type : new Class<?>[]{Situation.class, SituationParticipant.class}) {
            for (var field : type.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column != null) {
                    assertEquals("", column.columnDefinition(), field.getName());
                }
                if (field.getType().isEnum()) {
                    assertEquals(EnumType.STRING, field.getAnnotation(Enumerated.class).value());
                }
            }
        }
        assertEquals("", SituationParticipant.class.getDeclaredField("situation")
                .getAnnotation(JoinColumn.class).columnDefinition());
    }

    @Test
    void hibernateMysqlMetadataResolvesSignedIdsAndMicrosecondTimestampsWithoutDatabase() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        try {
            MetadataImplementor metadata = (MetadataImplementor) new MetadataSources(registry)
                    .addAnnotatedClass(Situation.class).addAnnotatedClass(SituationParticipant.class)
                    .buildMetadata();
            metadata.orderColumns(false);
            metadata.validate();
            var situations = metadata.getEntityBinding(Situation.class.getName()).getTable();
            var participants = metadata.getEntityBinding(SituationParticipant.class.getName()).getTable();
            assertEquals("bigint", sqlType(situations, "id", metadata));
            assertEquals("bigint", sqlType(situations, "creator_id", metadata));
            for (String name : new String[]{"id", "situation_id", "user_id"}) {
                assertEquals("bigint", sqlType(participants, name, metadata), name);
            }
            assertEquals("datetime(6)", sqlType(situations, "ended_at", metadata));
            assertEquals("datetime(6)", sqlType(participants, "joined_at", metadata));
            assertEquals("datetime(6)", sqlType(participants, "left_at", metadata));
            assertEquals(1, situations.getIndexes().size());
            assertEquals(2, participants.getIndexes().size());
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private String sqlType(org.hibernate.mapping.Table table, String name, MetadataImplementor metadata) {
        return table.getColumns().stream().filter(c -> c.getName().equals(name)).findFirst()
                .orElseThrow().getSqlType(metadata).toLowerCase();
    }
}
