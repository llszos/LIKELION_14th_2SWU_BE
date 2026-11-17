package com.gyeot.domain.situation.service;

import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.query.SituationParticipantReaderImpl;
import com.gyeot.domain.situation.repository.*;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/** 일회용 MySQL 전용. Docker 환경에서 별도 실행해야 합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class,SafetyInformationCipher.class,SituationParticipantReaderImpl.class,
        SituationParticipantsQueryService.class})
@ActiveProfiles("test")
@Testcontainers
class SituationParticipantsQueryIntegrationTest {
    @Container @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4")).withEnv("TZ","Asia/Seoul");
    @Autowired SituationRepository situations;
    @Autowired SituationParticipantRepository participants;
    @Autowired SituationParticipantsQueryService service;
    @Autowired EntityManager em;
    private final LocalDateTime joined=LocalDateTime.of(2026,11,15,9,0);

    @Test void filtersAndSortsActiveRowsIncludingOwnerAndAllowsEndedSituation() {
        var s=save();
        var later=add(s,20L,SituationParticipantRole.PARTICIPANT,joined.plusHours(1));
        var first=add(s,10L,SituationParticipantRole.OWNER,joined);
        var second=add(s,30L,SituationParticipantRole.PARTICIPANT,joined);
        add(s,40L,SituationParticipantRole.PARTICIPANT,joined).leave(joined.plusHours(2));
        add(s,50L,SituationParticipantRole.PARTICIPANT,joined).remove(joined.plusHours(2));
        add(save(),60L,SituationParticipantRole.PARTICIPANT,joined);
        s.end(joined.plusHours(3));
        em.flush(); em.clear();
        var result=service.list(s.getId(),20L);
        assertThat(result.items()).extracting(i->i.participantId())
                .containsExactly(first.getId(),second.getId(),later.getId());
        assertThat(result.items().getFirst().role()).isEqualTo(SituationParticipantRole.OWNER);
    }
    @Test void deniesInactiveMissingAndOwnerWithoutActiveParticipation() {
        var s=save();
        add(s,20L,SituationParticipantRole.PARTICIPANT,joined).leave(joined.plusHours(1));
        add(s,30L,SituationParticipantRole.PARTICIPANT,joined).remove(joined.plusHours(1));
        em.flush(); em.clear();
        for(Long viewer:new Long[]{10L,20L,30L,99L})
            assertThatThrownBy(()->service.list(s.getId(),viewer)).isInstanceOfSatisfying(BusinessException.class,
                    e->assertThat(e.getErrorCode().getCode()).isEqualTo("SITUATION_NOT_FOUND"));
        assertThatThrownBy(()->service.list(Long.MAX_VALUE,10L)).isInstanceOf(BusinessException.class);
    }
    private Situation save(){return situations.save(Situation.create(10L,"유형","이름",
            LocalDate.of(2026,11,15),LocalDate.of(2026,11,20)));}
    private SituationParticipant add(Situation s,Long user,SituationParticipantRole role,LocalDateTime at){
        return participants.save(SituationParticipant.create(s,user,role,at));
    }
}
