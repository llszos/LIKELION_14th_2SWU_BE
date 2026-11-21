package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.request.SituationCreateRequest;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.repository.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 DB 대신 기록용 매니저로 Service·Writer·선택 참여자 저장의 동일 경계를 확인합니다. */
class SituationCreateServiceTransactionTest {
    private AnnotationConfigApplicationContext context;
    private SituationCreateService service;
    private SituationParticipantRepository participants;
    private SituationCreateWriterTransactionTest.RecordingTransactionManager tx;
    @BeforeEach void setUp(){
        context=new AnnotationConfigApplicationContext(Config.class);
        service=context.getBean(SituationCreateService.class);participants=context.getBean(SituationParticipantRepository.class);
        tx=context.getBean(SituationCreateWriterTransactionTest.RecordingTransactionManager.class);
        var situations=context.getBean(SituationRepository.class);
        when(situations.save(any(Situation.class))).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();Situation s=i.getArgument(0);ReflectionTestUtils.setField(s,"id",7L);return s;});
        when(situations.getReferenceById(7L)).thenReturn(Situation.create(10L,"type","이름",LocalDateTime.of(2026,11,20,9,0),LocalDateTime.of(2026,11,20,10,0)));
        when(context.getBean(SituationCreationTypeResolver.class).resolve("type",null)).thenReturn("type");
    }
    @AfterEach void close(){context.close();}
    @Test void commitsOneTransactionAcrossOwnerAndSelectedSaves(){
        when(participants.save(any())).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();return i.getArgument(0);});
        service.create(10L,request());
        verify(participants,times(2)).save(any());assertThat(tx.begins).isEqualTo(1);assertThat(tx.commits).isEqualTo(1);assertThat(tx.rollbacks).isZero();
    }
    @Test void selectedSaveFailureRequestsRollbackOfWholeFlow(){
        var failure=new IllegalStateException("selected participant save failed");
        when(participants.save(any())).thenAnswer(i->{SituationParticipant p=i.getArgument(0);assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();if(p.getUserId().equals(20L))throw failure;return p;});
        assertThatThrownBy(()->service.create(10L,request())).isSameAs(failure);
        assertThat(tx.begins).isEqualTo(1);assertThat(tx.commits).isZero();assertThat(tx.rollbacks).isEqualTo(1);
    }
    private SituationCreateRequest request(){var start=LocalDateTime.of(2026,11,20,9,0);return new SituationCreateRequest("type",null,"이름",List.of(20L),start,start.plusHours(1));}
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true)
    static class Config {
        @Bean SituationRepository situations(){return mock(SituationRepository.class);}
        @Bean SituationParticipantRepository participants(){return mock(SituationParticipantRepository.class);}
        @Bean SituationCreationTypeResolver types(){return mock(SituationCreationTypeResolver.class);}
        @Bean SituationCreateParticipantValidator participantValidator(){return mock(SituationCreateParticipantValidator.class);}
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"),ZoneOffset.UTC);}
        @Bean(destroyMethod="close") ValidatorFactory factory(){return Validation.buildDefaultValidatorFactory();}
        @Bean Validator validator(ValidatorFactory factory){return factory.getValidator();}
        @Bean SituationCreateWriterTransactionTest.RecordingTransactionManager transactionManager(){
            return new SituationCreateWriterTransactionTest.RecordingTransactionManager(){
                @Override protected boolean isExistingTransaction(Object transaction){
                    return TransactionSynchronizationManager.isActualTransactionActive();
                }
            };
        }
        @Bean SituationCreateWriter writer(SituationRepository s,SituationParticipantRepository p,Clock c){return new SituationCreateWriter(s,p,c);}
        @Bean SituationCreateService service(Validator v,SituationCreationTypeResolver t,SituationCreateParticipantValidator pv,SituationCreateWriter w,SituationRepository s,SituationParticipantRepository p,Clock c){return new SituationCreateService(v,t,pv,w,s,p,c);}
    }
}
