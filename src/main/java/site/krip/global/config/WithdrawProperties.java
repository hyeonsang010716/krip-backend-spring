package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 회원 탈퇴 유예/스케줄 설정.
 */
@ConfigurationProperties(prefix = "krip.withdraw")
public record WithdrawProperties(
        int gracePeriodDays,
        String purgeCron,
        String purgeZone
) {
}
