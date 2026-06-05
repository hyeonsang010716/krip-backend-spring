package site.krip.domain.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.auth.entity.UserTravelStyle;

import java.util.Collection;
import java.util.List;

/** 유저 여행 스타일 RDB 접근. */
public interface UserTravelStyleRepository extends JpaRepository<UserTravelStyle, String> {

    @Modifying
    @Query("delete from UserTravelStyle s where s.user.userId = :userId")
    void deleteByUserId(@Param("userId") String userId);

    /** 여러 유저의 여행 스타일 일괄 조회 (친구 검색 결과 배치 로드). */
    @Query("select s from UserTravelStyle s where s.user.userId in :userIds")
    List<UserTravelStyle> findByUserIds(@Param("userIds") Collection<String> userIds);
}
