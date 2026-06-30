package site.krip.domain.tripmate.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.jspecify.annotations.Nullable;
import site.krip.domain.auth.entity.QUser;
import site.krip.domain.auth.entity.QUserDetailInform;
import site.krip.domain.friend.entity.QUserBlock;
import site.krip.domain.tripmate.entity.QTripmatePost;
import site.krip.domain.tripmate.entity.TripmatePost;

import java.time.Instant;
import java.util.List;

class TripmatePostRepositoryCustomImpl implements TripmatePostRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    TripmatePostRepositoryCustomImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public List<TripmatePost> findDisplayed(String viewerId, @Nullable Instant cursorAt,
                                            @Nullable String cursor, int limit) {
        QTripmatePost p = QTripmatePost.tripmatePost;
        QUser u = QUser.user;
        QUserDetailInform d = QUserDetailInform.userDetailInform;
        QUserBlock b = QUserBlock.userBlock;

        // 차단 관계(방향 무관) 작성자 제외 — 상관 서브쿼리.
        BooleanBuilder where = new BooleanBuilder(p.displayed.isTrue())
                .and(JPAExpressions.selectOne().from(b)
                        .where(b.blockerId.eq(viewerId).and(b.blockedId.eq(p.userId))
                                .or(b.blockerId.eq(p.userId).and(b.blockedId.eq(viewerId))))
                        .notExists());
        if (cursorAt != null && cursor != null) {
            // 키셋 이후: (createdAt < cursorAt) or (createdAt == cursorAt and postId < cursor)
            where.and(p.createdAt.lt(cursorAt)
                    .or(p.createdAt.eq(cursorAt).and(p.postId.lt(cursor))));
        }

        return queryFactory.selectFrom(p)
                .leftJoin(p.user, u).fetchJoin()
                .leftJoin(u.detail, d).fetchJoin()
                .where(where)
                .orderBy(p.createdAt.desc(), p.postId.desc())
                .limit(limit)
                .fetch();
    }
}
