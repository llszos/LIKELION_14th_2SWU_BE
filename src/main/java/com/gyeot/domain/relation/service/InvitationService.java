package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.dto.response.InvitationCreateResponse;
import com.gyeot.domain.relation.entity.Invitation;
import com.gyeot.domain.relation.entity.InvitationStatus;
import com.gyeot.domain.relation.entity.InvitationType;
import com.gyeot.domain.relation.exception.InvitationErrorCode;
import com.gyeot.domain.relation.repository.InvitationRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class InvitationService {

    private static final long INVITATION_VALID_DAYS = 7;

    private final InvitationRepository invitationRepository;
    private final InvitationTokenGenerator tokenGenerator;
    private final Clock serviceClock;
    private final String invitationBaseUrl;

    public InvitationService(
            InvitationRepository invitationRepository,
            InvitationTokenGenerator tokenGenerator,
            Clock serviceClock,
            @Value("${gyeot.invitation.base-url}") String invitationBaseUrl
    ) {
        this.invitationRepository = invitationRepository;
        this.tokenGenerator = tokenGenerator;
        this.serviceClock = serviceClock;
        this.invitationBaseUrl = removeTrailingSlash(invitationBaseUrl);
    }

    /** 새 초대를 만들고, 원문 토큰은 링크를 구성해 응답하는 데만 사용합니다. */
    @Transactional
    public InvitationCreateResponse createInvitation(Long inviterId, InvitationType invitationType) {
        if (invitationType != InvitationType.MY_PERSON) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }

        InvitationTokenGenerator.IssuedInvitationToken issuedToken = tokenGenerator.issue();
        LocalDateTime now = LocalDateTime.now(serviceClock);
        LocalDateTime expiresAt = now.plusDays(INVITATION_VALID_DAYS);

        Invitation invitation = Invitation.create(
                inviterId,
                InvitationType.MY_PERSON,
                issuedToken.tokenHash(),
                null,
                expiresAt
        );
        Invitation savedInvitation = invitationRepository.save(invitation);

        String invitationUrl = invitationBaseUrl + "/" + issuedToken.token();
        return new InvitationCreateResponse(savedInvitation.getId(), invitationUrl, expiresAt);
    }

    /** 발신자 본인의 대기 중 초대만 취소합니다. 다시 초대하면 새 토큰으로 별도 행을 생성합니다. */
    @Transactional
    public void cancelInvitation(Long inviterId, Long invitationId) {
        Invitation invitation = invitationRepository.findByIdAndInviterId(invitationId, inviterId)
                .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_NOT_FOUND));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(InvitationErrorCode.INVITATION_NOT_PENDING);
        }

        invitation.cancel();
    }

    private String removeTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("초대 링크 기본 주소(gyeot.invitation.base-url)가 필요합니다.");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}