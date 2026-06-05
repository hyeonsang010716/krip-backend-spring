package site.krip.domain.chat.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 그룹 방 생성 요청. member_ids 는 본인 제외·친구만 허용, creator 포함 최대 100명(=초대 99명).
 */
public record CreateGroupRoomBody(
        @NotNull @Size(min = 1, max = 100) String title,
        @NotEmpty @Size(max = 99) List<String> memberIds
) {
}
