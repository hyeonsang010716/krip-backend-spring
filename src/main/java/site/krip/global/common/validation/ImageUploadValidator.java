package site.krip.global.common.validation;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import site.krip.global.common.exception.ApiException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 이미지 업로드 공통 검증 — content-type 화이트리스트 + 최대 크기.
 * 허용 타입/크기는 호출부 정책으로 주입(예: 피드는 gif 제외, 프로필은 5MB).
 */
@Component
public class ImageUploadValidator {

    public void validate(MultipartFile file, List<String> allowedContentTypes, long maxBytes) {
        if (!allowedContentTypes.contains(file.getContentType())) {
            String allowed = allowedContentTypes.stream()
                    .map(t -> t.startsWith("image/") ? t.substring("image/".length()) : t)
                    .collect(Collectors.joining(", "));
            throw ApiException.badRequest("허용되지 않는 파일 형식입니다: " + file.getContentType()
                    + " (" + allowed + "만 가능)");
        }
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest("파일 크기가 " + (maxBytes / (1024 * 1024)) + "MB 를 초과합니다.");
        }
    }
}
