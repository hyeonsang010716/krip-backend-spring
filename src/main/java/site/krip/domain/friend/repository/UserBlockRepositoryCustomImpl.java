package site.krip.domain.friend.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.jspecify.annotations.Nullable;
import site.krip.domain.auth.entity.QUser;
import site.krip.domain.auth.entity.QUserDetailInform;
import site.krip.domain.friend.entity.QUserBlock;
import site.krip.domain.friend.entity.UserBlock;

import java.time.Instant;
import java.util.List;

class UserBlockRepositoryCustomImpl implements UserBlockRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    UserBlockRepositoryCustomImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public List<UserBlock> findBlocks(String blockerId, @Nullable Instant cursorAt,
                                      @Nullable String cursor, int limit) {
        QUserBlock b = QUserBlock.userBlock;
        QUser blocked = QUser.user;
        QUserDetailInform detail = QUserDetailInform.userDetailInform;

        BooleanBuilder where = new BooleanBuilder(b.blockerId.eq(blockerId));
        if (cursorAt != null && cursor != null) {
            // 키셋 이후: (createdAt < cursorAt) or (createdAt == cursorAt and blockId < cursor)
            where.and(b.createdAt.lt(cursorAt)
                    .or(b.createdAt.eq(cursorAt).and(b.blockId.lt(cursor))));
        }

        return queryFactory.selectFrom(b)
                .leftJoin(b.blocked, blocked).fetchJoin()
                .leftJoin(blocked.detail, detail).fetchJoin()
                .where(where)
                .orderBy(b.createdAt.desc(), b.blockId.desc())
                .limit(limit)
                .fetch();
    }
}
