package site.krip.domain.feed;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.domain.feed.entity.FeedPost;
import site.krip.domain.feed.entity.FeedVisibility;
import site.krip.domain.feed.repository.FeedPostRepository;
import site.krip.global.support.IdGenerator;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피드 E2E 공통 베이스 — S3 가 없는 환경이라 업로드 플로우를 못 타므로, 가시성/좋아요/댓글/삭제 검증에
 * 필요한 {@link FeedPost} 행을 리포지토리로 직접 시드한다. URL 3종은 NOT NULL 이므로 더미 값을 채운다.
 *
 * <p>친구/차단 시드는 {@link IntegrationTestSupport#makeFriends}/{@link IntegrationTestSupport#block} 사용.
 */
abstract class FeedTestSupport extends IntegrationTestSupport {

    @Autowired
    protected FeedPostRepository feedPostRepository;

    /** 지정 visibility 의 피드 게시물을 직접 INSERT 하고 post_id 반환. caption 은 null 가능. */
    protected String seedPost(String ownerId, FeedVisibility visibility, String caption) {
        String postId = IdGenerator.feedPostId();
        FeedPost post = new FeedPost(
                postId, ownerId, visibility, caption,
                "https://example.com/feed/" + postId + "/original.jpg",
                "https://example.com/feed/" + postId + "/small.jpg",
                "https://example.com/feed/" + postId + "/medium.jpg");
        // assigned-id → save() 는 merge 로 동작하나 반환 인스턴스만 사용하므로 안전.
        feedPostRepository.save(post);
        return postId;
    }

    /** liker 가 게시물에 좋아요(201). */
    protected void like(String liker, String postId) throws Exception {
        mockMvc.perform(post("/api/feed/posts/" + postId + "/like")
                        .with(auth(liker)))
                .andExpect(status().isCreated());
    }

    /** liker 가 좋아요 취소(200). */
    protected void unlike(String liker, String postId) throws Exception {
        mockMvc.perform(delete("/api/feed/posts/" + postId + "/like")
                        .with(auth(liker)))
                .andExpect(status().isOk());
    }

    /** commenter 가 댓글 작성(201) 후 comment_id 반환. */
    protected String comment(String commenter, String postId, String content) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/feed/posts/" + postId + "/comments")
                        .with(auth(commenter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("content", content)))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(res, "comment_id");
    }
}
