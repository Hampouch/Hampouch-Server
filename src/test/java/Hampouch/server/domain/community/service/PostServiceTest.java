package Hampouch.server.domain.community.service;

import Hampouch.server.domain.community.dto.request.PostListQuery;
import Hampouch.server.domain.community.dto.response.*;
import Hampouch.server.domain.community.entity.*;
import Hampouch.server.domain.community.repository.*;
import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.CommunityErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    PostRepository postRepository;
    @Mock
    PostImageRepository postImageRepository;
    @Mock
    PostLikeRepository postLikeRepository;
    @Mock
    PostBookmarkRepository postBookmarkRepository;
    @Mock
    PostCommentRepository postCommentRepository;
    @Mock
    FoodPostDetailRepository foodPostDetailRepository;
    @Mock
    RecruitPostDetailRepository recruitPostDetailRepository;
    @Mock
    UserRepository userRepository;

    PostService postService;

    @BeforeEach
    void setUp() {
        postService = new PostService(
                postRepository, postImageRepository, postLikeRepository, postBookmarkRepository,
                postCommentRepository, foodPostDetailRepository, recruitPostDetailRepository, userRepository
        );
    }

    private Post post(Long id, Long userId, PostType type, PostCategory category) {
        Post post = Post.create(userId, type, category, "제목-" + id, "내용-" + id);
        setField(post, "id", id);
        return post;
    }

    private User user(Long id, String nickname, UserRole role) {
        User user = User.createSocialUser("user" + id + "@example.com", AuthProvider.GOOGLE, "provider-" + id);
        setField(user, "id", id);
        setField(user, "nickname", nickname);
        setField(user, "role", role);
        return user;
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

    // ========== 1. getHome ==========

    @Test
    void 홈조회_인기글_포치픽_전체목록을_한번에_반환한다() {
        Post popular = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        Post pochiPick = post(2L, 20L, PostType.TIP, PostCategory.ETC);
        User admin = user(20L, "관리자", UserRole.ADMIN);

        when(postRepository.findTopPopularPosts(eq(10), any())).thenReturn(List.of(popular));
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin));
        when(postRepository.findTopByUserIdInOrderByCreatedAtDesc(eq(List.of(20L)), any()))
                .thenReturn(List.of(pochiPick));

        Page<Post> allPage = new PageImpl<>(List.of(popular, pochiPick), PageRequest.of(0, 20), 2);
        when(postRepository.findAll(any(Pageable.class))).thenReturn(allPage);

        stubEmptyN1Lookups();

        HomeResponse response = postService.getHome(99L, PostListQuery.of("LATEST", 0, 20));

        assertThat(response.popularPosts()).hasSize(1);
        assertThat(response.pochiPicks()).hasSize(1);
        assertThat(response.posts().content()).hasSize(2);
    }

    @Test
    void 홈조회_관리자가_없으면_포치픽은_빈_목록이다() {
        when(postRepository.findTopPopularPosts(eq(10), any())).thenReturn(List.of());
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of());
        when(postRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        HomeResponse response = postService.getHome(99L, PostListQuery.of("LATEST", 0, 20));

        assertThat(response.pochiPicks()).isEmpty();
        verify(postRepository, never()).findTopByUserIdInOrderByCreatedAtDesc(any(), any());
    }

    // ========== 2. getPopularPosts ==========

    @Test
    void 인기글_전체보기는_좋아요_10개이상_기준으로_조회한다() {
        Post popular = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        Page<Post> page = new PageImpl<>(List.of(popular), PageRequest.of(0, 20), 1);
        when(postRepository.findPopularPosts(eq(10), any())).thenReturn(page);
        stubEmptyN1Lookups();

        PageResponse<PostListResponse> response = postService.getPopularPosts(99L, PostListQuery.of("LATEST", 0, 20));

        assertThat(response.content()).hasSize(1);
        verify(postRepository).findPopularPosts(eq(10), any());
    }

    // ========== 3. getPochiPicks ==========

    @Test
    void 포치픽_전체보기_관리자가_없으면_빈_페이지를_반환한다() {
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of());

        PageResponse<PostListResponse> response = postService.getPochiPicks(99L, PostListQuery.of("LATEST", 0, 20));

        assertThat(response.content()).isEmpty();
        verify(postRepository, never()).findByUserIdIn(any(), any());
    }

    @Test
    void 포치픽_전체보기_관리자가_있으면_관리자글만_조회한다() {
        User admin = user(20L, "관리자", UserRole.ADMIN);
        Post pochiPick = post(1L, 20L, PostType.TIP, PostCategory.ETC);

        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin));
        when(postRepository.findByUserIdIn(eq(List.of(20L)), any()))
                .thenReturn(new PageImpl<>(List.of(pochiPick), PageRequest.of(0, 20), 1));
        stubEmptyN1Lookups();

        PageResponse<PostListResponse> response = postService.getPochiPicks(99L, PostListQuery.of("LATEST", 0, 20));

        assertThat(response.content()).hasSize(1);
    }

    // ========== 4. getPostsByCategory ==========

    @Test
    void 카테고리별_목록조회_잘못된_카테고리면_예외() {
        assertThatThrownBy(() -> postService.getPostsByCategory(99L, "INVALID_CATEGORY", PostListQuery.of("LATEST", 0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_INVALID_POST_CATEGORY);
    }

    @Test
    void 카테고리별_목록조회_정상흐름() {
        Post cookingPost = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        when(postRepository.findByCategory(eq(PostCategory.COOKING), any()))
                .thenReturn(new PageImpl<>(List.of(cookingPost), PageRequest.of(0, 20), 1));
        stubEmptyN1Lookups();

        PageResponse<PostListResponse> response = postService.getPostsByCategory(99L, "COOKING", PostListQuery.of("LATEST", 0, 20));

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).category()).isEqualTo("COOKING");
    }

    @Test
    void 정렬타입이_잘못되면_예외() {
        assertThatThrownBy(() -> postService.getPostsByCategory(99L, "COOKING", PostListQuery.of("INVALID_SORT", 0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_INVALID_SORT_TYPE);
    }

    // ========== 5. getPostDetail ==========

    @Test
    void 상세조회_존재하지않는_게시글이면_예외() {
        when(postRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.getPostDetail(99L, 1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_POST_NOT_FOUND);
    }

    @Test
    void 상세조회_TIP_게시글이면_food_recruit_상세가_둘다_null이다() {
        Post tipPost = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        User author = user(10L, "작성자", UserRole.USER);

        when(postRepository.findById(1L)).thenReturn(Optional.of(tipPost));
        when(userRepository.findById(10L)).thenReturn(Optional.of(author));
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postCommentRepository.findByPostIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        PostDetailResponse response = postService.getPostDetail(99L, 1L);

        assertThat(response.foodDetail()).isNull();
        assertThat(response.recruitDetail()).isNull();
        verify(postRepository).increaseViewCount(1L);
        verifyNoInteractions(foodPostDetailRepository, recruitPostDetailRepository);
    }

    @Test
    void 상세조회_FOOD_RECOMMEND_게시글이면_foodDetail만_채워진다() {
        Post foodPost = post(1L, 10L, PostType.FOOD_RECOMMEND, PostCategory.FOOD_RECOMMEND);
        User author = user(10L, "작성자", UserRole.USER);
        FoodPostDetail foodDetail = FoodPostDetail.create(1L, "김치찌개", "우리집", 9000, 5, 4, 5);

        when(postRepository.findById(1L)).thenReturn(Optional.of(foodPost));
        when(userRepository.findById(10L)).thenReturn(Optional.of(author));
        when(foodPostDetailRepository.findByPostId(1L)).thenReturn(Optional.of(foodDetail));
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postCommentRepository.findByPostIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        PostDetailResponse response = postService.getPostDetail(99L, 1L);

        assertThat(response.foodDetail()).isNotNull();
        assertThat(response.foodDetail().menuName()).isEqualTo("김치찌개");
        assertThat(response.recruitDetail()).isNull();
    }

    @Test
    void 상세조회_RECRUIT_게시글이면_recruitDetail만_채워지고_battle관련_필드는_기본값이다() {
        Post recruitPost = post(1L, 10L, PostType.RECRUIT, PostCategory.RECRUIT);
        User author = user(10L, "작성자", UserRole.USER);
        RecruitPostDetail recruitDetail = RecruitPostDetail.create(1L, 999L, "https://hampouch.com/battle/999");

        when(postRepository.findById(1L)).thenReturn(Optional.of(recruitPost));
        when(userRepository.findById(10L)).thenReturn(Optional.of(author));
        when(recruitPostDetailRepository.findByPostId(1L)).thenReturn(Optional.of(recruitDetail));
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postCommentRepository.findByPostIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        PostDetailResponse response = postService.getPostDetail(99L, 1L);

        assertThat(response.recruitDetail()).isNotNull();
        assertThat(response.recruitDetail().battleId()).isEqualTo(999L);
        assertThat(response.recruitDetail().battleUrl()).isEqualTo("https://hampouch.com/battle/999");
        // Battle 도메인 연동 전이라 하드코딩된 기본값(null/0/false)이어야 함
        assertThat(response.recruitDetail().battleTitle()).isNull();
        assertThat(response.recruitDetail().durationDays()).isZero();
        assertThat(response.recruitDetail().recruit()).isFalse();
        assertThat(response.foodDetail()).isNull();
    }

    @Test
    void 상세조회_작성자가_탈퇴했으면_탈퇴한_회원으로_표시된다() {
        Post tipPost = post(1L, 10L, PostType.TIP, PostCategory.COOKING);

        when(postRepository.findById(1L)).thenReturn(Optional.of(tipPost));
        when(userRepository.findById(10L)).thenReturn(Optional.empty()); // 작성자 조회 안 됨(탈퇴 등)
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postCommentRepository.findByPostIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        PostDetailResponse response = postService.getPostDetail(99L, 1L);

        assertThat(response.author().authorName()).isEqualTo("탈퇴한 사용자");
    }

    @Test
    void 상세조회_댓글이_대댓글과_트리구조로_조립된다() {
        Post tipPost = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        User author = user(10L, "작성자", UserRole.USER);
        User commenter = user(20L, "댓글러", UserRole.USER);

        PostComment parentComment = PostComment.create(1L, 20L, null, "부모 댓글");
        setField(parentComment, "id", 100L);
        PostComment replyComment = PostComment.create(1L, 20L, 100L, "대댓글");
        setField(replyComment, "id", 101L);

        when(postRepository.findById(1L)).thenReturn(Optional.of(tipPost));
        when(userRepository.findById(10L)).thenReturn(Optional.of(author));
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postCommentRepository.findByPostIdOrderByCreatedAtAsc(1L))
                .thenReturn(List.of(parentComment, replyComment));
        when(userRepository.findAllById(List.of(20L))).thenReturn(List.of(commenter));

        PostDetailResponse response = postService.getPostDetail(99L, 1L);

        assertThat(response.comments()).hasSize(1); // 최상위 댓글 1개만
        assertThat(response.comments().get(0).content()).isEqualTo("부모 댓글");
        assertThat(response.comments().get(0).replies()).hasSize(1);
        assertThat(response.comments().get(0).replies().get(0).content()).isEqualTo("대댓글");
    }

    @Test
    void 상세조회_삭제된_댓글도_content가_원본그대로_내려간다() {
        Post tipPost = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        User author = user(10L, "작성자", UserRole.USER);
        User commenter = user(20L, "댓글러", UserRole.USER);

        PostComment deletedComment = PostComment.create(1L, 20L, null, "삭제될 내용");
        setField(deletedComment, "id", 100L);
        deletedComment.delete();

        when(postRepository.findById(1L)).thenReturn(Optional.of(tipPost));
        when(userRepository.findById(10L)).thenReturn(Optional.of(author));
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postCommentRepository.findByPostIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(deletedComment));
        when(userRepository.findAllById(List.of(20L))).thenReturn(List.of(commenter));

        PostDetailResponse response = postService.getPostDetail(99L, 1L);

        assertThat(response.comments().get(0).content()).isEqualTo("삭제될 내용");
        assertThat(response.comments().get(0).isDeleted()).isTrue();
    }

    // ========== 공통 헬퍼 ==========

    private void stubEmptyN1Lookups() {
        lenient().when(postImageRepository.findByPostIdInOrderByPostIdAscSortOrderAsc(anyList())).thenReturn(List.of());
        lenient().when(userRepository.findAllById(anyList())).thenReturn(List.of());
        lenient().when(postLikeRepository.findByPostIdInAndUserId(anyList(), any())).thenReturn(List.of());
        lenient().when(postBookmarkRepository.findByPostIdInAndUserId(anyList(), any())).thenReturn(List.of());
    }
}