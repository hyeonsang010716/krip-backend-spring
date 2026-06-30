package site.krip.domain.friend.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.jspecify.annotations.Nullable;
import site.krip.domain.auth.entity.QUser;
import site.krip.domain.auth.entity.QUserDetailInform;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.FriendshipStatus;
import site.krip.domain.friend.entity.QFriendship;

import java.time.Instant;
import java.util.List;

class FriendshipRepositoryCustomImpl implements FriendshipRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    FriendshipRepositoryCustomImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public List<Friendship> findFriends(String userId, FriendshipStatus status,
                                        @Nullable Instant cursorAt, @Nullable String cursor, int limit) {
        QFriendship f = QFriendship.friendship;
        // requester/addressee 둘 다 User → 각자 detail 까지 4개 alias 분리(기본 alias 중복 금지).
        QUser requester = new QUser("requester");
        QUserDetailInform reqDetail = new QUserDetailInform("reqDetail");
        QUser addressee = new QUser("addressee");
        QUserDetailInform addrDetail = new QUserDetailInform("addrDetail");

        BooleanBuilder where = new BooleanBuilder(
                f.status.eq(status).and(f.requesterId.eq(userId).or(f.addresseeId.eq(userId))));
        applyKeyset(where, f, cursorAt, cursor);

        return queryFactory.selectFrom(f)
                .leftJoin(f.requester, requester).fetchJoin()
                .leftJoin(requester.detail, reqDetail).fetchJoin()
                .leftJoin(f.addressee, addressee).fetchJoin()
                .leftJoin(addressee.detail, addrDetail).fetchJoin()
                .where(where)
                .orderBy(f.updatedAt.desc(), f.friendshipId.desc())
                .limit(limit)
                .fetch();
    }

    @Override
    public List<Friendship> findReceived(String userId, FriendshipStatus status,
                                         @Nullable Instant cursorAt, @Nullable String cursor, int limit) {
        QFriendship f = QFriendship.friendship;
        QUser requester = new QUser("requester");
        QUserDetailInform reqDetail = new QUserDetailInform("reqDetail");

        BooleanBuilder where = new BooleanBuilder(f.addresseeId.eq(userId).and(f.status.eq(status)));
        applyKeyset(where, f, cursorAt, cursor);

        return queryFactory.selectFrom(f)
                .leftJoin(f.requester, requester).fetchJoin()
                .leftJoin(requester.detail, reqDetail).fetchJoin()
                .where(where)
                .orderBy(f.updatedAt.desc(), f.friendshipId.desc())
                .limit(limit)
                .fetch();
    }

    @Override
    public List<Friendship> findSent(String userId, FriendshipStatus status,
                                     @Nullable Instant cursorAt, @Nullable String cursor, int limit) {
        QFriendship f = QFriendship.friendship;
        QUser addressee = new QUser("addressee");
        QUserDetailInform addrDetail = new QUserDetailInform("addrDetail");

        BooleanBuilder where = new BooleanBuilder(f.requesterId.eq(userId).and(f.status.eq(status)));
        applyKeyset(where, f, cursorAt, cursor);

        return queryFactory.selectFrom(f)
                .leftJoin(f.addressee, addressee).fetchJoin()
                .leftJoin(addressee.detail, addrDetail).fetchJoin()
                .where(where)
                .orderBy(f.updatedAt.desc(), f.friendshipId.desc())
                .limit(limit)
                .fetch();
    }

    /** 키셋 이후: (updatedAt < cursorAt) or (updatedAt == cursorAt and friendshipId < cursor). */
    private void applyKeyset(BooleanBuilder where, QFriendship f,
                             @Nullable Instant cursorAt, @Nullable String cursor) {
        if (cursorAt != null && cursor != null) {
            where.and(f.updatedAt.lt(cursorAt)
                    .or(f.updatedAt.eq(cursorAt).and(f.friendshipId.lt(cursor))));
        }
    }
}
