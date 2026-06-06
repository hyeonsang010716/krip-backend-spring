package site.krip.global.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 코드포인트(이모지 1자) 기준 최대 길이 검증.
 *
 * <p>{@code @Size} 는 UTF-16 코드유닛 기준이라 비-BMP 문자(이모지)를 2로 세어 과도 거부한다.
 * Postgres {@code varchar(n)}(코드포인트 기준)과 일치시키기 위해 이 제약을 쓴다. {@code null} 은 통과.
 */
@Documented
@Constraint(validatedBy = CodePointSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointSize {

    int max();

    String message() default "최대 {max}자까지 가능합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
