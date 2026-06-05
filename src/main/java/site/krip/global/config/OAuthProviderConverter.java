package site.krip.global.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import site.krip.domain.auth.entity.OAuthProvider;

/**
 * 쿼리 파라미터 {@code type=google} → {@link OAuthProvider} 변환.
 * 기본 enum 변환은 이름(GOOGLE) 기준이라 value(google) 매칭용 컨버터가 필요하다.
 */
@Component
public class OAuthProviderConverter implements Converter<String, OAuthProvider> {

    @Override
    public OAuthProvider convert(String source) {
        return OAuthProvider.fromValue(source);
    }
}
