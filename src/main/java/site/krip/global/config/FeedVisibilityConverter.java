package site.krip.global.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import site.krip.domain.feed.entity.FeedVisibility;

/**
 * 쿼리 파라미터 {@code visibility=public} → {@link FeedVisibility} 변환.
 * 기본 enum 변환은 이름(PUBLIC) 기준이라 value(public) 매칭용 컨버터가 필요하다.
 */
@Component
public class FeedVisibilityConverter implements Converter<String, FeedVisibility> {

    @Override
    public FeedVisibility convert(String source) {
        return FeedVisibility.from(source);
    }
}
