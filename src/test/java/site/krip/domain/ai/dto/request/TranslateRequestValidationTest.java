package site.krip.domain.ai.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TranslateRequest} 검증 — source/target 누락이 컨트롤러 경계에서 400 으로 걸러지는지 확인한다.
 * (이전엔 @Pattern 만 있어 null 이 통과 → 서비스의 source.equals(...) 에서 NPE→500 났던 회귀 방지)
 */
class TranslateRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("source 누락(null) 은 검증 위반 — NPE 전에 차단")
    void nullSourceIsRejected() {
        Set<ConstraintViolation<TranslateRequest>> v =
                validator.validate(new TranslateRequest("hi", null, "en"));
        assertThat(v).anyMatch(cv -> cv.getPropertyPath().toString().equals("source"));
    }

    @Test
    @DisplayName("target 누락(null) 은 검증 위반")
    void nullTargetIsRejected() {
        Set<ConstraintViolation<TranslateRequest>> v =
                validator.validate(new TranslateRequest("hi", "ko", null));
        assertThat(v).anyMatch(cv -> cv.getPropertyPath().toString().equals("target"));
    }

    @Test
    @DisplayName("빈 문자열 source 도 위반")
    void blankSourceIsRejected() {
        Set<ConstraintViolation<TranslateRequest>> v =
                validator.validate(new TranslateRequest("hi", "  ", "en"));
        assertThat(v).anyMatch(cv -> cv.getPropertyPath().toString().equals("source"));
    }

    @Test
    @DisplayName("ko|en 외 값은 위반")
    void unsupportedLangIsRejected() {
        Set<ConstraintViolation<TranslateRequest>> v =
                validator.validate(new TranslateRequest("hi", "fr", "en"));
        assertThat(v).anyMatch(cv -> cv.getPropertyPath().toString().equals("source"));
    }

    @Test
    @DisplayName("ko→en 정상 입력은 위반 없음")
    void validRequestPasses() {
        Set<ConstraintViolation<TranslateRequest>> v =
                validator.validate(new TranslateRequest("hi", "ko", "en"));
        assertThat(v).isEmpty();
    }
}
