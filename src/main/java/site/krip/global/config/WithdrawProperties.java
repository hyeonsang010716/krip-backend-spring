package site.krip.global.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 회원 탈퇴 유예/스케줄 설정.
 */
@Validated
@ConfigurationProperties(prefix = "krip.withdraw")
public record WithdrawProperties(
        @PositiveOrZero int gracePeriodDays,
        @NotBlank String purgeCron,
        @NotBlank String purgeZone
) {
}
