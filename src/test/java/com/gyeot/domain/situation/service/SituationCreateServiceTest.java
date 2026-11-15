package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.request.SituationCreateRequest;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.repository.*;
import com.gyeot.global.exception.BusinessException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SituationCreateServiceTest {
    private final SituationCreationTypeResolver types=mock(SituationCreationTypeResolver.class);
    private final SituationCreateParticipantValidator validation=mock(SituationCreateParticipantValidator.class);
    private final SituationRepository situations=mock(SituationRepository.class);
    private final SituationParticipantRepository participants=mock(SituationParticipantRepository.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"),ZoneOffset.UTC);
    private final SituationCreateWriter writer=new SituationCreateWriter(situations,participants,clock);
    private final jakarta.validation.ValidatorFactory factory=Validation.buildDefaultValidatorFactory();
    private final SituationCreateService service=new SituationCreateService(factory.getValidator(),types,validation,writer,situations,participants,clock);
    private final LocalDateTime start=LocalDateTime.of(2026,11,20,9,0);
    @BeforeEach void setUp(){
        when(types.resolve("test-type",null)).thenReturn("resolved-type");
        when(situations.save(any(Situation.class))).thenAnswer(i->{Situation s=i.getArgument(0);ReflectionTestUtils.setField(s,"id",7L);return s;});
        when(situations.getReferenceById(7L)).thenAnswer(i->Situation.create(10L,"resolved-type","이름",start,start.plusHours(1)));
    }
    @AfterEach void close(){factory.close();}
    @Test void savesOwnerThenSelectedParticipantsOnlyAfterValidation(){
        var result=service.create(10L,request(List.of(20L,30L)));
        assertThat(result.situationId()).isEqualTo(7L);assertThat(result.creatorId()).isEqualTo(10L);assertThat(result.status()).isEqualTo(SituationStatus.ACTIVE);
        var rows=org.mockito.ArgumentCaptor.forClass(SituationParticipant.class);
        verify(participants,times(3)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(SituationParticipant::getUserId).containsExactly(10L,20L,30L);
        assertThat(rows.getAllValues()).extracting(SituationParticipant::getRole).containsExactly(SituationParticipantRole.OWNER,SituationParticipantRole.PARTICIPANT,SituationParticipantRole.PARTICIPANT);
        var order=inOrder(types,validation,situations,participants);
        order.verify(types).resolve("test-type",null);order.verify(validation).validate(10L,List.of(20L,30L));order.verify(situations).save(any());
    }
    @Test void emptySelectionStillSavesOwner(){service.create(10L,request(List.of()));verify(participants).save(any());}
    @Test void validationFailureDoesNotPartiallySave(){doThrow(new IllegalArgumentException("invalid participants")).when(validation).validate(10L,List.of(20L));assertThatThrownBy(()->service.create(10L,request(List.of(20L)))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(situations,participants);}
    @Test void invalidPeriodDoesNotReachTypeOrStorage(){var r=new SituationCreateRequest("test-type",null,"이름",List.of(),start,start);assertThatThrownBy(()->service.create(10L,r)).isInstanceOf(BusinessException.class);verifyNoInteractions(types,validation,situations,participants);}
    @Test void realTypeBoundaryRejectsAllInputsIncludingCustomWithoutSaving(){
        var subject=new SituationCreateService(factory.getValidator(),new SituationCreationTypeResolver(),validation,writer,situations,participants,clock);
        for(String custom:Arrays.asList(null,"기타 내용")){
            var r=new SituationCreateRequest("test-type",custom,"이름",List.of(),start,start.plusHours(1));
            assertThatThrownBy(()->subject.create(10L,r)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode().getCode()).isEqualTo("SITUATION_TYPE_NOT_CONFIGURED"));
        }
        verifyNoInteractions(validation,situations,participants);
    }
    private SituationCreateRequest request(List<Long> ids){return new SituationCreateRequest("test-type",null,"이름",ids,start,start.plusHours(1));}
}
