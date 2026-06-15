package site.krip.global.support;

/**
 * 로그에 넣을 사용자 입력의 개행·제어문자 무력화 — CRLF 로그 위조(CWE-117) 방지.
 */
public final class LogSafe {

    private LogSafe() {
    }

    /** 제어문자(CR/LF/탭 등)를 '_' 로 치환. null 통과. */
    public static String clean(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isISOControl(c) ? '_' : c);
        }
        return sb.toString();
    }
}
