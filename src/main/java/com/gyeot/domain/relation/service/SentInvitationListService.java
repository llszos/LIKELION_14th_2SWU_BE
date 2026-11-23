package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.dto.response.SentInvitationListResponse;
import com.gyeot.domain.relation.entity.Invitation;
import com.gyeot.domain.relation.entity.InvitationStatus;
import com.gyeot.domain.relation.repository.InvitationRepository;
import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 로그인 사용자가 발신한 모든 유형의 초대를 최근 생성순으로 조회합니다. */
@Service
@RequiredArgsConstructor
public class SentInvitationListService {

    private final InvitationRepository invitationRepository;
    private final UserProfileQuery userProfileQuery;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SentInvitationListResponse getSentInvitations(Long inviterId) {
        if (inviterId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }

        List<Invitation> invitations = invitationRepository.findAllByInviterIdOrderByCreatedAtDescIdDesc(inviterId);
        List<Long> responderIds = invitations.stream()
                .map(Invitation::getInviteeId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, UserProfileQuery.UserProfile> profiles = userProfileQuery.findProfilesByUserIds(responderIds);
        LocalDateTime now = LocalDateTime.now(clock);

        List<SentInvitationListResponse.Item> items = invitations.stream()
                .map(invitation -> toItem(invitation, profiles, now))
                .toList();
        return new SentInvitationListResponse(items);
    }

    private SentInvitationListResponse.Item toItem(
            Invitation invitation,
            Map<Long, UserProfileQuery.UserProfile> profiles,
            LocalDateTime now
    ) {
        Long responderId = invitation.getInviteeId();
        SentInvitationListResponse.Responder responder = null;
        if (responderId != null) {
            UserProfileQuery.UserProfile profile = profiles.get(responderId);
            responder = new SentInvitationListResponse.Responder(
                    responderId,
                    profile == null ? null : profile.name()
            );
        }

        InvitationStatus status = invitation.getStatus() == InvitationStatus.PENDING
                && !now.isBefore(invitation.getExpiresAt())
                ? InvitationStatus.EXPIRED
                : invitation.getStatus();
        return new SentInvitationListResponse.Item(
                invitation.getId(),
                invitation.getInvitationType(),
                status,
                responder,
                invitation.getCreatedAt(),
                invitation.getExpiresAt()
        );
    }
}
