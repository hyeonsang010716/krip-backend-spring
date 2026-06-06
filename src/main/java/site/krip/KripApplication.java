package site.krip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;


// 자체 SecurityFilterChain 사용 — 기본 인메모리 유저 자동설정 비활성.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class KripApplication {

    public static void main(String[] args) {
        SpringApplication.run(KripApplication.class, args);
    }
}
