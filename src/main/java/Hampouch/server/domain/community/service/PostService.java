package Hampouch.server.domain.community.service;

import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.community.dto.request.*;
import Hampouch.server.domain.community.dto.response.*;
import Hampouch.server.domain.community.entity.*;
import Hampouch.server.domain.community.event.CommunityImageDeleteEvent;
import Hampouch.server.domain.community.repository.*;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.CommunityErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    private static final int POPULAR_POST_LIKE_THRESHOLD = 10;
    private static final int HOME_SECTION_SIZE = 5;
    //응답에는 최상위 댓글 하나당 대댓글을 이 개수까지만 노출
    private static final int MAX_REPLIES_PER_COMMENT = 20;

    private final PostRepository postRepository;
    private final PostImageRepository postImageRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostBookmarkRepository postBookmarkRepository;
    private final PostCommentRepository postCommentRepository;
    private final FoodPostDetailRepository foodPostDetailRepository;
    private final RecruitPostDetailRepository recruitPostDetailRepository;
    private final BattleRepository battleRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final UserRepository userRepository;
    private final ImagePresignService imagePresignService;
    private final ApplicationEventPublisher eventPublisher;

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

    //꿀팁 게시글 작성
    @Transactional
    public PostMutationResponse createTipPost(Long userId, TipPostRequest request) {
        PostCategory category = parseTipCategory(request.category());
        List<String> imageKeys = normalizeImageKeys(request.imageKeys());

        Post post = Post.create(userId, PostType.TIP, category, request.title(), request.content());
        Post savedPost = postRepository.save(post);

        saveImages(savedPost.getId(), imageKeys);

        return PostMutationResponse.from(savedPost.getId());
    }

    //뭐먹지 게시글 작성
    @Transactional
    public PostMutationResponse createFoodPost(Long userId, FoodPostRequest request) {
        List<String> imageKeys = normalizeImageKeys(request.imageKeys());

        Post post = Post.create(userId, PostType.FOOD_RECOMMEND, PostCategory.FOOD_RECOMMEND, request.title(), request.content());
        Post savedPost = postRepository.save(post);

        FoodPostDetail detail = FoodPostDetail.create(
                savedPost.getId(),
                request.menuName(),
                request.placeName(),
                request.price(),
                request.tasteRating(),
                request.costRating(),
                request.moodRating()
        );
        foodPostDetailRepository.save(detail);

        saveImages(savedPost.getId(), imageKeys);

        return PostMutationResponse.from(savedPost.getId());
    }

    //모집 게시글 작성
    @Transactional
    public PostMutationResponse createRecruitPost(Long userId, RecruitPostRequest request) {
        Battle battle = findBattleFromInvitationUrl(request.battleUrl());

        Post post = Post.create(userId, PostType.RECRUIT, PostCategory.RECRUIT, request.title(), request.content());
        Post savedPost = postRepository.save(post);

        RecruitPostDetail detail = RecruitPostDetail.create(savedPost.getId(), battle.getId(), request.battleUrl());
        recruitPostDetailRepository.save(detail);

        return PostMutationResponse.from(savedPost.getId());
    }

    //꿀팁 게시글 수정
    @Transactional
    public PostMutationResponse updateTipPost(Long userId, Long postId, TipPostRequest request) {
        Post post = findOwnedPost(userId, postId, PostType.TIP);
        PostCategory category = parseTipCategory(request.category());
        List<String> imageKeys = normalizeImageKeys(request.imageKeys());

        post.update(category, request.title(), request.content());

        replaceImages(postId, imageKeys);

        return PostMutationResponse.from(postId);
    }

    //뭐먹지 게시글 수정
    @Transactional
    public PostMutationResponse updateFoodPost(Long userId, Long postId, FoodPostRequest request) {
        Post post = findOwnedPost(userId, postId, PostType.FOOD_RECOMMEND);
        List<String> imageKeys = normalizeImageKeys(request.imageKeys());

        FoodPostDetail detail = foodPostDetailRepository.findByPostId(postId)
                .orElseThrow(() -> new CustomException(CommunityErrorCode.COMMUNITY_POST_NOT_FOUND));

        post.update(PostCategory.FOOD_RECOMMEND, request.title(), request.content());

        detail.update(
                request.menuName(),
                request.placeName(),
                request.price(),
                request.tasteRating(),
                request.costRating(),
                request.moodRating()
        );

        replaceImages(postId, imageKeys);

        return PostMutationResponse.from(postId);
    }

    //모집 게시글 수정
    @Transactional
    public PostMutationResponse updateRecruitPost(Long userId, Long postId, RecruitPostRequest request) {
        Post post = findOwnedPost(userId, postId, PostType.RECRUIT);
        Battle battle = findBattleFromInvitationUrl(request.battleUrl());

        RecruitPostDetail detail = recruitPostDetailRepository.findByPostId(postId)
                        .orElseThrow(() -> new CustomException(CommunityErrorCode.COMMUNITY_POST_NOT_FOUND));

        post.update(PostCategory.RECRUIT, request.title(), request.content());

        detail.update(battle.getId(), request.battleUrl());

        return PostMutationResponse.from(postId);
    }

    //게시글 삭제
    @Transactional
    public void deletePost(Long userId, Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new CustomException(CommunityErrorCode.COMMUNITY_POST_NOT_FOUND));

        if (!post.isOwnedBy(userId)) {
            throw new CustomException(CommunityErrorCode.COMMUNITY_NOT_POST_AUTHOR);
        }

        List<String> imageKeys = postImageRepository.findByPostIdOrderBySortOrderAsc(postId).stream()
                .map(PostImage::getImageKey)
                .toList();

        postCommentRepository.deleteAllByPostId(postId);
        postLikeRepository.deleteAllByPostId(postId);
        postBookmarkRepository.deleteAllByPostId(postId);
        postImageRepository.deleteAllByPostId(postId);

        if (post.getPostType() == PostType.FOOD_RECOMMEND) {
            foodPostDetailRepository.deleteById(postId);
        }

        if (post.getPostType() == PostType.RECRUIT) {
            recruitPostDetailRepository.deleteById(postId);
        }

        postRepository.delete(post);
        publishImageDeleteEvent(imageKeys);
    }

    //게시글 좋아요 토글
    @Transactional
    public PostLikeToggleResponse togglePostLike(Long userId, Long postId) {
        Post post = findPostForUpdate(postId);

        Optional<PostLike> existingLike = postLikeRepository.findByPostIdAndUserId(postId, userId);

        boolean liked;

        if (existingLike.isPresent()) {
            postLikeRepository.delete(existingLike.get());
            post.decreaseLikeCount();
            liked = false;
        } else {
            postLikeRepository.save(PostLike.create(postId, userId));
            post.increaseLikeCount();
            liked = true;
        }

        return PostLikeToggleResponse.of(postId, liked, post.getLikeCount());
    }

    //게시글 북마크 토글
    @Transactional
    public PostBookmarkToggleResponse togglePostBookmark(Long userId, Long postId) {
        findPostForUpdate(postId);

        Optional<PostBookmark> existingBookmark = postBookmarkRepository.findByPostIdAndUserId(postId, userId);

        boolean bookmarked;

        if (existingBookmark.isPresent()) {
            postBookmarkRepository.delete(existingBookmark.get());
            bookmarked = false;
        } else {
            postBookmarkRepository.save(PostBookmark.create(postId, userId));
            bookmarked = true;
        }

        return PostBookmarkToggleResponse.of(postId, bookmarked);
    }

    //댓글 또는 대댓글 작성
    @Transactional
    public CommentCreateResponse createComment(Long userId, Long postId, CommentCreateRequest request) {
        Post post = findPostForUpdate(postId);
        Long parentCommentId = request.parentCommentId();

        if (parentCommentId != null) {
            PostComment parentComment = postCommentRepository.findById(parentCommentId)
                    .orElseThrow(() -> new CustomException(CommunityErrorCode.COMMUNITY_PARENT_COMMENT_NOT_FOUND));

            if (!parentComment.getPostId().equals(postId)) {
                throw new CustomException(CommunityErrorCode.COMMUNITY_PARENT_COMMENT_POST_MISMATCH);
            }

            if (parentComment.isReply()) {
                throw new CustomException(CommunityErrorCode.COMMUNITY_COMMENT_DEPTH_EXCEEDED);
            }
        }

        PostComment comment = PostComment.create(postId, userId, parentCommentId, request.content());
        PostComment savedComment = postCommentRepository.save(comment);

        post.increaseCommentCount();

        return CommentCreateResponse.from(
                savedComment.getId(),
                savedComment.getPostId(),
                savedComment.getParentCommentId(),
                savedComment.getContent(),
                savedComment.getCreatedAt()
        );
    }

    //댓글 삭제
    @Transactional
    public void deleteComment(Long userId, Long commentId) {
        PostComment comment = postCommentRepository.findById(commentId)
                .orElseThrow(() -> new CustomException(CommunityErrorCode.COMMUNITY_COMMENT_NOT_FOUND));

        if (!comment.isOwnedBy(userId)) {
            throw new CustomException(CommunityErrorCode.COMMUNITY_NOT_COMMENT_AUTHOR);
        }

        Post post = findPostForUpdate(comment.getPostId());

        int deletedCount = postCommentRepository.markDeletedIfActive(commentId, userId);

        if (deletedCount == 1) {
            post.decreaseCommentCount();
        }
    }

    //내가 작성한 게시글 조회
    public PageResponse<PostListResponse> getMyPosts(
            Long userId,
            PostListQuery query
    ) {
        Pageable pageable = PageRequest.of(
                query.page(),
                query.size(),
                resolveSort(query.sortType())
        );
        Slice<Post> postSlice =
                postRepository.findByUserId(userId, pageable);

        return toPageResponse(postSlice, userId);
    }

    //내가 북마크한 게시글 조회
    public PageResponse<BookmarkedPostResponse> getMyBookmarks(
            Long userId,
            PostListQuery query
    ) {
        Pageable pageable = PageRequest.of(
                query.page(),
                query.size()
        );

        Slice<PostBookmark> bookmarkSlice = switch (query.sortType()) {
            case "LATEST" ->
                    postBookmarkRepository.findLatestByUserId(
                            userId,
                            pageable
                    );
            case "POPULAR" ->
                    postBookmarkRepository.findPopularByUserId(
                            userId,
                            pageable
                    );
            case "VIEW" ->
                    postBookmarkRepository.findMostViewedByUserId(
                            userId,
                            pageable
                    );
            default -> throw new CustomException(
                    CommunityErrorCode.COMMUNITY_INVALID_SORT_TYPE
            );
        };

        List<BookmarkedPostResponse> content =
                toBookmarkedPostResponses(
                        bookmarkSlice.getContent(),
                        userId
                );

        return PageResponse.of(
                content,
                bookmarkSlice.getNumber(),
                bookmarkSlice.getSize(),
                bookmarkSlice.hasNext()
        );
    }


    //공통 헬퍼

    // 작성자 표시 처리 - user row 자체가 없는 경우(방어적) 뿐 아니라, soft delete로 status만 DELETED로 바뀐 경우(user.isDeleted())도 동일하게 "탈퇴한 사용자"로 마스킹해야 한다.
    // 이전에는 author == null만 확인해서, 실제로 존재하지만 탈퇴 처리된 유저의 닉네임이 그대로 노출되는 문제가 있었다.
    private AuthorResponse toAuthorResponse(Long userId, User author) {
        return AuthorResponse.of(userId, displayName(author), profileImageUrl(author));
    }

    private String displayName(User user) {
        return user == null || user.isDeleted() ? "탈퇴한 사용자" : user.getNickname();
    }

    private String profileImageUrl(User user) {
        return user == null || user.isDeleted() ? null : user.getProfileImageUrl();
    }

    private List<Long> findAdminUserIds() {
        return userRepository.findByRole(UserRole.ADMIN).stream()
                .map(User::getId)
                .toList();
    }

    private Sort resolveSort(String sortType) {
        Sort primarySort = switch (sortType) {
            case "POPULAR" -> Sort.by(Sort.Direction.DESC, "likeCount");
            case "VIEW" -> Sort.by(Sort.Direction.DESC, "viewCount");
            case "LATEST" -> Sort.by(Sort.Direction.DESC, "createdAt");
            default -> throw new CustomException(CommunityErrorCode.COMMUNITY_INVALID_SORT_TYPE);
        };

        return primarySort.and(
                Sort.by(Sort.Direction.DESC, "id")
        );
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

        // 게시글당 sortOrder가 가장 작은 이미지 1건만 DB에서 직접 가져온다(findFirstImagesByPostIdIn)
        Map<Long, String> thumbnailByPostId = postImageRepository.findFirstImagesByPostIdIn(postIds).stream()
                .collect(Collectors.toMap(PostImage::getPostId, PostImage::getImageUrl));

        List<Long> authorIds = posts.stream().map(Post::getUserId).distinct().toList();
        Map<Long, User> authorByUserId = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

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
                        displayName(authorByUserId.get(post.getUserId())),
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
        RecruitPostDetail detail = recruitPostDetailRepository.findByPostId(postId)
                .orElse(null);

        if (detail == null) {
            return null;
        }

        Battle battle = battleRepository.findById(detail.getBattleId())
                .orElseThrow(() -> new CustomException(
                        CommunityErrorCode.COMMUNITY_BATTLE_NOT_FOUND
                ));

        int currentMemberCount =
                battleParticipantRepository.countByBattle_Id(battle.getId());

        boolean recruit = battle.getStatus() == BattleStatus.READY
                && currentMemberCount < battle.getCapacity();

        return RecruitDetailResponse.of(
                battle.getId(),
                detail.getBattleUrl(),
                battle.getTitle(),
                battle.getStartDate(),
                battle.getDurationDays(),
                battle.getCapacity(),
                currentMemberCount,
                battle.getPenalty(),
                recruit
        );
    }

    //북마크 응답 변환
    private List<BookmarkedPostResponse> toBookmarkedPostResponses(List<PostBookmark> bookmarks, Long loginUserId) {
        if (bookmarks.isEmpty()) {
            return List.of();
        }

        List<Long> postIds = bookmarks.stream()
                .map(PostBookmark::getPostId)
                .toList();

        Map<Long, Post> postById = postRepository.findAllById(postIds)
                .stream()
                .collect(Collectors.toMap(Post::getId, post -> post));

        Map<Long, String> thumbnailByPostId =
                postImageRepository.findFirstImagesByPostIdIn(postIds)
                        .stream()
                        .collect(Collectors.toMap(
                                PostImage::getPostId,
                                PostImage::getImageUrl
                        ));

        List<Long> authorIds = postById.values()
                .stream()
                .map(Post::getUserId)
                .distinct()
                .toList();

        Map<Long, User> authorByUserId =
                userRepository.findAllById(authorIds)
                        .stream()
                        .collect(Collectors.toMap(User::getId, user -> user));

        Set<Long> likedPostIds =
                postLikeRepository.findByPostIdInAndUserId(postIds, loginUserId)
                        .stream()
                        .map(PostLike::getPostId)
                        .collect(Collectors.toSet());

        return bookmarks.stream()
                .map(bookmark -> {
                    Post post = postById.get(bookmark.getPostId());
                    User author = authorByUserId.get(post.getUserId());

                    return BookmarkedPostResponse.of(
                            post.getId(),
                            post.getPostType().name(),
                            post.getCategory().name(),
                            post.getTitle(),
                            post.getContent(),
                            thumbnailByPostId.get(post.getId()),
                            displayName(author),
                            post.getCreatedAt(),
                            post.getViewCount(),
                            post.getLikeCount(),
                            post.getCommentCount(),
                            likedPostIds.contains(post.getId()),
                            true,
                            post.isOwnedBy(loginUserId),
                            bookmark.getCreatedAt()
                    );
                })
                .toList();
    }

    /**
     * 게시글의 댓글을 최상위 댓글 기준으로 페이지네이션해서 부모-자식 트리로 조립.
     * 최상위 댓글은 요청한 page/size로 실제 페이지네이션되고(더보기 요청 시 다음 페이지 조회 가능),
     * 대댓글은 최상위 댓글당 상한(MAX_REPLIES_PER_COMMENT)만 노출한다.
     * 삭제된 댓글도 content/작성자 정보를 원본 그대로 내려주고, isDeleted로 클라이언트가 표현 방식을 결정
     */
    private PageResponse<CommentResponse> buildCommentTree(Long postId, Long loginUserId, int commentPage, int commentSize) {
        Pageable pageable = PageRequest.of(commentPage, commentSize);
        Slice<PostComment> topLevelSlice = postCommentRepository.findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(postId, pageable);

        List<PostComment> topLevelComments = topLevelSlice.getContent();

        if (topLevelComments.isEmpty()) {
            return PageResponse.of(List.of(), commentPage, commentSize, false);
        }

        List<Long> topLevelCommentIds = topLevelComments.stream().map(PostComment::getId).toList();

        List<PostComment> replies =
                postCommentRepository.findRepliesWithinLimit(
                        topLevelCommentIds,
                        MAX_REPLIES_PER_COMMENT
                );

        Map<Long, Long> replyCountByParentId =
                postCommentRepository
                        .countRepliesByParentCommentIdIn(topLevelCommentIds)
                        .stream()
                        .collect(Collectors.toMap(
                                PostCommentRepository.ReplyCountView
                                        ::getParentCommentId,
                                PostCommentRepository.ReplyCountView
                                        ::getReplyCount
                        ));

        List<Long> userIds = java.util.stream.Stream.concat(
                        topLevelComments.stream().map(PostComment::getUserId),
                        replies.stream().map(PostComment::getUserId)
                )
                .distinct()
                .toList();

        Map<Long, User> userById =
                userRepository.findAllById(userIds)
                        .stream()
                        .collect(Collectors.toMap(User::getId, user -> user));

        Map<Long, List<ReplyResponse>> repliesByParentId =
                replies.stream()
                        .collect(Collectors.groupingBy(
                                PostComment::getParentCommentId,
                                Collectors.mapping(
                                        reply -> toReplyResponse(reply, userById, loginUserId),
                                        Collectors.toList()
                                )
                        ));

        List<CommentResponse> content =
                topLevelComments.stream()
                        .map(comment -> {User commentUser = userById.get(comment.getUserId());

                            List<ReplyResponse> commentReplies = repliesByParentId.getOrDefault(comment.getId(), List.of());

                            long replyCount = replyCountByParentId.getOrDefault(comment.getId(), 0L);

                            return CommentResponse.of(
                                    comment.getId(),
                                    comment.getUserId(),
                                    displayName(commentUser),
                                    profileImageUrl(commentUser),
                                    comment.getContent(),
                                    comment.isDeleted(),
                                    comment.isOwnedBy(loginUserId),
                                    comment.getCreatedAt(),
                                    commentReplies,
                                    replyCount,
                                    replyCount > commentReplies.size()
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
                displayName(user),
                profileImageUrl(user),
                reply.getContent(),
                reply.isDeleted(),
                reply.isOwnedBy(loginUserId),
                reply.getCreatedAt()
        );
    }

    //작성자와 게시글 유형 검증
    private Post findOwnedPost(Long userId, Long postId, PostType expectedType) {
        Post post = postRepository.findById(postId).orElseThrow(() -> new CustomException(CommunityErrorCode.COMMUNITY_POST_NOT_FOUND));

        if (!post.isOwnedBy(userId)) {
            throw new CustomException(CommunityErrorCode.COMMUNITY_NOT_POST_AUTHOR);
        }

        if (post.getPostType() != expectedType) {
            throw new CustomException(CommunityErrorCode.COMMUNITY_POST_TYPE_MISMATCH);
        }

        return post;
    }

    //꿀팁 카테고리 검증
    private PostCategory parseTipCategory(String categoryValue) {
        PostCategory category = parseCategory(categoryValue);

        if (category == PostCategory.FOOD_RECOMMEND || category == PostCategory.RECRUIT) {
            throw new CustomException(CommunityErrorCode.COMMUNITY_INVALID_POST_CATEGORY);
        }

        return category;
    }

    //이미지 목록 정규화
    private List<String> normalizeImageKeys(List<String> imageKeys) {
        if (imageKeys == null) {
            return List.of();
        }

        if (imageKeys.size() > 5) {
            throw new CustomException(CommunityErrorCode.COMMUNITY_IMAGE_COUNT_EXCEEDED);
        }

        if (imageKeys.stream().distinct().count() != imageKeys.size()) {
            throw new CustomException(CommunityErrorCode.COMMUNITY_DUPLICATE_IMAGE);
        }

        return List.copyOf(imageKeys);
    }

    //이미지 저장
    private void saveImages(Long postId, List<String> imageKeys) {
        if (imageKeys.isEmpty()) {
            return;
        }

        List<PostImage> images = new ArrayList<>();

        for (int index = 0; index < imageKeys.size(); index++) {
            String imageKey = imageKeys.get(index);

            images.add(PostImage.create(postId, imagePresignService.buildPublicUrl(imageKey), imageKey, index));
        }

        postImageRepository.saveAll(images);
    }

    //이미지 전체 교체
    private void replaceImages(Long postId, List<String> imageKeys) {
        List<String> existingImageKeys = postImageRepository.findByPostIdOrderBySortOrderAsc(postId).stream()
                .map(PostImage::getImageKey)
                .toList();

        Set<String> requestedImageKeys = Set.copyOf(imageKeys);

        List<String> removedImageKeys = existingImageKeys.stream()
                .filter(imageKey -> !requestedImageKeys.contains(imageKey))
                .toList();

        postImageRepository.deleteAllByPostId(postId);
        saveImages(postId, imageKeys);

        publishImageDeleteEvent(removedImageKeys);
    }

    private void publishImageDeleteEvent(List<String> imageKeys) {
        if (!imageKeys.isEmpty()) {
            eventPublisher.publishEvent(new CommunityImageDeleteEvent(imageKeys));
        }
    }

    //햄배틀 초대 URL 검증 및 Battle 조회
    private Battle findBattleFromInvitationUrl(String battleUrl) {
        String battleCode = extractBattleCode(battleUrl);

        return battleRepository.findByBattleCode(battleCode)
                .orElseThrow(() -> new CustomException(CommunityErrorCode.COMMUNITY_BATTLE_NOT_FOUND));
    }
    private String extractBattleCode(String battleUrl) {
        try {
            URI uri = URI.create(battleUrl);

            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"invite.hampouch.com".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException();
            }

            String path = uri.getPath();

            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException();
            }

            String[] segments = path.split("/");

            List<String> nonBlankSegments = java.util.Arrays.stream(segments)
                    .filter(segment -> !segment.isBlank())
                    .toList();

            if (nonBlankSegments.size() != 3
                    || !nonBlankSegments.get(0).equals("battles")
                    || !nonBlankSegments.get(1).equals("invite")) {
                throw new IllegalArgumentException();
            }

            String battleCode = nonBlankSegments.get(2);

            if (battleCode.isBlank()) {
                throw new IllegalArgumentException();
            }

            return battleCode;
        } catch (IllegalArgumentException exception) {
            throw new CustomException(CommunityErrorCode.COMMUNITY_INVALID_BATTLE_URL);
        }
    }
    private Post findPostForUpdate(Long postId) {
        return postRepository.findByIdForUpdate(postId)
                .orElseThrow(() -> new CustomException(CommunityErrorCode.COMMUNITY_POST_NOT_FOUND));
    }
}
