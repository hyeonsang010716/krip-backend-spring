package site.krip.domain.tripmate.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.jspecify.annotations.Nullable;
import site.krip.domain.tripmate.entity.QTripmatePostLike;
import site.krip.domain.tripmate.entity.TripmatePostLike;

import java.time.Instant;
import java.util.List;

class TripmatePostLikeRepositoryCustomImpl implements TripmatePostLikeRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    TripmatePostLikeRepositoryCustomImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public List<TripmatePostLike> findLikes(String postId, @Nullable Instant cursorAt,
                                            @Nullable String cursorUserId, int limit) {
        QTripmatePostLike l = QTripmatePostLike.tripmatePostLike;

        BooleanBuilder where = new BooleanBuilder(l.postId.eq(postId));
        if (cursorAt != null && cursorUserId != null) {
            // 키셋 이후: (createdAt < cursorAt) or (createdAt == cursorAt and userId < cursorUserId)
            where.and(l.createdAt.lt(cursorAt)
                    .or(l.createdAt.eq(cursorAt).and(l.userId.lt(cursorUserId))));
        }

        return queryFactory.selectFrom(l)
                .where(where)
                .orderBy(l.createdAt.desc(), l.userId.desc())
                .limit(limit)
                .fetch();
    }
}
