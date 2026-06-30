package site.krip.domain.feed.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.jspecify.annotations.Nullable;
import site.krip.domain.feed.entity.FeedPostLike;
import site.krip.domain.feed.entity.QFeedPostLike;

import java.time.Instant;
import java.util.List;

class FeedPostLikeRepositoryCustomImpl implements FeedPostLikeRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    FeedPostLikeRepositoryCustomImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public List<FeedPostLike> findLikes(String postId, @Nullable Instant cursorAt,
                                        @Nullable String cursorUserId, int limit) {
        QFeedPostLike l = QFeedPostLike.feedPostLike;

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
