package Hampouch.server.domain.community;

import Hampouch.server.domain.community.entity.*;
import Hampouch.server.domain.community.repository.*;
import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.jwt.JwtProvider;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 커뮤니티 조회 API가 실제 MySQL(V1~V9 마이그레이션이 적용된 스키마)을 거쳐
 * 정상 동작하는지 검증한다. food_post_detail/recruit_post_detail의 공유 PK 저장/조회,
 * 벌크 업데이트(increaseViewCount)의 동시성 등은 H2로는 정확히 재현되지 않아
 * @MySqlContainerTest로 실제 MySQL 위에서 검증한다.
 */
@MySqlContainerTest
@AutoConfigureMockMvc
class CommunityFlowIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PostRepository postRepository;

    @Autowired
    FoodPostDetailRepository foodPostDetailRepository;

    @Autowired
    RecruitPostDetailRepository recruitPostDetailRepository;

    @Autowired
    PostImageRepository postImageRepository;

    @Autowired
    PostCommentRepository postCommentRepository;

    private String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId, UserRole.USER);
    }

    private Long createUser(String email, UserRole role) {
        User user = User.createSocialUser(email, AuthProvider.GOOGLE, "provider-id-" + email);
        setField(user, "nickname", "테스터-" + Math.abs(email.hashCode()));
        setField(user, "role", role);
        userRepository.saveAndFlush(user);
        return user.getId();
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Transactional
    void 게시글_목록_조회가_실제_DB로_동작한다() throws Exception {
        Long authorId = createUser("list-author@example.com", UserRole.USER);
        Long viewerId = createUser("list-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.TIP, PostCategory.COOKING, "제목입니다", "본문입니다");
        postRepository.saveAndFlush(post);

        mvc.perform(get("/api/community/posts")
                        .header("Authorization", bearer(viewerId))
                        .param("category", "COOKING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value("제목입니다"))
                .andExpect(jsonPath("$.data.content[0].postType").value("TIP"));
    }

    @Test
    @Transactional
    void FOOD_RECOMMEND_게시글_상세조회가_공유PK로_실제_DB에서_동작한다() throws Exception {
        Long authorId = createUser("food-author@example.com", UserRole.USER);
        Long viewerId = createUser("food-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.FOOD_RECOMMEND, PostCategory.FOOD_RECOMMEND, "맛집 추천", "여기 맛있어요");
        postRepository.saveAndFlush(post);

        FoodPostDetail detail = FoodPostDetail.create(post.getId(), "김치찌개", "우리집", 9000, 5, 4, 5);
        foodPostDetailRepository.saveAndFlush(detail);

        mvc.perform(get("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.foodDetail.menuName").value("김치찌개"))
                .andExpect(jsonPath("$.data.foodDetail.placeName").value("우리집"))
                .andExpect(jsonPath("$.data.recruitDetail").doesNotExist());
    }

    @Test
    @Transactional
    void RECRUIT_게시글_상세조회는_battle_필드만_채워지고_나머지는_기본값이다() throws Exception {
        Long authorId = createUser("recruit-author@example.com", UserRole.USER);
        Long viewerId = createUser("recruit-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.RECRUIT, PostCategory.RECRUIT, "같이 챌린지해요", "모집합니다");
        postRepository.saveAndFlush(post);

        RecruitPostDetail detail = RecruitPostDetail.create(post.getId(), 999L, "https://hampouch.com/battle/999");
        recruitPostDetailRepository.saveAndFlush(detail);

        mvc.perform(get("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recruitDetail.battleId").value(999))
                .andExpect(jsonPath("$.data.recruitDetail.battleUrl").value("https://hampouch.com/battle/999"))
                .andExpect(jsonPath("$.data.recruitDetail.durationDays").value(0))
                .andExpect(jsonPath("$.data.recruitDetail.recruit").value(false))
                .andExpect(jsonPath("$.data.foodDetail").doesNotExist());
    }

    // 회원 탈퇴는 row를 삭제하지 않고 status만 DELETED로 바꾸는 soft delete이므로,
    // 실제로 User.delete()를 호출해 DB에 반영한 뒤 조회해야 마스킹 로직이 정확히 검증된다.
    @Test
    @Transactional
    void 상세조회_탈퇴한_작성자는_실제_DB에서도_탈퇴한_사용자로_마스킹된다() throws Exception {
        Long authorId = createUser("will-delete@example.com", UserRole.USER);
        Long viewerId = createUser("masking-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.TIP, PostCategory.ETC, "제목", "내용");
        postRepository.saveAndFlush(post);

        User author = userRepository.findById(authorId).orElseThrow();
        author.delete();
        userRepository.saveAndFlush(author);

        mvc.perform(get("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.author.authorName").value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.data.author.profileImageUrl").doesNotExist());
    }

    @Test
    @Transactional
    void 게시글_썸네일이_목록조회에서_정렬순서대로_첫번째_이미지로_내려간다() throws Exception {
        Long authorId = createUser("thumb-author@example.com", UserRole.USER);
        Long viewerId = createUser("thumb-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.TIP, PostCategory.DISCOUNT, "썸네일 테스트", "내용");
        postRepository.saveAndFlush(post);

        postImageRepository.saveAndFlush(PostImage.create(post.getId(), "https://s3/second.jpg", "key-2", 1));
        postImageRepository.saveAndFlush(PostImage.create(post.getId(), "https://s3/first.jpg", "key-1", 0));

        mvc.perform(get("/api/community/posts")
                        .header("Authorization", bearer(viewerId))
                        .param("category", "DISCOUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value("https://s3/first.jpg"));
    }

    // Slice 전환으로 COUNT 쿼리 없이 hasNext가 정확히 계산되는지 확인.
    // size=2로 3건 중 2건만 조회되면 hasNext=true, 남은 1건을 조회하면 hasNext=false여야 한다.
    @Test
    @Transactional
    void 목록조회는_COUNT쿼리_없이_hasNext가_정확하다() throws Exception {
        Long authorId = createUser("slice-author@example.com", UserRole.USER);
        Long viewerId = createUser("slice-viewer@example.com", UserRole.USER);

        for (int i = 0; i < 3; i++) {
            postRepository.saveAndFlush(Post.create(authorId, PostType.TIP, PostCategory.RECORD, "글" + i, "내용"));
        }

        mvc.perform(get("/api/community/posts")
                        .header("Authorization", bearer(viewerId))
                        .param("category", "RECORD")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mvc.perform(get("/api/community/posts")
                        .header("Authorization", bearer(viewerId))
                        .param("category", "RECORD")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @Transactional
    void page가_음수면_400을_반환한다() throws Exception {
        Long viewerId = createUser("neg-page@example.com", UserRole.USER);

        mvc.perform(get("/api/community/posts")
                        .header("Authorization", bearer(viewerId))
                        .param("category", "ETC")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @Transactional
    void size가_0이면_400을_반환한다() throws Exception {
        Long viewerId = createUser("zero-size@example.com", UserRole.USER);

        mvc.perform(get("/api/community/posts")
                        .header("Authorization", bearer(viewerId))
                        .param("category", "ETC")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // 이전에는 findByUserIdIn... 리포지토리 메서드명에 "Top"이 들어가 있어 Spring Data가
    // 암묵적으로 Top1로 해석했다. Pageable로 5개를 요청해도 항상 1건만 반환되는 버그였는데,
    // 관리자 게시글을 5개 이상 만들어 실제로 5개가 반환되는지로 이 문제가 해결됐는지 증명한다.
    @Test
    @Transactional
    void 포치픽은_관리자_게시글_5개_이상이어도_홈에_5개까지_반환된다() throws Exception {
        Long adminId = createUser("home-admin@example.com", UserRole.ADMIN);
        Long viewerId = createUser("home-viewer@example.com", UserRole.USER);

        for (int i = 0; i < 7; i++) {
            postRepository.saveAndFlush(Post.create(adminId, PostType.TIP, PostCategory.ETC, "관리자글" + i, "내용"));
        }

        mvc.perform(get("/api/community/home")
                        .header("Authorization", bearer(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pochiPicks.length()").value(5));
    }

    // 최상위 댓글이 무제한으로 한 응답에 담기지 않고 실제로 페이지네이션되는지 확인.
    // 댓글 25개를 저장하고 commentSize=10으로 조회하면 1페이지에 10개만, hasNext=true여야 하고,
    // 마지막 페이지(3페이지, 0-indexed로 2)는 5개만 오고 hasNext=false여야 한다.
    @Test
    @Transactional
    void 상세조회는_최상위댓글을_실제로_페이지네이션한다() throws Exception {
        Long authorId = createUser("comment-page-author@example.com", UserRole.USER);
        Long commenterId = createUser("comment-page-commenter@example.com", UserRole.USER);
        Long viewerId = createUser("comment-page-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.TIP, PostCategory.ETC, "댓글페이지네이션테스트", "내용");
        postRepository.saveAndFlush(post);

        for (int i = 0; i < 25; i++) {
            postCommentRepository.saveAndFlush(PostComment.create(post.getId(), commenterId, null, "댓글" + i));
        }

        // 1페이지: 10개, hasNext=true
        mvc.perform(get("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(viewerId))
                        .param("commentPage", "0")
                        .param("commentSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comments.content.length()").value(10))
                .andExpect(jsonPath("$.data.comments.hasNext").value(true));

        // 마지막 페이지(2, 0-indexed): 남은 5개, hasNext=false
        mvc.perform(get("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(viewerId))
                        .param("commentPage", "2")
                        .param("commentSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comments.content.length()").value(5))
                .andExpect(jsonPath("$.data.comments.hasNext").value(false));
    }

    @Test
    @Transactional
    void 상세조회_commentPage가_음수면_400을_반환한다() throws Exception {
        Long authorId = createUser("neg-comment-page-author@example.com", UserRole.USER);
        Long viewerId = createUser("neg-comment-page-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.TIP, PostCategory.ETC, "제목", "내용");
        postRepository.saveAndFlush(post);

        mvc.perform(get("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(viewerId))
                        .param("commentPage", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // 조회수 증가(increaseViewCount)는 @Modifying 벌크 UPDATE라 원자적 연산이어야 하는데,
    // 이게 실제로 동시 요청 상황에서 유실 없이 정확히 누적되는지는 H2로는 정확히 검증되지 않는다.
    // 실 MySQL 위에서 여러 요청을 동시에 겹쳐 실행해 최종 view_count가 요청 수와
    // 정확히 일치하는지 확인한다. (이 테스트는 @Transactional을 걸지 않는다 -
    // 각 요청이 별도 트랜잭션/커넥션으로 실행되어야 실제 동시성이 재현되기 때문)
    @Test
    void 동시에_여러번_상세조회하면_조회수가_요청수만큼_정확히_증가한다() throws Exception {
        Long authorId = createUser("view-race-author@example.com", UserRole.USER);
        Post post = Post.create(authorId, PostType.TIP, PostCategory.ETC, "동시조회테스트", "내용");
        postRepository.saveAndFlush(post);

        int requestCount = 10;
        List<Long> viewerIds = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            viewerIds.add(createUser("view-race-viewer" + i + "@example.com", UserRole.USER));
        }

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (Long viewerId : viewerIds) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    MvcResult result = mvc.perform(get("/api/community/posts/" + post.getId())
                                    .header("Authorization", bearer(viewerId)))
                            .andReturn();
                    statusCodes.add(result.getResponse().getStatus());
                } catch (Exception e) {
                    statusCodes.add(-1);
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(15, TimeUnit.SECONDS);

        assertThat(statusCodes)
                .as("모든 요청이 200이어야 한다. 실제 응답: %s", statusCodes)
                .allMatch(status -> status == 200);

        Post updated = postRepository.findById(post.getId()).orElseThrow();
        assertThat(updated.getViewCount())
                .as("조회수는 요청 수만큼 유실 없이 정확히 증가해야 한다")
                .isEqualTo(requestCount);
    }
}