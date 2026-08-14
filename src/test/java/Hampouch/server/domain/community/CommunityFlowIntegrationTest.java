package Hampouch.server.domain.community;

import Hampouch.server.domain.community.entity.*;
import Hampouch.server.domain.community.repository.*;
import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.global.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 커뮤니티 조회 API가 실제 스프링 컨텍스트 + 실제 DB(마이그레이션 V4로 만든 스키마)를
 * 거쳐 정상 동작하는지 검증한다.
 * 아직 작성(create) API가 없으므로, 리포지토리로 직접 데이터를 저장해두고
 * 조회 API만 호출하는 방식으로 검증한다.
 * 특히 food_post_detail/recruit_post_detail이 AUTO_INCREMENT 없이 Post의 id를
 * 그대로 공유하는 PK 구조라, 이 저장/조회가 실제로 동작하는지가 핵심 검증 포인트다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
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

    private String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId, UserRole.USER);
    }

    private Long createUser(String email, UserRole role) {
        User user = User.createSocialUser(email, AuthProvider.GOOGLE, "provider-id-" + email);
        setField(user, "nickname", "테스터-" + email.hashCode());
        setField(user, "role", role);
        userRepository.save(user);
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
    void 게시글_목록_조회가_실제_DB로_동작한다() throws Exception {
        Long authorId = createUser("list-author@example.com", UserRole.USER);
        Long viewerId = createUser("list-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.TIP, PostCategory.COOKING, "제목입니다", "본문입니다");
        postRepository.save(post);

        mvc.perform(get("/api/community/posts")
                        .header("Authorization", bearer(viewerId))
                        .param("category", "COOKING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value("제목입니다"))
                .andExpect(jsonPath("$.data.content[0].postType").value("TIP"));
    }

    @Test
    void FOOD_RECOMMEND_게시글_상세조회가_공유PK로_실제_DB에서_동작한다() throws Exception {
        Long authorId = createUser("food-author@example.com", UserRole.USER);
        Long viewerId = createUser("food-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.FOOD_RECOMMEND, PostCategory.FOOD_RECOMMEND, "맛집 추천", "여기 맛있어요");
        postRepository.saveAndFlush(post);

        // food_post_detail은 AUTO_INCREMENT 없이 post.getId()를 그대로 PK로 사용 -
        // 이 저장이 실패 없이 되는지가 핵심 검증 대상. saveAndFlush로 즉시 반영해야
        // 뒤이은 API 호출에서 findByPostId가 이 row를 확실히 찾을 수 있다.
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
    void RECRUIT_게시글_상세조회는_battle_필드만_채워지고_나머지는_기본값이다() throws Exception {
        Long authorId = createUser("recruit-author@example.com", UserRole.USER);
        Long viewerId = createUser("recruit-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.RECRUIT, PostCategory.RECRUIT, "같이 챌린지해요", "모집합니다");
        postRepository.saveAndFlush(post);

        RecruitPostDetail detail = RecruitPostDetail.create(post.getId(), 999L, "https://hampouch.com/battle/999");
        recruitPostDetailRepository.saveAndFlush(detail);

        // RecruitDetailResponse는 record라 값이 null이어도 필드 자체는 JSON에 존재한다
        // (키가 사라지는 게 아니라 "battleTitle": null 형태로 내려감).
        mvc.perform(get("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recruitDetail.battleId").value(999))
                .andExpect(jsonPath("$.data.recruitDetail.battleUrl").value("https://hampouch.com/battle/999"))
                // Battle 도메인 연동 전이라 나머지는 RecruitDetailResponse.of(...)에 하드코딩된
                // 기본값(null, null, 0, 0, 0, null, false) 그대로 내려가야 함
                .andExpect(jsonPath("$.data.recruitDetail.battleTitle").doesNotExist())
                .andExpect(jsonPath("$.data.recruitDetail.startDate").doesNotExist())
                .andExpect(jsonPath("$.data.recruitDetail.durationDays").value(0))
                .andExpect(jsonPath("$.data.recruitDetail.maxMemberCount").value(0))
                .andExpect(jsonPath("$.data.recruitDetail.currentMemberCount").value(0))
                .andExpect(jsonPath("$.data.recruitDetail.penalty").doesNotExist())
                .andExpect(jsonPath("$.data.recruitDetail.recruit").value(false))
                .andExpect(jsonPath("$.data.foodDetail").doesNotExist());
    }

    @Test
    void 상세조회하면_조회수가_실제_DB에서_증가한다() throws Exception {
        Long authorId = createUser("view-author@example.com", UserRole.USER);
        Long viewerId = createUser("view-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.TIP, PostCategory.ETC, "조회수 테스트", "내용");
        postRepository.saveAndFlush(post);

        mvc.perform(get("/api/community/posts/" + post.getId())
                        .header("Authorization", bearer(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewCount").value(1));

        // 벌크 업데이트(increaseViewCount)가 실제로 DB에 반영됐는지 재조회로 확인
        Post updated = postRepository.findById(post.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(updated.getViewCount()).isEqualTo(1);
    }

    @Test
    void 게시글_썸네일이_목록조회에서_정렬순서대로_첫번째_이미지로_내려간다() throws Exception {
        Long authorId = createUser("thumb-author@example.com", UserRole.USER);
        Long viewerId = createUser("thumb-viewer@example.com", UserRole.USER);

        Post post = Post.create(authorId, PostType.TIP, PostCategory.DISCOUNT, "썸네일 테스트", "내용");
        postRepository.saveAndFlush(post);

        postImageRepository.save(PostImage.create(post.getId(), "https://s3/second.jpg", "key-2", 1));
        postImageRepository.save(PostImage.create(post.getId(), "https://s3/first.jpg", "key-1", 0));

        mvc.perform(get("/api/community/posts")
                        .header("Authorization", bearer(viewerId))
                        .param("category", "DISCOUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value("https://s3/first.jpg"));
    }
}