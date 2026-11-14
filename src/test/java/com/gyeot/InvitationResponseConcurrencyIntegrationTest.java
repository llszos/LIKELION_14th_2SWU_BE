package com.gyeot;

import com.gyeot.domain.relation.entity.Invitation;
import com.gyeot.domain.relation.entity.InvitationStatus;
import com.gyeot.domain.relation.entity.InvitationType;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.repository.InvitationRepository;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.relation.service.InvitationService;
import com.gyeot.domain.relation.service.InvitationTokenGenerator;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InvitationResponseConcurrencyIntegrationTest {

    @Autowired private InvitationService invitationService;
    @Autowired private InvitationRepository invitationRepository;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InvitationTokenGenerator tokenGenerator;

    @Test
    void sameInvitationCannotBeAcceptedTwiceConcurrently() throws Exception {
        Long inviterId = createUser("inviter");
        Long firstInviteeId = createUser("first-invitee");
        Long secondInviteeId = createUser("second-invitee");
        String token = createInvitation(inviterId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> first = executor.submit(() -> acceptAfter(start, token, firstInviteeId));
            Future<Object> second = executor.submit(() -> acceptAfter(start, token, secondInviteeId));
            start.countDown();
            List<Object> results = List.of(first.get(), second.get());

            assertEquals(1, results.stream().filter(Boolean.TRUE::equals).count());
            assertEquals(1, results.stream().filter(BusinessException.class::isInstance).count());
            Invitation saved = invitationRepository.findByTokenHash(tokenGenerator.hash(token)).orElseThrow();
            assertEquals(InvitationStatus.ACCEPTED, saved.getStatus());
            assertNotNull(saved.getInviteeId());
            List<Relationship> relationships = relationshipRepository.findAll().stream()
                    .filter(relationship -> inviterId.equals(relationship.getSourceUserId()))
                    .toList();
            assertEquals(1, relationships.size());
            assertEquals(inviterId, relationships.getFirst().getSourceUserId());
            assertEquals(saved.getInviteeId(), relationships.getFirst().getTargetUserId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void acceptingAndCancelingSameInvitationLeavesOneConsistentOutcome() throws Exception {
        Long inviterId = createUser("inviter-cancel-race");
        Long inviteeId = createUser("invitee-cancel-race");
        String token = createInvitation(inviterId);
        Long invitationId = invitationRepository.findByTokenHash(tokenGenerator.hash(token)).orElseThrow().getId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> accept = executor.submit(() -> acceptAfter(start, token, inviteeId));
            Future<Object> cancel = executor.submit(() -> cancelAfter(start, inviterId, invitationId));
            start.countDown();
            List<Object> results = List.of(accept.get(), cancel.get());

            assertEquals(1, results.stream().filter(Boolean.TRUE::equals).count());
            assertEquals(1, results.stream().filter(BusinessException.class::isInstance).count());
            Invitation saved = invitationRepository.findById(invitationId).orElseThrow();
            List<Relationship> relationships = relationshipRepository.findAll().stream()
                    .filter(relationship -> inviterId.equals(relationship.getSourceUserId()))
                    .toList();
            if (saved.getStatus() == InvitationStatus.ACCEPTED) {
                assertEquals(inviteeId, saved.getInviteeId());
                assertEquals(1, relationships.size());
                assertEquals(inviteeId, relationships.getFirst().getTargetUserId());
            } else {
                assertEquals(InvitationStatus.CANCELED, saved.getStatus());
                assertEquals(null, saved.getInviteeId());
                assertEquals(0, relationships.size());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private Object acceptAfter(CountDownLatch start, String token, Long inviteeId) throws Exception {
        start.await();
        try {
            invitationService.acceptInvitation(token, inviteeId);
            return true;
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private Object cancelAfter(CountDownLatch start, Long inviterId, Long invitationId) throws Exception {
        start.await();
        try {
            invitationService.cancelInvitation(inviterId, invitationId);
            return true;
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private Long createUser(String suffix) {
        String unique = suffix + "-" + System.nanoTime();
        return userRepository.save(User.register(unique + "@example.com", "password-hash", unique)).getId();
    }

    private String createInvitation(Long inviterId) {
        String token = "invitation-" + System.nanoTime();
        invitationRepository.save(Invitation.create(
                inviterId,
                InvitationType.MY_PERSON,
                tokenGenerator.hash(token),
                null,
                LocalDateTime.now().plusHours(1)
        ));
        return token;
    }
}
