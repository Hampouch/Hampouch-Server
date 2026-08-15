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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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
    // 대댓글 무제한 로드 방지 - 최상위 댓글 하나당 이 개수까지만 노출
    private static final int MAX_REPLIES_PER_COMMENT = 20;

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
                : postRepository.findByUserIdInOrderByCreatedAtDesc(
                adminUserIds, PageRequest.of(0, HOME_SECTION_SIZE));

        Pageable pageable = PageRequest.of(query.page(), query.size(), resolveSort(query.sortType()));
        Slice<Post> postSlice = postRepository.findAllPosts(pageable);

        return HomeResponse.of(
                toListResponses(popularPosts, loginUserId),
                toListResponses(pochiPicks, loginUserId),
                toPageResponse(postSlice, loginUserId)
        );
    }

    //인기글 전체보기
    public PageResponse<PostListResponse> getPopularPosts(Long loginUserId, PostListQuery query) {
        Pageable pageable = PageRequest.of(query.page(), query.size(), resolveSort(query.sortType()));
        Slice<Post> postSlice = postRepository.findPopularPosts(POPULAR_POST_LIKE_THRESHOLD, pageable);
        return toPageResponse(postSlice, loginUserId);
    }

    //포치픽 전체보기
    public PageResponse<PostListResponse> getPochiPicks(Long loginUserId, PostListQuery query) {
        List<Long> adminUserIds = findAdminUserIds();

        if (adminUserIds.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.size(), false);
        }

        Pageable pageable = PageRequest.of(query.page(), query.size(), resolveSort(query.sortType()));
        Slice<Post> postSlice = postRepository.findByUserIdIn(adminUserIds, pageable);
        return toPageResponse(postSlice, loginUserId);
    }

    //카테고리별 목록 조회
    public PageResponse<PostListResponse> getPostsByCategory(Long loginUserId, String categoryParam, PostListQuery query) {
        PostCategory category = parseCategory(categoryParam);
        Pageable pageable = PageRequest.of(query.page(), query.size(), resolveSort(query.sortType()));
        Slice<Post> postSlice = postRepository.findByCategory(category, pageable);
        return toPageResponse(postSlice, loginUserId);
    }

    //게시글 상세 조회
    @Transactional
    public PostDetailResponse getPostDetail(Long loginUserId, Long postId, int commentPage, int commentSize) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new CustomException(CommunityErrorCode.COMMUNITY_POST_NOT_FOUND));

        postRepository.increaseViewCount(postId);

        User author = userRepository.findById(post.getUserId()).orElse(null);
        AuthorResponse authorResponse = toAuthorResponse(post.getUserId(), author);

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

        PageResponse<CommentResponse> comments = buildCommentTree(postId, loginUserId, commentPage, commentSize);

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

    // 작성자 표시 처리 - user row 자체가 없는 경우(방어적) 뿐 아니라, soft delete로 status만
    // DELETED로 바뀐 경우(user.isDeleted())도 동일하게 "탈퇴한 사용자"로 마스킹해야 한다.
    // 이전에는 author == null만 확인해서, 실제로 존재하지만 탈퇴 처리된 유저의 닉네임이
    // 그대로 노출되는 문제가 있었다.
    private AuthorResponse toAuthorResponse(Long userId, User author) {
        if (author == null || author.isDeleted()) {
            return AuthorResponse.of(userId, "탈퇴한 사용자", null);
        }
        return AuthorResponse.of(userId, author.getNickname(), author.getProfileImageUrl());
    }

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

    private PageResponse<PostListResponse> toPageResponse(Slice<Post> postSlice, Long loginUserId) {
        List<PostListResponse> content = toListResponses(postSlice.getContent(), loginUserId);
        return PageResponse.of(content, postSlice.getNumber(), postSlice.getSize(), postSlice.hasNext());
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

        // 게시글당 sortOrder가 가장 작은 이미지 1건만 DB에서 직접 가져온다(findFirstImagesByPostIdIn).
        // 이전에는 게시글당 이미지를 전부 읽어와 서비스에서 첫 번째만 취하고 나머지를 버렸는데,
        // 게시글당 이미지가 여러 장이면 불필요한 행까지 다 읽는 낭비가 있었다.
        Map<Long, String> thumbnailByPostId = postImageRepository.findFirstImagesByPostIdIn(postIds).stream()
                .collect(Collectors.toMap(PostImage::getPostId, PostImage::getImageUrl));

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
     * 게시글의 댓글을 최상위 댓글 기준으로 페이지네이션해서 부모-자식 트리로 조립.
     * 최상위 댓글은 요청한 page/size로 실제 페이지네이션되고(더보기 요청 시 다음 페이지 조회 가능),
     * 대댓글은 최상위 댓글당 상한(MAX_REPLIES_PER_COMMENT)만 노출한다.
     * 삭제된 댓글도 content/작성자 정보를 원본 그대로 내려주고, isDeleted로 클라이언트가 표현 방식을 결정
     */
    private PageResponse<CommentResponse> buildCommentTree(Long postId, Long loginUserId, int commentPage, int commentSize) {
        Pageable pageable = PageRequest.of(commentPage, commentSize, Sort.by(Sort.Direction.ASC, "createdAt"));
        Slice<PostComment> topLevelSlice = postCommentRepository.findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAsc(postId, pageable);

        List<PostComment> topLevelComments = topLevelSlice.getContent();

        if (topLevelComments.isEmpty()) {
            return PageResponse.of(List.of(), commentPage, commentSize, false);
        }

        List<Long> topLevelCommentIds = topLevelComments.stream().map(PostComment::getId).toList();

        // 대댓글은 최상위 댓글당 상한을 둔다. DB에서 상한을 정확히 적용하려면 윈도우 함수나
        // 배치 조회가 필요한데, 우선 부모 댓글별로 넉넉히 가져온 뒤 서비스에서 자른다.
        // (최상위 댓글 자체가 페이지네이션되어 있어 한 요청이 읽는 대댓글 총량도 자연히 제한됨)
        List<PostComment> replies = postCommentRepository.findByParentCommentIdInOrderByCreatedAtAsc(topLevelCommentIds);

        List<Long> userIds = java.util.stream.Stream.concat(
                        topLevelComments.stream().map(PostComment::getUserId),
                        replies.stream().map(PostComment::getUserId))
                .distinct()
                .toList();
        Map<Long, User> userById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        Map<Long, List<ReplyResponse>> repliesByParentId = replies.stream()
                .collect(Collectors.groupingBy(
                        PostComment::getParentCommentId,
                        Collectors.mapping(comment -> toReplyResponse(comment, userById, loginUserId), Collectors.toList())
                ));

        List<CommentResponse> content = topLevelComments.stream()
                .map(comment -> {
                    User commentUser = userById.get(comment.getUserId());
                    List<ReplyResponse> commentReplies = repliesByParentId.getOrDefault(comment.getId(), List.of());
                    if (commentReplies.size() > MAX_REPLIES_PER_COMMENT) {
                        commentReplies = commentReplies.subList(0, MAX_REPLIES_PER_COMMENT);
                    }
                    return CommentResponse.of(
                            comment.getId(),
                            comment.getUserId(),
                            commentUser != null ? commentUser.getNickname() : "탈퇴한 사용자",
                            commentUser != null ? commentUser.getProfileImageUrl() : null,
                            comment.getContent(),
                            comment.isDeleted(),
                            comment.isOwnedBy(loginUserId),
                            comment.getCreatedAt(),
                            commentReplies
                    );
                })
                .toList();

        return PageResponse.of(content, topLevelSlice.getNumber(), topLevelSlice.getSize(), topLevelSlice.hasNext());
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