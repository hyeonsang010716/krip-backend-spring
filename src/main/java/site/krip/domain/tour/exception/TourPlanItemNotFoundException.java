package site.krip.domain.tour.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 카드를 찾을 수 없음 — 404.
 *
 * <p>다음을 통합: item_id 없음 / item 은 있으나 소속 plan 이 사라짐 / URL 의 plan_id 와 불일치.
 * 리소스 enumeration 방어를 위해 메시지는 모두 "존재하지 않는 카드입니다." 로 통일.
 */
public class TourPlanItemNotFoundException extends ApiException {

    public TourPlanItemNotFoundException(String message) {
        super(404, message);
    }
}
