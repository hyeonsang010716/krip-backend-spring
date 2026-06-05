package site.krip.domain.tour.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 플랜을 찾을 수 없음 — 404.
 *
 * <p>plan_id 로 직접 조회했는데 row 가 없거나(또는 race 로 사라진) 경우.
 */
public class TourPlanNotFoundException extends ApiException {

    public TourPlanNotFoundException(String message) {
        super(404, message);
    }
}
