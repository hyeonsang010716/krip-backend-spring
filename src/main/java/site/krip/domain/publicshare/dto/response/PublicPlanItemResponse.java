package site.krip.domain.publicshare.dto.response;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 공개 share 응답의 카드 단건.
 *
 * <p>{@code rating}/{@code photos} 는 MongoDB Place 라이브 값. photos 는 없으면 빈 배열.
 */
public record PublicPlanItemResponse(
        String itemId,
        int dayNumber,
        double position,
        String placeId,
        String displayName,
        String address,
        @Nullable String visitTime,
        @Nullable Double rating,
        List<String> photos
) {
}
