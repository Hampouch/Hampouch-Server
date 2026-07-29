package Hampouch.server.domain.community.service;

import Hampouch.server.domain.community.dto.request.PostListQuery;
import Hampouch.server.domain.community.dto.response.*;
import Hampouch.server.domain.community.entity.Post;
import Hampouch.server.domain.community.entity.PostCategory;
import Hampouch.server.domain.community.entity.PostComment;
import Hampouch.server.domain.community.entity.PostImage;
import Hampouch.server.domain.community.entity.PostLike;
import Hampouch.server.domain.community.entity.PostType;
import Hampouch.server.domain.community.repository.FoodPostDetailRepository;
import Hampouch.server.domain.community.repository.PostBookmarkRepository;
import Hampouch.server.domain.community.repository.PostCommentRepository;
import Hampouch.server.domain.community.repository.PostImageRepository;
import Hampouch.server.domain.community.repository.PostLikeRepository;
import Hampouch.server.domain.community.repository.PostRepository;
import Hampouch.server.domain.community.repository.RecruitPostDetailRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.CommunityErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    private static final int POPULAR_POST_LIKE_THRESHOLD = 10;
    private static final int HOME_SECTION_SIZE = 5;

    private final PostRepository postRepository;
    private final PostImageRepository postImageRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostBookmarkRepository postBookmarkRepository;
    private final PostCommentRepository postCommentRepository;
    private final FoodPostDetailRepository foodPostDetailRepository;
    private final RecruitPostDetailRepository recruitPostDetailRepository;
    private final UserRepository userRepository;

    //커뮤니티 홈 조회
    public HomeResponse getHome(Long loginUserId, PostListQuery query) {
        List<Post> popularPosts = postRepository.findTopPopularPosts(
                POPULAR_POST_LIKE_THRESHOLD, PageRequest.of(0, HOME_SECTION_SIZE));

        List<Long> adminUserIds = findAdminUserIds();
        List<Post> pochiPicks = adminUserIds.isEmpty()
                ? List.of()
                : postRepository.findTopByUserIdInOrderByCreatedAtDesc(
                adminUserIds, PageRequest.of(0, HOME_SECTION_SIZE));

        Pageable pageable = PageRequest.of(query.page(), query.size(), resolveSort(query.sortType()));
        Page<Post> postPage = postRepository.findAll(pageable);

        return HomeResponse.of(
                toListResponses(popularPosts, loginUserId),
                toListResponses(pochiPicks, loginUserId),
                toPageResponse(postPage, loginUserId)
        );
    }

    //인기글 전체보기
    public PageResponse<PostListResponse> getPopularPosts(Long loginUserId, PostListQuery query) {
        Pageable pageable = PageRequest.of(query.page(), query.size(), resolveSort(query.sortType()));
        Page<Post> postPage = postRepository.findPopularPosts(POPULAR_POST_LIKE_THRESHOLD, pageable);
        return toPageResponse(postPage, loginUserId);
    }

    //포치픽 전체보기
    public PageResponse<PostListResponse> getPochiPicks(Long loginUserId, PostListQuery query) {
        List<Long> adminUserIds = findAdminUserIds();

        if (adminUserIds.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.size(), false);
        }

        Pageable pageable = PageRequest.of(query.page(), query.size(), resolveSort(query.sortType()));
        Page<Post> postPage = postRepository.findByUserIdIn(adminUserIds, pageable);
        return toPageResponse(postPage, loginUserId);
    }

    //카테고리별 목록 조회
    public PageResponse<PostListResponse> getPostsByCategory(Long loginUserId, String categoryParam, PostListQuery query) {
        PostCategory category = parseCategory(categoryParam);
        Pageable pageable = PageRequest.of(query.page(), query.size(), resolveSort(query.sortType()));
        Page<Post> postPage = postRepository.findByCategory(category, pageable);
        return toPageResponse(postPage, loginUserId);
    }

    //게시글 상세 조회
    @Transactional
    public PostDetailResponse getPostDetail(Long loginUserId, Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new CustomException(CommunityErrorCode.COMMUNITY_POST_NOT_FOUND));

        postRepository.increaseViewCount(postId);

        User author = userRepository.findById(post.getUserId()).orElse(null);
        AuthorResponse authorResponse = AuthorResponse.of(
                post.getUserId(),
                author != null ? author.getNickname() : "탈퇴한 사용자",
                author != null ? author.getProfileImageUrl() : null
        );

        FoodDetailResponse foodDetail = post.getPostType() == PostType.FOOD_RECOMMEND
                ? toFoodDetailResponse(postId)
                : null;

        RecruitDetailResponse recruitDetail = post.getPostType() == PostType.RECRUIT
                ? toRecruitDetailResponse(postId)
                : null;

        List<PostImageResponse> images = postImageRepository.findByPostIdOrderBySortOrderAsc(postId).stream()
                .map(image -> PostImageResponse.of(image.getImageKey(), image.getImageUrl()))
                .toList();

        boolean isLiked = postLikeRepository.existsByPostIdAndUserId(postId, loginUserId);
        boolean isBookmarked = postBookmarkRepository.existsByPostIdAndUserId(postId, loginUserId);

        List<CommentResponse> comments = buildCommentTree(postId, loginUserId);

        return PostDetailResponse.of(
                post.getId(),
                post.getPostType().name(),
                post.getCategory().name(),
                post.getTitle(),
                post.getContent(),
                authorResponse,
                foodDetail,
                recruitDetail,
                images,
                post.getViewCount() + 1, //방금 증가시킨 값 반영(벌크 업데이트라 post 객체 자체는 갱신 안 됨)
                post.getLikeCount(),
                post.getCommentCount(),
                post.getCreatedAt(),
                post.getUpdatedAt(),
                isLiked,
                isBookmarked,
                post.isOwnedBy(loginUserId),
                comments
        );
    }

    //공통 헬퍼
    private List<Long> findAdminUserIds() {
        return userRepository.findByRole(UserRole.ADMIN).stream()
                .map(User::getId)
                .toList();
    }

    private Sort resolveSort(String sortType) {
        return switch (sortType) {
            case "POPULAR" -> Sort.by(Sort.Direction.DESC, "likeCount");
            case "VIEW" -> Sort.by(Sort.Direction.DESC, "viewCount");
            case "LATEST" -> Sort.by(Sort.Direction.DESC, "createdAt");
            default -> throw new CustomException(CommunityErrorCode.COMMUNITY_INVALID_SORT_TYPE);
        };
    }

    private PostCategory parseCategory(String categoryParam) {
        try {
            return PostCategory.valueOf(categoryParam);
        } catch (IllegalArgumentException e) {
            throw new CustomException(CommunityErrorCode.COMMUNITY_INVALID_POST_CATEGORY);
        }
    }

    private PageResponse<PostListResponse> toPageResponse(Page<Post> postPage, Long loginUserId) {
        List<PostListResponse> content = toListResponses(postPage.getContent(), loginUserId);
        return PageResponse.of(content, postPage.getNumber(), postPage.getSize(), postPage.hasNext());
    }

    /**
     * 게시글 목록을 목록용 응답으로 변환
     * 썸네일(이미지), 좋아요/북마크 여부를 게시글 개수만큼 쿼리 날리지 않고 postId 목록으로 한 번씩만 조회해서(N+1 방지) 매핑
     */
    private List<PostListResponse> toListResponses(List<Post> posts, Long loginUserId) {
        if (posts.isEmpty()) {
            return List.of();
        }

        List<Long> postIds = posts.stream().map(Post::getId).toList();

        //각 postId에 대해 처음 만나는 이미지만 채택 -> 썸네일(가장 작은 sortOrder)
        Map<Long, String> thumbnailByPostId = new HashMap<>();
        for (PostImage image : postImageRepository.findByPostIdInOrderByPostIdAscSortOrderAsc(postIds)) {
            thumbnailByPostId.putIfAbsent(image.getPostId(), image.getImageUrl());
        }

        List<Long> authorIds = posts.stream().map(Post::getUserId).distinct().toList();
        Map<Long, String> nicknameByUserId = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));

        Set<Long> likedPostIds = postLikeRepository.findByPostIdInAndUserId(postIds, loginUserId).stream()
                .map(PostLike::getPostId)
                .collect(Collectors.toSet());

        Set<Long> bookmarkedPostIds = postBookmarkRepository.findByPostIdInAndUserId(postIds, loginUserId).stream()
                .map(bookmark -> bookmark.getPostId())
                .collect(Collectors.toSet());

        return posts.stream()
                .map(post -> PostListResponse.of(
                        post.getId(),
                        post.getPostType().name(),
                        post.getCategory().name(),
                        post.getTitle(),
                        post.getContent(),
                        thumbnailByPostId.get(post.getId()),
                        nicknameByUserId.getOrDefault(post.getUserId(), "탈퇴한 사용자"),
                        post.getCreatedAt(),
                        post.getViewCount(),
                        post.getLikeCount(),
                        post.getCommentCount(),
                        likedPostIds.contains(post.getId()),
                        bookmarkedPostIds.contains(post.getId()),
                        post.isOwnedBy(loginUserId)
                ))
                .toList();
    }

    private FoodDetailResponse toFoodDetailResponse(Long postId) {
        return foodPostDetailRepository.findByPostId(postId)
                .map(detail -> FoodDetailResponse.of(
                        detail.getMenu(),
                        detail.getPlace(),
                        detail.getPrice(),
                        detail.getTasteRating(),
                        detail.getCostRating(),
                        detail.getMoodRating()
                ))
                .orElse(null);
    }

    private RecruitDetailResponse toRecruitDetailResponse(Long postId) {
        // TODO(Battle 도메인 merge 후): battleId로 Battle 조회해서 battleTitle, startDate, durationDays, maxMemberCount, currentMemberCount, penalty, recruit 채우기
        return recruitPostDetailRepository.findByPostId(postId)
                .map(detail -> RecruitDetailResponse.of(
                        detail.getBattleId(),
                        detail.getBattleUrl(),
                        null, null, 0, 0, 0, null, false
                ))
                .orElse(null);
    }

    /**
     * 게시글의 모든 댓글(최상위+대댓글)을 한 번에 조회해서 부모-자식 트리로 조립
     * parentCommentId가 null이면 최상위, 아니면 그 값이 가리키는 최상위 댓글의 replies에 담긴다
     * 삭제된 댓글도 content/작성자 정보를 원본 그대로 내려주고, isDeleted로 클라이언트가 표현 방식을 결정
     */
    private List<CommentResponse> buildCommentTree(Long postId, Long loginUserId) {
        List<PostComment> allComments = postCommentRepository.findByPostIdOrderByCreatedAtAsc(postId);

        if (allComments.isEmpty()) {
            return List.of();
        }

        List<Long> userIds = allComments.stream().map(PostComment::getUserId).distinct().toList();
        Map<Long, User> userById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        Map<Long, List<ReplyResponse>> repliesByParentId = allComments.stream()
                .filter(PostComment::isReply)
                .collect(Collectors.groupingBy(
                        PostComment::getParentCommentId,
                        Collectors.mapping(comment -> toReplyResponse(comment, userById, loginUserId), Collectors.toList())
                ));

        return allComments.stream()
                .filter(comment -> !comment.isReply())
                .map(comment -> {
                    User commentUser = userById.get(comment.getUserId());
                    return CommentResponse.of(
                            comment.getId(),
                            comment.getUserId(),
                            commentUser != null ? commentUser.getNickname() : "탈퇴한 사용자",
                            commentUser != null ? commentUser.getProfileImageUrl() : null,
                            comment.getContent(),
                            comment.isDeleted(),
                            comment.isOwnedBy(loginUserId),
                            comment.getCreatedAt(),
                            repliesByParentId.getOrDefault(comment.getId(), List.of())
                    );
                })
                .toList();
    }

    private ReplyResponse toReplyResponse(PostComment reply, Map<Long, User> userById, Long loginUserId) {
        User user = userById.get(reply.getUserId());
        return ReplyResponse.of(
                reply.getId(),
                reply.getUserId(),
                user != null ? user.getNickname() : "탈퇴한 사용자",
                user != null ? user.getProfileImageUrl() : null,
                reply.getContent(),
                reply.isDeleted(),
                reply.isOwnedBy(loginUserId),
                reply.getCreatedAt()
        );
    }
}