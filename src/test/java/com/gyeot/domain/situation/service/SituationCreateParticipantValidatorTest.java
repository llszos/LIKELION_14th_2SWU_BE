package com.gyeot.domain.situation.service;

import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SituationCreateParticipantValidatorTest {
    private final UserRepository users=mock(UserRepository.class);
    private final RelationshipQueryRepository relationships=mock(RelationshipQueryRepository.class);
    private final SituationCreateParticipantValidator validator=new SituationCreateParticipantValidator(users,relationships);

    @Test void emptyListNeedsNoUserOrRelationLookup(){validator.validate(10L,List.of());verifyNoInteractions(users,relationships);}
    @Test void nullDuplicateAndSelfIdsFailBeforeAnyLookup(){
        for(List<Long> ids:Arrays.asList(null,Arrays.asList(20L,null),List.of(20L,20L),List.of(20L,10L)))error(ids,"INVALID_INPUT");
        verifyNoInteractions(users,relationships);
    }
    @Test void validatesEveryActiveTargetInCreatorToTargetDirection(){
        for(Long id:List.of(20L,30L)){active(id);when(relationships.existsBySourceUserIdAndTargetUserIdAndStatus(10L,id,RelationshipStatus.ACTIVE)).thenReturn(true);}
        validator.validate(10L,List.of(20L,30L));
        for(Long id:List.of(20L,30L))verify(relationships).existsBySourceUserIdAndTargetUserIdAndStatus(10L,id,RelationshipStatus.ACTIVE);
        verifyNoMoreInteractions(relationships);
    }
    @Test void missingUserIsNotFound(){error(List.of(20L),"USER_NOT_FOUND");verifyNoInteractions(relationships);}
    @Test void withdrawnUserUsesExistingAError(){when(users.existsById(20L)).thenReturn(true);when(users.existsByIdAndAccountStatus(20L,AccountStatus.WITHDRAWN)).thenReturn(true);error(List.of(20L),"USER_ALREADY_WITHDRAWN");verifyNoInteractions(relationships);}
    @Test void inactiveUserIsRejected(){when(users.existsById(20L)).thenReturn(true);error(List.of(20L),"USER_NOT_ACTIVE");verifyNoInteractions(relationships);}
    @Test void missingDirectedActiveRelationshipIsForbidden(){active(20L);error(List.of(20L),"FORBIDDEN");}
    @Test void failureStopsBeforeLaterTargetsAndDoesNotModifyInput(){active(20L);var ids=new ArrayList<>(List.of(20L,30L));error(ids,"FORBIDDEN");assertThat(ids).containsExactly(20L,30L);verify(users,never()).existsById(30L);}
    private void active(Long id){when(users.existsById(id)).thenReturn(true);when(users.existsByIdAndAccountStatus(id,AccountStatus.ACTIVE)).thenReturn(true);}
    private void error(List<Long> ids,String code){assertThatThrownBy(()->validator.validate(10L,ids)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode().getCode()).isEqualTo(code));}
}
