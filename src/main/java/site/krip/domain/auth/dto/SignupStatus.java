package site.krip.domain.auth.dto;

/** 회원가입 진행 상태. */
public enum SignupStatus {
    NEW("new"),                                 // 최초 방문 → 1차 가입 생성
    IN_PROGRESS("in_progress"),                 // 1차 완료, 2차 미완료
    COMPLETE("complete"),                       // 2차까지 완료
    WITHDRAWAL_PENDING("withdrawal_pending");   // 탈퇴 30일 유예 중 — 로그인 차단

    private final String value;

    SignupStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
