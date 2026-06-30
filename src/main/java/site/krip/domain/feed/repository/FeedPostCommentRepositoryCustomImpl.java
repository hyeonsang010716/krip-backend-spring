package site.krip.domain.feed.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.jspecify.annotations.Nullable;
import site.krip.domain.feed.entity.FeedPostComment;
import site.krip.domain.feed.entity.QFeedPostComment;

import java.time.Instant;
import java.util.List;

class FeedPostCommentRepositoryCustomImpl implements FeedPostCommentRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    FeedPostCommentRepositoryCustomImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public List<FeedPostComment> findByPost(String postId, @Nullable Instant cursorAt,
                                            @Nullable String cursor, int limit) {
        QFeedPostComment c = QFeedPostComment.feedPostComment;

        BooleanBuilder where = new BooleanBuilder(c.postId.eq(postId));
        if (cursorAt != null && cursor != null) {
            // 키셋 이후: (createdAt < cursorAt) or (createdAt == cursorAt and commentId < cursor)
            where.and(c.createdAt.lt(cursorAt)
                    .or(c.createdAt.eq(cursorAt).and(c.commentId.lt(cursor))));
        }

        return queryFactory.selectFrom(c)
                .where(where)
                .orderBy(c.createdAt.desc(), c.commentId.desc())
                .limit(limit)
                .fetch();
    }
}
