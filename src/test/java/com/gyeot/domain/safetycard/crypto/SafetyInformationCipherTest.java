package com.gyeot.domain.safetycard.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 암호화 왕복, 저장 형식, 잘못된 키·조작된 값 처리를 확인합니다. DB나 Docker는 필요 없습니다. */
class SafetyInformationCipherTest {

    private static final String KEY = "zlR0XwlvmRcg3Fu46qZmy4VCb4y5yVx0oY+fXn8iez4="; // application-test.yaml과 같은 테스트 키
    private static final String OTHER_KEY = "ZvqfM2q/dTM89/Q+v4OHYNmShqA/If1F754Ed3RnzhE=";

    private final SafetyInformationCipher cipher = new SafetyInformationCipher(KEY); // Spring 없이 직접 만들어 테스트

    @Test
    void 암호화한_값을_복호화하면_원문이_나온다() {
        String plain = "혈압약 아침 식후 1정";

        String encrypted = cipher.encrypt(plain);

        assertThat(encrypted).isNotEqualTo(plain);                  // DB에는 원문이 그대로 저장되지 않음
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plain);     // 복호화하면 원래 내용으로 돌아옴
    }

    @Test
    void 같은_내용도_암호화할_때마다_다른_값이_된다() {
        // 매번 nonce가 달라서, DB 값만 보고 같은 내용인지 알아낼 수 없음
        assertThat(cipher.encrypt("페니실린")).isNotEqualTo(cipher.encrypt("페니실린"));
    }

    @Test
    void 저장_형식은_nonce_12바이트_암호문_태그_16바이트다() {
        String plain = "abc";

        byte[] stored = Base64.getDecoder().decode(cipher.encrypt(plain));

        assertThat(stored).hasSize(12 + plain.length() + 16); // nonce 12 + 원문 3바이트 + 태그 16
    }

    @Test
    void 최대_길이_title도_컬럼_1000자_안에_들어간다() {
        String longestTitle = "가".repeat(150); // 한글 150자 = 450바이트

        assertThat(cipher.encrypt(longestTitle).length()).isLessThanOrEqualTo(1000); // title 컬럼 VARCHAR(1000)
    }

    @Test
    void null은_그대로_둔다() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void 다른_키로는_복호화할_수_없다() {
        String encrypted = new SafetyInformationCipher(OTHER_KEY).encrypt("주소"); // 다른 키로 암호화한 값

        assertThatThrownBy(() -> cipher.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 값이_조작되면_복호화할_수_없다() {
        byte[] stored = Base64.getDecoder().decode(cipher.encrypt("출입 방법"));
        stored[stored.length - 1] ^= 1; // 마지막 바이트 변경
        String tampered = Base64.getEncoder().encodeToString(stored);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 암호화되지_않은_평문은_복호화할_수_없다() {
        // 암호화 적용 전에 저장된 데이터가 남아 있는 경우 (그래서 적용 시 DB를 초기화함)
        assertThatThrownBy(() -> cipher.decrypt("평문 그대로 남은 값")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키가_없거나_32바이트가_아니면_시작할_수_없다() {
        assertThatThrownBy(() -> new SafetyInformationCipher("")).isInstanceOf(IllegalStateException.class);            // 환경변수 누락
        assertThatThrownBy(() -> new SafetyInformationCipher("not-base64!")).isInstanceOf(IllegalStateException.class); // Base64 아님
        assertThatThrownBy(() -> new SafetyInformationCipher(Base64.getEncoder().encodeToString(new byte[16])))           // 16바이트(AES-128)
                .isInstanceOf(IllegalStateException.class);
    }
}
