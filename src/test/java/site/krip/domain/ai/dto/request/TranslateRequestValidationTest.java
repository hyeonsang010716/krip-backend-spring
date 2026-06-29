package site.krip.domain.ai.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

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

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(new TranslateRequest("hi", null, "en"), "source"),  // source 누락(null) — NPE 전 차단
                Arguments.of(new TranslateRequest("hi", "ko", null), "target"),  // target 누락(null)
                Arguments.of(new TranslateRequest("hi", "  ", "en"), "source"),  // 빈 문자열 source
                Arguments.of(new TranslateRequest("hi", "fr", "en"), "source")); // ko|en 외 값
    }

    @ParameterizedTest(name = "{0} -> {1} 위반")
    @MethodSource("invalidRequests")
    @DisplayName("source/target 누락·빈값·미지원 언어는 해당 필드의 검증 위반을 낸다")
    void invalidRequestIsRejected(TranslateRequest request, String expectedProperty) {
        Set<ConstraintViolation<TranslateRequest>> v = validator.validate(request);
        assertThat(v).anyMatch(cv -> cv.getPropertyPath().toString().equals(expectedProperty));
    }

    @Test
    @DisplayName("ko→en 정상 입력은 위반 없음")
    void validRequestPasses() {
        Set<ConstraintViolation<TranslateRequest>> v =
                validator.validate(new TranslateRequest("hi", "ko", "en"));
        assertThat(v).isEmpty();
    }
}
