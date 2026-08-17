package Hampouch.server.domain.community.service;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.community.dto.request.FoodPostRequest;
import Hampouch.server.domain.community.dto.request.PostListQuery;
import Hampouch.server.domain.community.dto.request.RecruitPostRequest;
import Hampouch.server.domain.community.dto.request.TipPostRequest;
import Hampouch.server.domain.community.dto.response.*;
import Hampouch.server.domain.community.entity.*;
import Hampouch.server.domain.community.event.CommunityImageDeleteEvent;
import Hampouch.server.domain.community.repository.*;
import Hampouch.server.domain.user.entity.AuthProvider;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import Hampouch.server.global.common.exception.domain.CommunityErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import java.lang.reflect.Field;
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
    BattleRepository battleRepository;
    @Mock
    BattleParticipantRepository battleParticipantRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    ImagePresignService imagePresignService;
    @Mock
    ApplicationEventPublisher eventPublisher;

    PostService postService;

    @BeforeEach
    void setUp() {
        postService = new PostService(postRepository, postImageRepository, postLikeRepository, postBookmarkRepository,
                postCommentRepository, foodPostDetailRepository, recruitPostDetailRepository, battleRepository,
                battleParticipantRepository, userRepository, imagePresignService, eventPublisher);
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
        when(postRepository.findByUserIdInOrderByCreatedAtDesc(eq(List.of(20L)), any()))
                .thenReturn(List.of(pochiPick));

        Slice<Post> allSlice = new SliceImpl<>(List.of(popular, pochiPick), PageRequest.of(0, 20), false);
        when(postRepository.findAllPosts(any(Pageable.class))).thenReturn(allSlice);

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
        when(postRepository.findAllPosts(any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        HomeResponse response = postService.getHome(99L, PostListQuery.of("LATEST", 0, 20));

        assertThat(response.pochiPicks()).isEmpty();
        verify(postRepository, never()).findByUserIdInOrderByCreatedAtDesc(any(), any());
    }

    // ========== 2. getPopularPosts ==========

    @Test
    void 인기글_전체보기는_좋아요_10개이상_기준으로_조회한다() {
        Post popular = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        Slice<Post> slice = new SliceImpl<>(List.of(popular), PageRequest.of(0, 20), false);
        when(postRepository.findPopularPosts(eq(10), any())).thenReturn(slice);
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
                .thenReturn(new SliceImpl<>(List.of(pochiPick), PageRequest.of(0, 20), false));
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
                .thenReturn(new SliceImpl<>(List.of(cookingPost), PageRequest.of(0, 20), false));
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

    @Test
    void 목록조회_탈퇴한_작성자는_탈퇴한_사용자로_표시한다() {
        Post post = post(
                1L,
                10L,
                PostType.TIP,
                PostCategory.COOKING
        );
        User deletedAuthor = user(
                10L,
                "삭제전닉네임",
                UserRole.USER
        );
        deletedAuthor.delete();

        Slice<Post> slice = new SliceImpl<>(
                List.of(post),
                PageRequest.of(0, 20),
                false
        );

        when(postRepository.findByCategory(
                eq(PostCategory.COOKING),
                any(Pageable.class)
        )).thenReturn(slice);

        when(postImageRepository.findFirstImagesByPostIdIn(List.of(1L)))
                .thenReturn(List.of());
        when(userRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(deletedAuthor));
        when(postLikeRepository.findByPostIdInAndUserId(List.of(1L), 99L))
                .thenReturn(List.of());
        when(postBookmarkRepository.findByPostIdInAndUserId(List.of(1L), 99L))
                .thenReturn(List.of());

        PageResponse<PostListResponse> response =
                postService.getPostsByCategory(
                        99L,
                        "COOKING",
                        PostListQuery.of("LATEST", 0, 20)
                );

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).authorName())
                .isEqualTo("탈퇴한 사용자");
    }

    @Test
    void 목록조회는_주정렬값이_같을때_id_내림차순으로_정렬한다() {
        when(postRepository.findByCategory(
                eq(PostCategory.COOKING),
                any(Pageable.class)
        )).thenReturn(new SliceImpl<>(
                List.of(),
                PageRequest.of(0, 20),
                false
        ));

        postService.getPostsByCategory(
                99L,
                "COOKING",
                PostListQuery.of("LATEST", 0, 20)
        );

        verify(postRepository).findByCategory(
                eq(PostCategory.COOKING),
                argThat(pageable -> {
                    org.springframework.data.domain.Sort.Order createdAtOrder =
                            pageable.getSort().getOrderFor("createdAt");
                    org.springframework.data.domain.Sort.Order idOrder =
                            pageable.getSort().getOrderFor("id");

                    return createdAtOrder != null
                            && createdAtOrder.isDescending()
                            && idOrder != null
                            && idOrder.isDescending();
                })
        );
    }

    // ========== 4-1. PostListQuery 검증 (page/size) ==========

    @Test
    void page가_음수면_예외() {
        assertThatThrownBy(() -> PostListQuery.of("LATEST", -1, 20))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_ERROR);
    }

    @Test
    void size가_0이면_예외() {
        assertThatThrownBy(() -> PostListQuery.of("LATEST", 0, 0))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_ERROR);
    }

    // ========== 5. getPostDetail ==========

    @Test
    void 상세조회_존재하지않는_게시글이면_예외() {
        when(postRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.getPostDetail(99L, 1L, 0, 20))
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
        stubEmptyCommentTree(1L);

        PostDetailResponse response = postService.getPostDetail(99L, 1L, 0, 20);

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
        stubEmptyCommentTree(1L);

        PostDetailResponse response = postService.getPostDetail(99L, 1L, 0, 20);

        assertThat(response.foodDetail()).isNotNull();
        assertThat(response.foodDetail().menuName()).isEqualTo("김치찌개");
        assertThat(response.recruitDetail()).isNull();
    }

    @Test
    void 상세조회_RECRUIT_게시글이면_실제_배틀정보를_반환한다() {
        Post recruitPost = post(
                1L,
                10L,
                PostType.RECRUIT,
                PostCategory.RECRUIT
        );
        User author = user(
                10L,
                "작성자",
                UserRole.USER
        );
        RecruitPostDetail recruitDetail = RecruitPostDetail.create(
                1L,
                999L,
                "https://hampouch.com/battle/999"
        );
        Battle battle = Battle.of(
                "BATTLE-CODE",
                "일주일 절약 배틀",
                5,
                7,
                java.time.LocalDate.of(2026, 8, 20),
                "커피 사기",
                author
        );
        setField(battle, "id", 999L);

        when(postRepository.findById(1L))
                .thenReturn(Optional.of(recruitPost));
        when(userRepository.findById(10L))
                .thenReturn(Optional.of(author));
        when(recruitPostDetailRepository.findByPostId(1L))
                .thenReturn(Optional.of(recruitDetail));
        when(battleRepository.findById(999L))
                .thenReturn(Optional.of(battle));
        when(battleParticipantRepository.countByBattle_Id(999L))
                .thenReturn(3);
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L))
                .thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L))
                .thenReturn(false);
        stubEmptyCommentTree(1L);

        PostDetailResponse response =
                postService.getPostDetail(99L, 1L, 0, 20);

        assertThat(response.foodDetail()).isNull();
        assertThat(response.recruitDetail()).isNotNull();
        assertThat(response.recruitDetail().battleId()).isEqualTo(999L);
        assertThat(response.recruitDetail().battleUrl())
                .isEqualTo("https://hampouch.com/battle/999");
        assertThat(response.recruitDetail().battleTitle())
                .isEqualTo("일주일 절약 배틀");
        assertThat(response.recruitDetail().startDate())
                .isEqualTo(java.time.LocalDate.of(2026, 8, 20));
        assertThat(response.recruitDetail().durationDays()).isEqualTo(7);
        assertThat(response.recruitDetail().maxMemberCount()).isEqualTo(5);
        assertThat(response.recruitDetail().currentMemberCount()).isEqualTo(3);
        assertThat(response.recruitDetail().penalty()).isEqualTo("커피 사기");
        assertThat(response.recruitDetail().recruit()).isTrue();
    }

    @Test
    void 상세조회_RECRUIT_게시글의_배틀이_없으면_예외가_발생한다() {
        Post recruitPost = post(
                1L,
                10L,
                PostType.RECRUIT,
                PostCategory.RECRUIT
        );
        User author = user(
                10L,
                "작성자",
                UserRole.USER
        );
        RecruitPostDetail recruitDetail = RecruitPostDetail.create(
                1L,
                999L,
                "https://hampouch.com/battle/999"
        );

        when(postRepository.findById(1L))
                .thenReturn(Optional.of(recruitPost));
        when(userRepository.findById(10L))
                .thenReturn(Optional.of(author));
        when(recruitPostDetailRepository.findByPostId(1L))
                .thenReturn(Optional.of(recruitDetail));
        when(battleRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> postService.getPostDetail(99L, 1L, 0, 20)
        )
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_BATTLE_NOT_FOUND);
    }

    @Test
    void 상세조회_작성자_row가_없으면_탈퇴한_사용자로_표시된다() {
        Post tipPost = post(1L, 10L, PostType.TIP, PostCategory.COOKING);

        when(postRepository.findById(1L)).thenReturn(Optional.of(tipPost));
        when(userRepository.findById(10L)).thenReturn(Optional.empty());
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        stubEmptyCommentTree(1L);

        PostDetailResponse response = postService.getPostDetail(99L, 1L, 0, 20);

        assertThat(response.author().authorName()).isEqualTo("탈퇴한 사용자");
        assertThat(response.author().profileImageUrl()).isNull();
    }

    // soft delete로 status만 DELETED로 바뀐 실제 탈퇴 유저 - row는 존재하되 isDeleted()가 true인 경우
    // Optional.empty()(row 자체가 없는 경우)와는 다른 케이스라 별도로 검증한다.
    @Test
    void 상세조회_작성자가_soft_delete로_탈퇴했으면_탈퇴한_사용자로_마스킹된다() {
        Post tipPost = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        User deletedAuthor = user(10L, "원래닉네임", UserRole.USER);
        deletedAuthor.delete(); // status=DELETED, row는 그대로 존재

        when(postRepository.findById(1L)).thenReturn(Optional.of(tipPost));
        when(userRepository.findById(10L)).thenReturn(Optional.of(deletedAuthor));
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        stubEmptyCommentTree(1L);

        PostDetailResponse response = postService.getPostDetail(99L, 1L, 0, 20);

        assertThat(response.author().authorName()).isEqualTo("탈퇴한 사용자");
        assertThat(response.author().profileImageUrl()).isNull();
    }

    @Test
    void 상세조회_탈퇴한_댓글과_대댓글_작성자는_마스킹한다() {
        Post tipPost = post(
                1L,
                10L,
                PostType.TIP,
                PostCategory.COOKING
        );
        User postAuthor = user(
                10L,
                "게시글작성자",
                UserRole.USER
        );
        User deletedCommenter = user(
                20L,
                "삭제전닉네임",
                UserRole.USER
        );
        deletedCommenter.delete();

        PostComment parentComment =
                PostComment.create(1L, 20L, null, "댓글");
        setField(parentComment, "id", 100L);

        PostComment reply =
                PostComment.create(1L, 20L, 100L, "대댓글");
        setField(reply, "id", 101L);

        when(postRepository.findById(1L))
                .thenReturn(Optional.of(tipPost));
        when(userRepository.findById(10L))
                .thenReturn(Optional.of(postAuthor));
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L))
                .thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L))
                .thenReturn(false);
        when(postCommentRepository
                .findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(
                        eq(1L),
                        any(Pageable.class)
                ))
                .thenReturn(new SliceImpl<>(
                        List.of(parentComment),
                        PageRequest.of(0, 20),
                        false
                ));
        when(postCommentRepository.findRepliesWithinLimit(
                List.of(100L),
                20
        )).thenReturn(List.of(reply));

        when(postCommentRepository
                .countRepliesByParentCommentIdIn(
                        List.of(100L)
                ))
                .thenReturn(List.of(
                        replyCount(100L, 1L)
                ));
        when(userRepository.findAllById(List.of(20L)))
                .thenReturn(List.of(deletedCommenter));

        PostDetailResponse response =
                postService.getPostDetail(99L, 1L, 0, 20);

        CommentResponse comment = response.comments().content().get(0);
        ReplyResponse replyResponse = comment.replies().get(0);

        assertThat(comment.authorName()).isEqualTo("탈퇴한 사용자");
        assertThat(comment.profileImageUrl()).isNull();
        assertThat(comment.replyCount()).isEqualTo(1L);
        assertThat(comment.hasMoreReplies()).isFalse();
        assertThat(replyResponse.authorName()).isEqualTo("탈퇴한 사용자");
        assertThat(replyResponse.profileImageUrl()).isNull();
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
        when(postCommentRepository.findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(eq(1L), any()))
                .thenReturn(new SliceImpl<>(List.of(parentComment), PageRequest.of(0, 20), false));
        when(postCommentRepository.findRepliesWithinLimit(
                List.of(100L),
                20
        )).thenReturn(List.of(replyComment));

        when(postCommentRepository
                .countRepliesByParentCommentIdIn(
                        List.of(100L)
                ))
                .thenReturn(List.of(
                        replyCount(100L, 1L)
                ));
        when(userRepository.findAllById(anyList())).thenReturn(List.of(commenter));

        PostDetailResponse response = postService.getPostDetail(99L, 1L, 0, 20);

        CommentResponse comment =
                response.comments().content().get(0);

        assertThat(comment.content())
                .isEqualTo("부모 댓글");
        assertThat(comment.replies())
                .hasSize(1);
        assertThat(comment.replies().get(0).content())
                .isEqualTo("대댓글");
        assertThat(comment.replyCount())
                .isEqualTo(1L);
        assertThat(comment.hasMoreReplies())
                .isFalse();
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
        when(postCommentRepository.findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(eq(1L), any()))
                .thenReturn(new SliceImpl<>(List.of(deletedComment), PageRequest.of(0, 20), false));
        when(postCommentRepository.findRepliesWithinLimit(
                List.of(100L),
                20
        )).thenReturn(List.of());

        when(postCommentRepository
                .countRepliesByParentCommentIdIn(
                        List.of(100L)
                ))
                .thenReturn(List.of());
        when(userRepository.findAllById(anyList())).thenReturn(List.of(commenter));

        PostDetailResponse response = postService.getPostDetail(99L, 1L, 0, 20);

        CommentResponse comment =
                response.comments().content().get(0);

        assertThat(comment.content())
                .isEqualTo("삭제될 내용");
        assertThat(comment.isDeleted())
                .isTrue();
        assertThat(comment.replyCount())
                .isZero();
        assertThat(comment.hasMoreReplies())
                .isFalse();

        assertThat(response.comments().content().get(0).content()).isEqualTo("삭제될 내용");
        assertThat(response.comments().content().get(0).isDeleted()).isTrue();
    }

    @Test
    void 상세조회_대댓글이_상한을_초과하면_20개와_전체개수를_반환한다() {
        Post tipPost =
                post(
                        1L,
                        10L,
                        PostType.TIP,
                        PostCategory.COOKING
                );

        User author =
                user(
                        10L,
                        "작성자",
                        UserRole.USER
                );

        User commenter =
                user(
                        20L,
                        "댓글러",
                        UserRole.USER
                );

        PostComment parentComment =
                PostComment.create(
                        1L,
                        20L,
                        null,
                        "부모 댓글"
                );
        setField(parentComment, "id", 100L);

        List<PostComment> replies =
                new java.util.ArrayList<>();

        // 저장소 쿼리가 이미 부모별 최대 20개만 반환한다.
        for (int i = 0; i < 20; i++) {
            PostComment reply =
                    PostComment.create(
                            1L,
                            20L,
                            100L,
                            "대댓글" + i
                    );

            setField(reply, "id", 200L + i);
            replies.add(reply);
        }

        when(postRepository.findById(1L))
                .thenReturn(Optional.of(tipPost));

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(author));

        when(postImageRepository
                .findByPostIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of());

        when(postLikeRepository
                .existsByPostIdAndUserId(1L, 99L))
                .thenReturn(false);

        when(postBookmarkRepository
                .existsByPostIdAndUserId(1L, 99L))
                .thenReturn(false);

        when(postCommentRepository
                .findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(
                        eq(1L),
                        any(Pageable.class)
                ))
                .thenReturn(new SliceImpl<>(
                        List.of(parentComment),
                        PageRequest.of(0, 20),
                        false
                ));

        when(postCommentRepository.findRepliesWithinLimit(
                List.of(100L),
                20
        )).thenReturn(replies);

        when(postCommentRepository
                .countRepliesByParentCommentIdIn(
                        List.of(100L)
                ))
                .thenReturn(List.of(
                        replyCount(100L, 25L)
                ));

        when(userRepository.findAllById(anyList()))
                .thenReturn(List.of(commenter));

        PostDetailResponse response =
                postService.getPostDetail(
                        99L,
                        1L,
                        0,
                        20
                );

        CommentResponse comment =
                response.comments().content().get(0);

        assertThat(comment.replies())
                .hasSize(20);
        assertThat(comment.replyCount())
                .isEqualTo(25L);
        assertThat(comment.hasMoreReplies())
                .isTrue();

        verify(postCommentRepository)
                .findRepliesWithinLimit(
                        List.of(100L),
                        20
                );
    }

    @Test
    void 상세조회_최상위댓글은_요청한_commentPage_commentSize로_페이지네이션된다() {
        Post tipPost = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        User author = user(10L, "작성자", UserRole.USER);

        PostComment comment = PostComment.create(1L, 20L, null, "댓글");
        setField(comment, "id", 100L);

        when(postRepository.findById(1L)).thenReturn(Optional.of(tipPost));
        when(userRepository.findById(10L)).thenReturn(Optional.of(author));
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        // 2번째 페이지(page=1), 페이지 크기 5, 다음 페이지 있음(hasNext=true)인 상황을 재현
        when(postCommentRepository.findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(eq(1L), any()))
                .thenReturn(new SliceImpl<>(List.of(comment), PageRequest.of(1, 5), true));
        when(postCommentRepository.findRepliesWithinLimit(
                anyList(),
                eq(20)
        )).thenReturn(List.of());

        when(postCommentRepository
                .countRepliesByParentCommentIdIn(
                        anyList()
                ))
                .thenReturn(List.of());
        when(userRepository.findAllById(anyList())).thenReturn(List.of());

        PostDetailResponse response = postService.getPostDetail(99L, 1L, 1, 5);

        // 요청한 페이지 정보(commentPage=1, commentSize=5)가 리포지토리 호출에 그대로 전달됐는지 확인
        verify(postCommentRepository).findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(
                eq(1L),
                argThat(pageable -> pageable.getPageNumber() == 1 && pageable.getPageSize() == 5)
        );
        // 응답에도 페이지 정보(page, hasNext)가 정확히 반영됐는지 확인
        assertThat(response.comments().page()).isEqualTo(1);
        assertThat(response.comments().size()).isEqualTo(5);
        assertThat(response.comments().hasNext()).isTrue();
    }

    @Test
    void 상세조회_댓글이_없으면_빈_페이지를_반환한다() {
        Post tipPost = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        User author = user(10L, "작성자", UserRole.USER);

        when(postRepository.findById(1L)).thenReturn(Optional.of(tipPost));
        when(userRepository.findById(10L)).thenReturn(Optional.of(author));
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postBookmarkRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(false);
        when(postCommentRepository.findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(eq(1L), any()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        PostDetailResponse response = postService.getPostDetail(99L, 1L, 0, 20);

        assertThat(response.comments().content()).isEmpty();
        assertThat(response.comments().hasNext()).isFalse();
        // 최상위 댓글이 없으면 대댓글 조회 자체를 안 해야 함(불필요한 쿼리 방지)
        verify(postCommentRepository, never())
                .findRepliesWithinLimit(
                        anyList(),
                        anyInt()
                );

        verify(postCommentRepository, never())
                .countRepliesByParentCommentIdIn(
                        anyList()
                );
    }

    @Test
    void 게시글목록_size가_100을_초과하면_400을_반환한다() {
        assertThatThrownBy(() ->
                PostListQuery.of(
                        "LATEST",
                        0,
                        101
                )
        )
                .isInstanceOf(CustomException.class);
    }

    // ========== 6. 게시글 작성 ==========

    @Test
    void 꿀팁_게시글을_작성하고_이미지를_순서대로_저장한다() {
        TipPostRequest request = new TipPostRequest(
                "COOKING", "밀프렙 루틴", "게시글 내용",
                List.of("community/posts/first.jpg", "community/posts/second.png")
        );

        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            setField(post, "id", 1L);
            return post;
        });
        when(imagePresignService.buildPublicUrl("community/posts/first.jpg")).thenReturn("https://s3/first.jpg");
        when(imagePresignService.buildPublicUrl("community/posts/second.png")).thenReturn("https://s3/second.png");

        PostMutationResponse response = postService.createTipPost(10L, request);

        assertThat(response.postId()).isEqualTo(1L);
        verify(postRepository).save(argThat(post ->
                post.getUserId().equals(10L)
                        && post.getPostType() == PostType.TIP
                        && post.getCategory() == PostCategory.COOKING
                        && post.getTitle().equals("밀프렙 루틴")
                        && post.getContent().equals("게시글 내용")
        ));
        verify(postImageRepository).saveAll(argThat(images -> {
            List<PostImage> imageList = new java.util.ArrayList<>();
            images.forEach(imageList::add);
            return imageList.size() == 2
                    && imageList.get(0).getImageKey().equals("community/posts/first.jpg")
                    && imageList.get(0).getSortOrder() == 0
                    && imageList.get(1).getImageKey().equals("community/posts/second.png")
                    && imageList.get(1).getSortOrder() == 1;
        }));
    }

    @Test
    void 꿀팁_게시글에_RECRUIT_카테고리를_사용하면_예외() {
        TipPostRequest request = new TipPostRequest("RECRUIT", "제목", "내용", List.of());

        assertThatThrownBy(() -> postService.createTipPost(10L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_INVALID_POST_CATEGORY);

        verifyNoInteractions(postRepository);
    }

    @Test
    void 꿀팁_게시글의_이미지키가_중복되면_전용에러를_반환한다() {
        TipPostRequest request = new TipPostRequest(
                "COOKING", "제목", "내용",
                List.of("community/posts/same.jpg", "community/posts/same.jpg")
        );

        assertThatThrownBy(() -> postService.createTipPost(10L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_DUPLICATE_IMAGE);

        verifyNoInteractions(postRepository);
    }

    @Test
    void 뭐먹지_게시글과_음식상세를_함께_작성한다() {
        FoodPostRequest request = new FoodPostRequest(
                "마라탕 추천", "마라탕", "홍대 마라공방", 9000,
                5, 4, 5, "맛있어요", List.of()
        );

        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            setField(post, "id", 2L);
            return post;
        });

        PostMutationResponse response = postService.createFoodPost(10L, request);

        assertThat(response.postId()).isEqualTo(2L);
        verify(postRepository).save(argThat(post ->
                post.getPostType() == PostType.FOOD_RECOMMEND
                        && post.getCategory() == PostCategory.FOOD_RECOMMEND
                        && post.getTitle().equals("마라탕 추천")
        ));
        verify(foodPostDetailRepository).save(argThat(detail ->
                detail.getPostId().equals(2L)
                        && detail.getMenu().equals("마라탕")
                        && detail.getPlace().equals("홍대 마라공방")
                        && detail.getPrice() == 9000
                        && detail.getTasteRating() == 5
                        && detail.getCostRating() == 4
                        && detail.getMoodRating() == 5
        ));
        verify(postImageRepository, never()).saveAll(any());
    }

    @Test
    void 모집_게시글은_초대코드로_배틀을_확인하고_작성한다() {
        User creator = user(10L, "작성자", UserRole.USER);
        Battle battle = Battle.of(
                "ABC123", "절약 배틀", 5, 7,
                java.time.LocalDate.of(2026, 8, 20), "커피 사기", creator
        );
        setField(battle, "id", 5L);

        RecruitPostRequest request = new RecruitPostRequest(
                "같이 절약해요", "3명 모집합니다",
                "https://hampouch.app/api/battles/invitations/ABC123"
        );

        when(battleRepository.findByBattleCode("ABC123")).thenReturn(Optional.of(battle));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            setField(post, "id", 3L);
            return post;
        });

        PostMutationResponse response = postService.createRecruitPost(10L, request);

        assertThat(response.postId()).isEqualTo(3L);
        verify(recruitPostDetailRepository).save(argThat(detail ->
                detail.getPostId().equals(3L)
                        && detail.getBattleId().equals(5L)
                        && detail.getBattleUrl().equals("https://hampouch.app/api/battles/invitations/ABC123")
        ));
    }

    @Test
    void 모집_게시글의_초대URL_형식이_잘못되면_예외() {
        RecruitPostRequest request = new RecruitPostRequest(
                "제목", "내용", "https://hampouch.app/battles/ABC123"
        );

        assertThatThrownBy(() -> postService.createRecruitPost(10L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_INVALID_BATTLE_URL);

        verifyNoInteractions(battleRepository, postRepository);
    }

    @Test
    void 모집_게시글의_invite_경로는_허용하지않는다() {
        RecruitPostRequest request = new RecruitPostRequest(
                "제목", "내용", "https://hampouch.app/battles/invite/ABC123"
        );

        assertThatThrownBy(() -> postService.createRecruitPost(10L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_INVALID_BATTLE_URL);

        verifyNoInteractions(battleRepository, postRepository);
    }

    @Test
    void 모집_게시글의_배틀이_없으면_예외() {
        RecruitPostRequest request = new RecruitPostRequest(
                "제목", "내용", "https://hampouch.app/api/battles/invitations/UNKNOWN"
        );
        when(battleRepository.findByBattleCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.createRecruitPost(10L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_BATTLE_NOT_FOUND);

        verifyNoInteractions(postRepository);
    }

// ========== 7. 게시글 수정 ==========

    @Test
    void 꿀팁_게시글을_수정하면_이미지를_요청목록으로_교체한다() {
        Post post = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        TipPostRequest request = new TipPostRequest(
                "DISCOUNT", "수정된 제목", "수정된 내용",
                List.of("community/posts/new.jpg")
        );

        PostImage oldImage = PostImage.create(1L, "https://s3/old.jpg", "community/posts/old.jpg", 0);
        PostImage keptImage = PostImage.create(1L, "https://s3/new.jpg", "community/posts/new.jpg", 1);

        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(imagePresignService.buildPublicUrl("community/posts/new.jpg")).thenReturn("https://s3/new.jpg");
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L)).thenReturn(List.of(oldImage, keptImage));

        PostMutationResponse response = postService.updateTipPost(10L, 1L, request);

        assertThat(response.postId()).isEqualTo(1L);
        assertThat(post.getCategory()).isEqualTo(PostCategory.DISCOUNT);
        assertThat(post.getTitle()).isEqualTo("수정된 제목");
        assertThat(post.getContent()).isEqualTo("수정된 내용");
        verify(postImageRepository).deleteAllByPostId(1L);
        verify(postImageRepository).saveAll(argThat(images -> {
            List<PostImage> imageList = new java.util.ArrayList<>();
            images.forEach(imageList::add);
            return imageList.size() == 1
                    && imageList.get(0).getImageKey().equals("community/posts/new.jpg")
                    && imageList.get(0).getSortOrder() == 0;
        }));

        verify(eventPublisher).publishEvent(
                (Object) new CommunityImageDeleteEvent(
                        List.of("community/posts/old.jpg")
                )
        );
    }

    @Test
    void 뭐먹지_게시글과_음식상세를_함께_수정한다() {
        Post post = post(2L, 10L, PostType.FOOD_RECOMMEND, PostCategory.FOOD_RECOMMEND);
        FoodPostDetail detail = FoodPostDetail.create(
                2L, "기존 메뉴", "기존 장소", 5000, 3, 3, 3
        );
        FoodPostRequest request = new FoodPostRequest(
                "수정된 제목", "마라탕", "홍대 마라공방", 9000,
                5, 4, 5, "수정된 내용", List.of()
        );

        when(postRepository.findById(2L)).thenReturn(Optional.of(post));
        when(foodPostDetailRepository.findByPostId(2L)).thenReturn(Optional.of(detail));

        PostMutationResponse response = postService.updateFoodPost(10L, 2L, request);

        assertThat(response.postId()).isEqualTo(2L);
        assertThat(post.getTitle()).isEqualTo("수정된 제목");
        assertThat(post.getContent()).isEqualTo("수정된 내용");
        assertThat(detail.getMenu()).isEqualTo("마라탕");
        assertThat(detail.getPlace()).isEqualTo("홍대 마라공방");
        assertThat(detail.getPrice()).isEqualTo(9000);
        assertThat(detail.getTasteRating()).isEqualTo(5);
        assertThat(detail.getCostRating()).isEqualTo(4);
        assertThat(detail.getMoodRating()).isEqualTo(5);
        verify(postImageRepository).deleteAllByPostId(2L);
        verify(postImageRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 모집_게시글의_배틀과_초대URL을_수정한다() {
        Post post = post(3L, 10L, PostType.RECRUIT, PostCategory.RECRUIT);
        RecruitPostDetail detail = RecruitPostDetail.create(
                3L, 5L, "https://hampouch.app/api/battles/invitations/OLD"
        );
        User creator = user(10L, "작성자", UserRole.USER);
        Battle battle = Battle.of(
                "NEW", "새 배틀", 5, 7,
                java.time.LocalDate.of(2026, 8, 20), "커피 사기", creator
        );
        setField(battle, "id", 6L);

        RecruitPostRequest request = new RecruitPostRequest(
                "수정된 모집글", "2명 모집합니다",
                "https://hampouch.app/api/battles/invitations/NEW"
        );

        when(postRepository.findById(3L)).thenReturn(Optional.of(post));
        when(battleRepository.findByBattleCode("NEW")).thenReturn(Optional.of(battle));
        when(recruitPostDetailRepository.findByPostId(3L)).thenReturn(Optional.of(detail));

        PostMutationResponse response = postService.updateRecruitPost(10L, 3L, request);

        assertThat(response.postId()).isEqualTo(3L);
        assertThat(post.getTitle()).isEqualTo("수정된 모집글");
        assertThat(post.getContent()).isEqualTo("2명 모집합니다");
        assertThat(detail.getBattleId()).isEqualTo(6L);
        assertThat(detail.getBattleUrl()).isEqualTo("https://hampouch.app/api/battles/invitations/NEW");
    }

    @Test
    void 다른_사용자의_게시글은_수정할수없다() {
        Post post = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        TipPostRequest request = new TipPostRequest(
                "COOKING", "수정 제목", "수정 내용", List.of()
        );
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.updateTipPost(99L, 1L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_NOT_POST_AUTHOR);

        verify(postImageRepository, never()).deleteAllByPostId(anyLong());
    }

    @Test
    void 다른_유형의_수정API를_호출하면_예외() {
        Post foodPost = post(2L, 10L, PostType.FOOD_RECOMMEND, PostCategory.FOOD_RECOMMEND);
        TipPostRequest request = new TipPostRequest(
                "COOKING", "수정 제목", "수정 내용", List.of()
        );
        when(postRepository.findById(2L)).thenReturn(Optional.of(foodPost));

        assertThatThrownBy(() -> postService.updateTipPost(10L, 2L, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_POST_TYPE_MISMATCH);
    }

    // ========== 8. 게시글 삭제 ==========

    @Test
    void 게시글을_삭제하면_연관데이터와_이미지를_삭제한다() {
        Post post = post(1L, 10L, PostType.FOOD_RECOMMEND, PostCategory.FOOD_RECOMMEND);
        PostImage image = PostImage.create(1L, "https://s3/delete.jpg", "community/posts/delete.jpg", 0);

        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(postImageRepository.findByPostIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of(image));

        postService.deletePost(10L, 1L);

        verify(postCommentRepository).deleteAllByPostId(1L);
        verify(postLikeRepository).deleteAllByPostId(1L);
        verify(postBookmarkRepository).deleteAllByPostId(1L);
        verify(postImageRepository).deleteAllByPostId(1L);
        verify(foodPostDetailRepository).deleteById(1L);
        verify(recruitPostDetailRepository, never()).deleteById(anyLong());
        verify(postRepository).delete(post);
        verify(eventPublisher).publishEvent(
                (Object) new CommunityImageDeleteEvent(
                        List.of("community/posts/delete.jpg")
                )
        );
    }

    @Test
    void 다른_사용자의_게시글은_삭제할수없다() {
        Post post = post(1L, 10L, PostType.TIP, PostCategory.COOKING);
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.deletePost(99L, 1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMUNITY_NOT_POST_AUTHOR);

        verify(postCommentRepository, never()).deleteAllByPostId(anyLong());
        verify(postRepository, never()).delete(any(Post.class));
    }

    // ========== 공통 헬퍼 ==========

    private void stubEmptyCommentTree(Long postId) {
        lenient().when(
                postCommentRepository
                        .findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(
                                eq(postId),
                                any()
                        )
        ).thenReturn(new SliceImpl<>(
                List.of(),
                PageRequest.of(0, 20),
                false
        ));
    }

    private void stubEmptyN1Lookups() {
        lenient().when(postImageRepository.findFirstImagesByPostIdIn(anyList())).thenReturn(List.of());
        lenient().when(userRepository.findAllById(anyList())).thenReturn(List.of());
        lenient().when(postLikeRepository.findByPostIdInAndUserId(anyList(), any())).thenReturn(List.of());
        lenient().when(postBookmarkRepository.findByPostIdInAndUserId(anyList(), any())).thenReturn(List.of());
    }

    private PostCommentRepository.ReplyCountView replyCount(
            Long parentCommentId,
            long count
    ) {
        return new PostCommentRepository.ReplyCountView() {
            @Override
            public Long getParentCommentId() {
                return parentCommentId;
            }

            @Override
            public long getReplyCount() {
                return count;
            }
        };
    }
}
