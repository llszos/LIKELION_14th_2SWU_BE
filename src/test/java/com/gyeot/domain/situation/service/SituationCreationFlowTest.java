package com.gyeot.domain.situation.service;

import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.situation.controller.*;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.repository.*;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.auth.AuthenticatedUser;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 실제 Service·Resolver·참여자 Validator·Writer·Spring 트랜잭션 프록시를 사용합니다.
 * A 조회와 B Repository만 mock이며, 저장 인자·HTTP 재조회 매핑을 검증합니다. 실제 DB 영속화/롤백은 아닙니다.
 * JWT 필터는 별도 SecurityTest가 검증합니다.
 */
class SituationCreationFlowTest {
    private AnnotationConfigApplicationContext context;
    private MockMvc mvc;
    private SituationRepository situations;
    private SituationParticipantRepository participants;
    private UserRepository users;
    private RelationshipQueryRepository relationships;
    private SituationCreateWriterTransactionTest.RecordingTransactionManager tx;
    private final AtomicReference<Situation> saved = new AtomicReference<>();
    private final List<SituationParticipant> rows = new ArrayList<>();

    @BeforeEach void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        situations = context.getBean(SituationRepository.class);
        participants = context.getBean(SituationParticipantRepository.class);
        users = context.getBean(UserRepository.class);
        relationships = context.getBean(RelationshipQueryRepository.class);
        tx = context.getBean(SituationCreateWriterTransactionTest.RecordingTransactionManager.class);
        when(situations.save(any(Situation.class))).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Situation situation = i.getArgument(0);
            ReflectionTestUtils.setField(situation, "id", 7L);
            saved.set(situation);
            return situation;
        });
        when(situations.getReferenceById(7L)).thenAnswer(i -> saved.get());
        when(situations.findById(7L)).thenAnswer(i -> Optional.ofNullable(saved.get()));
        when(participants.save(any(SituationParticipant.class))).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            rows.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(participants.existsBySituation_IdAndUserIdAndStatus(7L, 10L, SituationParticipantStatus.ACTIVE))
                .thenAnswer(i -> rows.stream().anyMatch(p -> p.getUserId().equals(10L)
                        && p.getStatus() == SituationParticipantStatus.ACTIVE));
        mvc = MockMvcBuilders.standaloneSetup(
                        new SituationCreateController(context.getBean(SituationCreateService.class)),
                        new SituationController(context.getBean(SituationQueryService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(10L), null, List.of()));
    }

    @AfterEach void close() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @ParameterizedTest @ValueSource(strings = {"HOSPITAL_VISIT", "RECOVERY", "ABSENCE", "OTHER"})
    void realFlowCreatesOwnerAndReturnsCustomTextThroughDetail(String type) throws Exception {
        String custom = type.equals("OTHER") ? "  " + "가".repeat(15) + "  " : null;
        mvc.perform(post("/api/situations").contentType("application/json").content(body(type, custom, "[]")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.situationId").value(7))
                .andExpect(jsonPath("$.data.creatorId").value(10))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(saved.get().getTitle()).isEqualTo("상황 이름");
        assertThat(saved.get().getSituationType()).isEqualTo(type);
        assertThat(saved.get().getCustomSituationType()).isEqualTo(custom == null ? null : custom.strip());
        assertThat(saved.get().getStartAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 9, 0));
        assertThat(rows).singleElement().satisfies(owner -> {
            assertThat(owner.getUserId()).isEqualTo(10L);
            assertThat(owner.getRole()).isEqualTo(SituationParticipantRole.OWNER);
            assertThat(owner.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
            assertThat(owner.getJoinedAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 9, 0));
        });
        assertThat(tx.begins).isEqualTo(1);
        assertThat(tx.commits).isEqualTo(1);
        assertThat(tx.rollbacks).isZero();
        var result = mvc.perform(get("/api/situations/7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.situationType").value(type));
        if (custom == null) result.andExpect(jsonPath("$.data.customSituationType").isEmpty());
        else result.andExpect(jsonPath("$.data.customSituationType").value(custom.strip()));
    }

    @Test void activeRelatedParticipantIsValidatedThenSavedWithOwnerInOneTransaction() throws Exception {
        activeTarget();
        when(relationships.existsBySourceUserIdAndTargetUserIdAndStatus(10L, 20L, RelationshipStatus.ACTIVE)).thenReturn(true);
        mvc.perform(post("/api/situations").contentType("application/json").content(body("RECOVERY", null, "[20]")))
                .andExpect(status().isCreated());
        assertThat(rows).extracting(SituationParticipant::getUserId).containsExactly(10L, 20L);
        assertThat(rows.getLast().getRole()).isEqualTo(SituationParticipantRole.PARTICIPANT);
        verify(relationships).existsBySourceUserIdAndTargetUserIdAndStatus(10L, 20L, RelationshipStatus.ACTIVE);
        assertThat(tx.begins).isEqualTo(1);
        assertThat(tx.commits).isEqualTo(1);
    }

    @Test void invalidOtherAndInvalidCodesNeverReachStorage() throws Exception {
        for (String custom : Arrays.asList(null, "", " \t ", "가".repeat(16))) bad(body("OTHER", custom, "[]"), 400);
        for (String code : new String[]{"HOSPITAL", "ETC", "UNKNOWN", "recovery"}) bad(body(code, null, "[]"), 400);
        for (String custom : new String[]{"", " ", "기타 문구"}) bad(body("RECOVERY", custom, "[]"), 400);
        assertThat(saved.get()).isNull();
        assertThat(rows).isEmpty();
        verifyNoInteractions(users, relationships);
        verify(situations, never()).save(any());
    }

    @Test void duplicateSelfAndNullIdsAre400WithoutStorage() throws Exception {
        for (String ids : new String[]{"[20,20]", "[10]", "[null]"}) bad(body("ABSENCE", null, ids), 400);
        assertThat(saved.get()).isNull();
        verifyNoInteractions(users, relationships);
    }

    @Test void missingOrWithdrawnUserAndMissingRelationshipStopBeforeStorage() throws Exception {
        bad(body("RECOVERY", null, "[20]"), 404);
        when(users.existsById(20L)).thenReturn(true);
        when(users.existsByIdAndAccountStatus(20L, AccountStatus.WITHDRAWN)).thenReturn(true);
        bad(body("RECOVERY", null, "[20]"), 409);
        when(users.existsByIdAndAccountStatus(20L, AccountStatus.WITHDRAWN)).thenReturn(false);
        activeTarget();
        bad(body("RECOVERY", null, "[20]"), 403);
        assertThat(saved.get()).isNull();
        verifyNoInteractions(participants);
    }

    @Test void ownerSaveFailureSignalsOuterRollbackNotSuccessfulCreation() throws Exception {
        when(participants.save(any(SituationParticipant.class))).thenThrow(new IllegalStateException("owner save failed"));
        bad(body("OTHER", "기타 문구", "[]"), 500);
        assertThat(tx.commits).isZero();
        assertThat(tx.rollbacks).isEqualTo(1);
        // mock의 saved 값은 남습니다. 실제 DB가 원복됐다고 주장하지 않습니다.
        assertThat(saved.get().getCustomSituationType()).isEqualTo("기타 문구");
    }

    @Test void selectedParticipantFailureSignalsRollbackAcrossRealOwnerAndCustomStorageFlow() throws Exception {
        activeTarget();
        when(relationships.existsBySourceUserIdAndTargetUserIdAndStatus(10L, 20L, RelationshipStatus.ACTIVE)).thenReturn(true);
        when(participants.save(any(SituationParticipant.class))).thenAnswer(i -> {
            SituationParticipant participant = i.getArgument(0);
            if (participant.getRole() == SituationParticipantRole.PARTICIPANT)
                throw new IllegalStateException("selected participant save failed");
            rows.add(participant);
            return participant;
        });
        bad(body("OTHER", " 기타 문구 ", "[20]"), 500);
        assertThat(tx.begins).isEqualTo(1);
        assertThat(tx.commits).isZero();
        assertThat(tx.rollbacks).isEqualTo(1);
        assertThat(rows).singleElement().satisfies(p -> assertThat(p.getRole()).isEqualTo(SituationParticipantRole.OWNER));
        // 위 메모리 목록은 DB 롤백 결과가 아닙니다.
    }

    private void activeTarget() {
        when(users.existsById(20L)).thenReturn(true);
        when(users.existsByIdAndAccountStatus(20L, AccountStatus.ACTIVE)).thenReturn(true);
    }
    private void bad(String body, int status) throws Exception {
        mvc.perform(post("/api/situations").contentType("application/json").content(body)).andExpect(status().is(status));
    }
    private String body(String type, String custom, String ids) {
        String customJson = custom == null ? "null" : "\"" + custom.replace("\t", "\\t") + "\"";
        return """
                {"situationType":"%s","customSituationType":%s,"name":"상황 이름","participantIds":%s,
                 "startAt":"2026-11-20T09:00:00","endAt":"2026-11-20T10:00:00","creatorId":999}
                """.formatted(type, customJson, ids);
    }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean SituationRepository situations() { return mock(SituationRepository.class); }
        @Bean SituationParticipantRepository participants() { return mock(SituationParticipantRepository.class); }
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean RelationshipQueryRepository relationships() { return mock(RelationshipQueryRepository.class); }
        @Bean SituationCreationTypeResolver types() { return new SituationCreationTypeResolver(); }
        @Bean SituationCreateParticipantValidator participantValidator(UserRepository u, RelationshipQueryRepository r) { return new SituationCreateParticipantValidator(u, r); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC); }
        @Bean(destroyMethod = "close") ValidatorFactory factory() { return Validation.buildDefaultValidatorFactory(); }
        @Bean Validator validator(ValidatorFactory f) { return f.getValidator(); }
        @Bean SituationCreateWriterTransactionTest.RecordingTransactionManager transactionManager() {
            return new SituationCreateWriterTransactionTest.RecordingTransactionManager() {
                @Override protected boolean isExistingTransaction(Object transaction) { return TransactionSynchronizationManager.isActualTransactionActive(); }
                @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { /* outer caller handles propagated failure */ }
            };
        }
        @Bean SituationCreateWriter writer(SituationRepository s, SituationParticipantRepository p, Clock c) { return new SituationCreateWriter(s, p, c); }
        @Bean SituationCreateService service(Validator v, SituationCreationTypeResolver t, SituationCreateParticipantValidator pv,
                SituationCreateWriter w, SituationRepository s, SituationParticipantRepository p, Clock c) { return new SituationCreateService(v, t, pv, w, s, p, c); }
        @Bean SituationQueryService queries(SituationRepository s, SituationParticipantRepository p) { return new SituationQueryService(s, p); }
    }
}
