package site.krip.domain.tripmate.dto.response;

import java.util.List;

/** 이미지 업로드(다건) 응답. */
public record ImageUploadListResponse(List<ImageUploadResponse> images) {
}
