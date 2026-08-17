package Hampouch.server.domain.community;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.community.entity.*;
import Hampouch.server.domain.community.repository.*;
import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.jwt.JwtProvider;
import Hampouch.server.global.mysql.MySqlContainerTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Autowired
    BattleRepository battleRepository;

    @Autowired
    BattleParticipantRepository battleParticipantRepository;

    @Autowired
    PostLikeRepository postLikeRepository;

    @Autowired
    PostBookmarkRepository postBookmarkRepository;

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
    void RECRUIT_게시글_상세조회는_실제_배틀정보를_반환한다() throws Exception {
        Long authorId = createUser(
                "recruit-author@example.com",
                UserRole.USER
        );
        Long viewerId = createUser(
                "recruit-viewer@example.com",
                UserRole.USER
        );

        User author = userRepository.findById(authorId)
                .orElseThrow();

        Battle battle = Battle.of(
                "COMMUNITY-BATTLE-001",
                "일주일 절약 배틀",
                5,
                7,
                LocalDate.of(2026, 8, 20),
                "꼴찌가 커피 사기",
                author
        );
        battleRepository.saveAndFlush(battle);

        BattleParticipant participant =
                BattleParticipant.of(author, battle);
        battleParticipantRepository.saveAndFlush(participant);

        Post post = Post.create(
                authorId,
                PostType.RECRUIT,
                PostCategory.RECRUIT,
                "같이 챌린지해요",
                "모집합니다"
        );
        postRepository.saveAndFlush(post);

        String battleUrl =
                "https://hampouch.com/battle/" + battle.getId();

        RecruitPostDetail detail = RecruitPostDetail.create(
                post.getId(),
                battle.getId(),
                battleUrl
        );
        recruitPostDetailRepository.saveAndFlush(detail);

        mvc.perform(get("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recruitDetail.battleId")
                        .value(battle.getId()))
                .andExpect(jsonPath("$.data.recruitDetail.battleUrl")
                        .value(battleUrl))
                .andExpect(jsonPath("$.data.recruitDetail.battleTitle")
                        .value("일주일 절약 배틀"))
                .andExpect(jsonPath("$.data.recruitDetail.startDate")
                        .value("2026-08-20"))
                .andExpect(jsonPath("$.data.recruitDetail.durationDays")
                        .value(7))
                .andExpect(jsonPath("$.data.recruitDetail.maxMemberCount")
                        .value(5))
                .andExpect(jsonPath("$.data.recruitDetail.currentMemberCount")
                        .value(1))
                .andExpect(jsonPath("$.data.recruitDetail.penalty")
                        .value("꼴찌가 커피 사기"))
                .andExpect(jsonPath("$.data.recruitDetail.recruit")
                        .value(true))
                .andExpect(jsonPath("$.data.foodDetail")
                        .doesNotExist());
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

    @Test
    @Transactional
    void 목록조회_탈퇴한_작성자는_탈퇴한_사용자로_마스킹된다() throws Exception {
        Long authorId = createUser(
                "deleted-list-author@example.com",
                UserRole.USER
        );
        Long viewerId = createUser(
                "deleted-list-viewer@example.com",
                UserRole.USER
        );

        Post post = Post.create(
                authorId,
                PostType.TIP,
                PostCategory.ETC,
                "탈퇴 사용자 게시글",
                "게시글 내용"
        );
        postRepository.saveAndFlush(post);

        User author = userRepository.findById(authorId)
                .orElseThrow();
        author.delete();
        userRepository.saveAndFlush(author);

        mvc.perform(get("/api/community/posts")
                        .header("Authorization", bearer(viewerId))
                        .param("category", "ETC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data.content[?(@.postId == " + post.getId() + ")].authorName"
                ).value(org.hamcrest.Matchers.contains("탈퇴한 사용자")));
    }

    @Test
    @Transactional
    void 상세조회_탈퇴한_댓글과_대댓글_작성자는_마스킹된다() throws Exception {
        Long postAuthorId = createUser(
                "comment-mask-post-author@example.com",
                UserRole.USER
        );
        Long commenterId = createUser(
                "comment-mask-commenter@example.com",
                UserRole.USER
        );
        Long viewerId = createUser(
                "comment-mask-viewer@example.com",
                UserRole.USER
        );

        Post post = Post.create(
                postAuthorId,
                PostType.TIP,
                PostCategory.ETC,
                "댓글 마스킹 테스트",
                "게시글 내용"
        );
        postRepository.saveAndFlush(post);

        PostComment parentComment = PostComment.create(
                post.getId(),
                commenterId,
                null,
                "최상위 댓글"
        );
        postCommentRepository.saveAndFlush(parentComment);

        PostComment reply = PostComment.create(
                post.getId(),
                commenterId,
                parentComment.getId(),
                "대댓글"
        );
        postCommentRepository.saveAndFlush(reply);

        User commenter = userRepository.findById(commenterId)
                .orElseThrow();
        commenter.delete();
        userRepository.saveAndFlush(commenter);

        mvc.perform(get("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comments.content.length()")
                        .value(1))
                .andExpect(jsonPath("$.data.comments.content[0].commentId")
                        .value(parentComment.getId()))
                .andExpect(jsonPath("$.data.comments.content[0].authorName")
                        .value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.data.comments.content[0].profileImageUrl")
                        .doesNotExist())
                .andExpect(jsonPath("$.data.comments.content[0].replies.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.data.comments.content[0].replyCount"
                ).value(1))
                .andExpect(jsonPath(
                        "$.data.comments.content[0].hasMoreReplies"
                ).value(false))
                .andExpect(jsonPath("$.data.comments.content[0].replies[0].commentId")
                        .value(reply.getId()))
                .andExpect(jsonPath("$.data.comments.content[0].replies[0].authorName")
                        .value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.data.comments.content[0].replies[0].profileImageUrl")
                        .doesNotExist());
    }

    @Test
    @Transactional
    void 같은_게시글에_동일한_sortOrder의_이미지를_중복저장할수없다() {
        Long authorId = createUser(
                "duplicate-image-author@example.com",
                UserRole.USER
        );

        Post post = Post.create(
                authorId,
                PostType.TIP,
                PostCategory.ETC,
                "이미지 순서 중복 테스트",
                "게시글 내용"
        );
        postRepository.saveAndFlush(post);

        PostImage firstImage = PostImage.create(
                post.getId(),
                "https://s3/first.jpg",
                "first-key",
                0
        );
        postImageRepository.saveAndFlush(firstImage);

        PostImage duplicateOrderImage = PostImage.create(
                post.getId(),
                "https://s3/duplicate.jpg",
                "duplicate-key",
                0
        );

        assertThatThrownBy(
                () -> postImageRepository.saveAndFlush(duplicateOrderImage)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void 꿀팁_게시글을_작성하고_수정한다() throws Exception {
        Long authorId = createUser("tip-mutation-author@example.com", UserRole.USER);

        MvcResult result = mvc.perform(post("/api/community/posts/tips")
                        .header("Authorization", bearer(authorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "COOKING",
                                  "title": "처음 제목",
                                  "content": "처음 내용",
                                  "imageKeys": [
                                    "community/posts/first.jpg",
                                    "community/posts/second.png"
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").isNumber())
                .andReturn();

        Long postId = ((Number) JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.postId"
        )).longValue();

        Post createdPost = postRepository.findById(postId).orElseThrow();
        assertThat(createdPost.getPostType()).isEqualTo(PostType.TIP);
        assertThat(createdPost.getCategory()).isEqualTo(PostCategory.COOKING);
        assertThat(createdPost.getTitle()).isEqualTo("처음 제목");
        assertThat(createdPost.getContent()).isEqualTo("처음 내용");

        List<PostImage> createdImages = postImageRepository.findByPostIdOrderBySortOrderAsc(postId);
        assertThat(createdImages).extracting(PostImage::getImageKey)
                .containsExactly("community/posts/first.jpg", "community/posts/second.png");
        assertThat(createdImages).extracting(PostImage::getSortOrder).containsExactly(0, 1);

        mvc.perform(patch("/api/community/posts/tips/" + postId)
                        .header("Authorization", bearer(authorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "DISCOUNT",
                                  "title": "수정된 제목",
                                  "content": "수정된 내용",
                                  "imageKeys": [
                                    "community/posts/second.png",
                                    "community/posts/new.webp"
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").value(postId));

        Post updatedPost = postRepository.findById(postId).orElseThrow();
        assertThat(updatedPost.getCategory()).isEqualTo(PostCategory.DISCOUNT);
        assertThat(updatedPost.getTitle()).isEqualTo("수정된 제목");
        assertThat(updatedPost.getContent()).isEqualTo("수정된 내용");

        List<PostImage> updatedImages = postImageRepository.findByPostIdOrderBySortOrderAsc(postId);
        assertThat(updatedImages).extracting(PostImage::getImageKey)
                .containsExactly("community/posts/second.png", "community/posts/new.webp");
        assertThat(updatedImages).extracting(PostImage::getSortOrder).containsExactly(0, 1);
    }

    @Test
    void 게시글상세_commentSize가_50을_초과하면_400을_반환한다()
            throws Exception {
        Long userId =
                createUser(
                        "comment-size-user@example.com",
                        UserRole.USER
                );

        Post post =
                postRepository.saveAndFlush(
                        Post.create(
                                userId,
                                PostType.TIP,
                                PostCategory.ETC,
                                "댓글 크기 검증",
                                "본문"
                        )
                );

        mvc.perform(get(
                        "/api/community/posts/" + post.getId()
                )
                        .header(
                                "Authorization",
                                bearer(userId)
                        )
                        .param("commentPage", "0")
                        .param("commentSize", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("VALIDATION_ERROR"));
    }

    @Test
    @Transactional
    void 뭐먹지_게시글과_상세정보를_작성하고_수정한다() throws Exception {
        Long authorId = createUser("food-mutation-author@example.com", UserRole.USER);

        MvcResult result = mvc.perform(post("/api/community/posts/foods")
                        .header("Authorization", bearer(authorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "마라탕 추천",
                              "menuName": "마라탕",
                              "placeName": "홍대 마라공방",
                              "price": 9000,
                              "tasteRating": 5,
                              "costRating": 4,
                              "moodRating": 5,
                              "content": "맛있어요",
                              "imageKeys": []
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").isNumber())
                .andReturn();

        Long postId = ((Number) JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.postId"
        )).longValue();

        FoodPostDetail createdDetail = foodPostDetailRepository.findByPostId(postId).orElseThrow();
        assertThat(createdDetail.getMenu()).isEqualTo("마라탕");
        assertThat(createdDetail.getPlace()).isEqualTo("홍대 마라공방");
        assertThat(createdDetail.getPrice()).isEqualTo(9000);
        assertThat(createdDetail.getTasteRating()).isEqualTo(5);
        assertThat(createdDetail.getCostRating()).isEqualTo(4);
        assertThat(createdDetail.getMoodRating()).isEqualTo(5);

        mvc.perform(patch("/api/community/posts/foods/" + postId)
                        .header("Authorization", bearer(authorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "수정된 마라탕 추천",
                              "menuName": "마라샹궈",
                              "placeName": "합정 마라공방",
                              "price": 12000,
                              "tasteRating": 4,
                              "costRating": 3,
                              "moodRating": 5,
                              "content": "수정된 내용",
                              "imageKeys": []
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").value(postId));

        Post updatedPost = postRepository.findById(postId).orElseThrow();
        FoodPostDetail updatedDetail = foodPostDetailRepository.findByPostId(postId).orElseThrow();
        assertThat(updatedPost.getTitle()).isEqualTo("수정된 마라탕 추천");
        assertThat(updatedPost.getContent()).isEqualTo("수정된 내용");
        assertThat(updatedDetail.getMenu()).isEqualTo("마라샹궈");
        assertThat(updatedDetail.getPlace()).isEqualTo("합정 마라공방");
        assertThat(updatedDetail.getPrice()).isEqualTo(12000);
        assertThat(updatedDetail.getTasteRating()).isEqualTo(4);
        assertThat(updatedDetail.getCostRating()).isEqualTo(3);
        assertThat(updatedDetail.getMoodRating()).isEqualTo(5);
    }

    @Test
    @Transactional
    void 모집_게시글과_배틀연결을_작성하고_수정한다() throws Exception {
        Long authorId = createUser("recruit-mutation-author@example.com", UserRole.USER);
        User author = userRepository.findById(authorId).orElseThrow();

        Battle firstBattle = Battle.of(
                "COMMUNITY-OLD", "첫 번째 배틀", 5, 7,
                java.time.LocalDate.of(2026, 8, 20), "커피 사기", author
        );
        Battle secondBattle = Battle.of(
                "COMMUNITY-NEW", "두 번째 배틀", 6, 14,
                java.time.LocalDate.of(2026, 9, 1), "점심 사기", author
        );
        battleRepository.saveAndFlush(firstBattle);
        battleRepository.saveAndFlush(secondBattle);

        MvcResult result = mvc.perform(post("/api/community/posts/recruits")
                        .header("Authorization", bearer(authorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "같이 절약해요",
                              "content": "3명 모집합니다",
                              "battleUrl": "https://hampouch.app/api/battles/invitations/COMMUNITY-OLD"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").isNumber())
                .andReturn();

        Long postId = ((Number) JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.postId"
        )).longValue();

        RecruitPostDetail createdDetail = recruitPostDetailRepository.findByPostId(postId).orElseThrow();
        assertThat(createdDetail.getBattleId()).isEqualTo(firstBattle.getId());
        assertThat(createdDetail.getBattleUrl())
                .isEqualTo("https://hampouch.app/api/battles/invitations/COMMUNITY-OLD");

        mvc.perform(patch("/api/community/posts/recruits/" + postId)
                        .header("Authorization", bearer(authorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "수정된 모집글",
                              "content": "2명 모집합니다",
                              "battleUrl": "https://hampouch.app/api/battles/invitations/COMMUNITY-NEW"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").value(postId));

        Post updatedPost = postRepository.findById(postId).orElseThrow();
        RecruitPostDetail updatedDetail = recruitPostDetailRepository.findByPostId(postId).orElseThrow();
        assertThat(updatedPost.getTitle()).isEqualTo("수정된 모집글");
        assertThat(updatedPost.getContent()).isEqualTo("2명 모집합니다");
        assertThat(updatedDetail.getBattleId()).isEqualTo(secondBattle.getId());
        assertThat(updatedDetail.getBattleUrl())
                .isEqualTo("https://hampouch.app/api/battles/invitations/COMMUNITY-NEW");
    }

    @Test
    @Transactional
    void 게시글을_삭제하면_연관데이터도_삭제된다() throws Exception {
        Long authorId = createUser("delete-post-author@example.com", UserRole.USER);
        Long otherUserId = createUser("delete-post-other@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.TIP, PostCategory.ETC, "삭제할 게시글", "삭제할 내용");
        postRepository.saveAndFlush(post);
        postImageRepository.saveAndFlush(PostImage.create(
                post.getId(), "https://s3/delete.jpg", "community/posts/delete.jpg", 0
        ));
        postLikeRepository.saveAndFlush(PostLike.create(post.getId(), otherUserId));
        postBookmarkRepository.saveAndFlush(PostBookmark.create(post.getId(), otherUserId));
        postCommentRepository.saveAndFlush(PostComment.create(
                post.getId(), otherUserId, null, "삭제될 댓글"
        ));

        mvc.perform(delete("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(authorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(postRepository.findById(post.getId())).isEmpty();
        assertThat(postImageRepository.findByPostIdOrderBySortOrderAsc(post.getId())).isEmpty();
        assertThat(postLikeRepository.findByPostIdAndUserId(post.getId(), otherUserId)).isEmpty();
        assertThat(postBookmarkRepository.findByPostIdAndUserId(post.getId(), otherUserId)).isEmpty();
        assertThat(postCommentRepository.findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(
                post.getId(), org.springframework.data.domain.PageRequest.of(0, 20)
        ).getContent()).isEmpty();
    }

    @Test
    @Transactional
    void 다른_사용자는_게시글을_삭제할수없다() throws Exception {
        Long authorId = createUser("delete-owner@example.com", UserRole.USER);
        Long otherUserId = createUser("delete-attacker@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.TIP, PostCategory.ETC, "삭제 권한 테스트", "내용");
        postRepository.saveAndFlush(post);

        mvc.perform(delete("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(otherUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_POST_AUTHOR"));

        assertThat(postRepository.findById(post.getId())).isPresent();
    }

    @Test
    @Transactional
    void 게시글상세_대댓글은_부모별_20개까지만_내리고_전체개수를_표시한다()
            throws Exception {
        Long authorId =
                createUser(
                        "reply-limit-author@example.com",
                        UserRole.USER
                );

        Long commenterId =
                createUser(
                        "reply-limit-commenter@example.com",
                        UserRole.USER
                );

        Post post =
                postRepository.saveAndFlush(
                        Post.create(
                                authorId,
                                PostType.TIP,
                                PostCategory.ETC,
                                "대댓글 상한 테스트",
                                "본문"
                        )
                );

        PostComment parentComment =
                postCommentRepository.saveAndFlush(
                        PostComment.create(
                                post.getId(),
                                commenterId,
                                null,
                                "부모 댓글"
                        )
                );

        for (int i = 0; i < 25; i++) {
            postCommentRepository.save(
                    PostComment.create(
                            post.getId(),
                            commenterId,
                            parentComment.getId(),
                            "대댓글 " + i
                    )
            );
        }

        postCommentRepository.flush();

        mvc.perform(get(
                        "/api/community/posts/" + post.getId()
                )
                        .header(
                                "Authorization",
                                bearer(authorId)
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data.comments.content[0].replies.length()"
                ).value(20))
                .andExpect(jsonPath(
                        "$.data.comments.content[0].replyCount"
                ).value(25))
                .andExpect(jsonPath(
                        "$.data.comments.content[0].hasMoreReplies"
                ).value(true));
    }
}
