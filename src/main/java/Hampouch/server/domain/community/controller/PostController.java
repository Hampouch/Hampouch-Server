package Hampouch.server.domain.community.controller;

import Hampouch.server.domain.community.dto.request.*;
import Hampouch.server.domain.community.dto.response.*;
import Hampouch.server.domain.community.service.PostService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/community")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    //커뮤니티 홈 조회
    @GetMapping("/home")
    public ResponseEntity<ApiResponse<HomeResponse>> getHome(
            @LoginUserId Long userId,
            @RequestParam(required = false) String sortType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        HomeResponse response = postService.getHome(userId, PostListQuery.of(sortType, page, size));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //인기글 전체보기
    @GetMapping("/posts/popular")
    public ResponseEntity<ApiResponse<PageResponse<PostListResponse>>> getPopularPosts(
            @LoginUserId Long userId,
            @RequestParam(required = false) String sortType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<PostListResponse> response =
                postService.getPopularPosts(userId, PostListQuery.of(sortType, page, size));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //포치픽 전체보기
    @GetMapping("/posts/pochi-picks")
    public ResponseEntity<ApiResponse<PageResponse<PostListResponse>>> getPochiPicks(
            @LoginUserId Long userId,
            @RequestParam(required = false) String sortType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<PostListResponse> response =
                postService.getPochiPicks(userId, PostListQuery.of(sortType, page, size));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //게시글 목록 조회 (카테고리별)
    @GetMapping("/posts")
    public ResponseEntity<ApiResponse<PageResponse<PostListResponse>>> getPostsByCategory(
            @LoginUserId Long userId,
            @RequestParam String category,
            @RequestParam(required = false) String sortType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<PostListResponse> response =
                postService.getPostsByCategory(userId, category, PostListQuery.of(sortType, page, size));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //게시글 상세 조회
    //commentPage/commentSize: 최상위 댓글 페이지네이션 (더 보기 요청 시 commentPage를 늘려서 재호출)
    @GetMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<PostDetailResponse>> getPostDetail(
            @LoginUserId Long userId,
            @PathVariable Long postId,
            @RequestParam(defaultValue = "0") int commentPage,
            @RequestParam(defaultValue = "20") int commentSize
    ) {
        // 상세 조회의 page/size 검증은 PostListQuery 재사용 없이 여기서 직접 처리
        // (댓글 페이지네이션은 게시글 목록 페이지네이션과 별개 파라미터라 PostListQuery로 묶지 않음)
        if (commentPage < 0) {
            throw new CustomException(CommonErrorCode.VALIDATION_ERROR, "commentPage는 0 이상이어야 합니다.");
        }
        if (commentSize < 1) {
            throw new CustomException(CommonErrorCode.VALIDATION_ERROR, "commentSize는 1 이상이어야 합니다.");
        }

        PostDetailResponse response = postService.getPostDetail(userId, postId, commentPage, commentSize);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //꿀팁 게시글 작성
    @PostMapping("/posts/tips")
    public ResponseEntity<ApiResponse<PostMutationResponse>> createTipPost(
            @LoginUserId Long userId,
            @RequestBody @Valid TipPostRequest request
    ) {
        PostMutationResponse response = postService.createTipPost(userId, request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //뭐먹지 게시글 작성
    @PostMapping("/posts/foods")
    public ResponseEntity<ApiResponse<PostMutationResponse>> createFoodPost(
            @LoginUserId Long userId,
            @RequestBody @Valid FoodPostRequest request
    ) {
        PostMutationResponse response = postService.createFoodPost(userId, request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //모집 게시글 작성
    @PostMapping("/posts/recruits")
    public ResponseEntity<ApiResponse<PostMutationResponse>> createRecruitPost(
            @LoginUserId Long userId,
            @RequestBody @Valid RecruitPostRequest request
    ) {
        PostMutationResponse response = postService.createRecruitPost(userId, request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //꿀팁 게시글 수정
    @PatchMapping("/posts/tips/{postId}")
    public ResponseEntity<ApiResponse<PostMutationResponse>> updateTipPost(
            @LoginUserId Long userId,
            @PathVariable Long postId,
            @RequestBody @Valid TipPostRequest request
    ) {
        PostMutationResponse response = postService.updateTipPost(userId, postId, request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //뭐먹지 게시글 수정
    @PatchMapping("/posts/foods/{postId}")
    public ResponseEntity<ApiResponse<PostMutationResponse>> updateFoodPost(
            @LoginUserId Long userId,
            @PathVariable Long postId,
            @RequestBody @Valid FoodPostRequest request
    ) {
        PostMutationResponse response = postService.updateFoodPost(userId, postId, request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //모집 게시글 수정
    @PatchMapping("/posts/recruits/{postId}")
    public ResponseEntity<ApiResponse<PostMutationResponse>> updateRecruitPost(
            @LoginUserId Long userId,
            @PathVariable Long postId,
            @RequestBody @Valid RecruitPostRequest request
    ) {
        PostMutationResponse response = postService.updateRecruitPost(userId, postId, request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //게시글 삭제
    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<Void>> deletePost(
            @LoginUserId Long userId,
            @PathVariable Long postId
    ) {
        postService.deletePost(userId, postId);

        return ResponseEntity.ok(ApiResponse.success());
    }

    //게시글 좋아요 토글
    @PostMapping("/posts/{postId}/likes")
    public ResponseEntity<ApiResponse<PostLikeToggleResponse>> togglePostLike(
            @LoginUserId Long userId,
            @PathVariable Long postId
    ) {
        PostLikeToggleResponse response = postService.togglePostLike(userId, postId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //게시글 북마크 토글
    @PostMapping("/posts/{postId}/bookmarks")
    public ResponseEntity<ApiResponse<PostBookmarkToggleResponse>> togglePostBookmark(
            @LoginUserId Long userId,
            @PathVariable Long postId
    ) {
        PostBookmarkToggleResponse response = postService.togglePostBookmark(userId, postId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //댓글 및 대댓글 작성
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentCreateResponse>> createComment(
            @LoginUserId Long userId,
            @PathVariable Long postId,
            @RequestBody @Valid CommentCreateRequest request
    ) {
        CommentCreateResponse response = postService.createComment(userId, postId, request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //댓글 삭제
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<ApiResponse<Void>> deleteComment(
            @LoginUserId Long userId,
            @PathVariable Long commentId
    ) {
        postService.deleteComment(userId, commentId);

        return ResponseEntity.ok(ApiResponse.success());
    }

    //내가 작성한 게시글 조회
    @GetMapping("/me/posts")
    public ResponseEntity<ApiResponse<PageResponse<PostListResponse>>> getMyPosts(
            @LoginUserId Long userId,
            @RequestParam(required = false) String sortType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<PostListResponse> response =
                postService.getMyPosts(userId, PostListQuery.of(sortType, page, size));

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //내가 북마크한 게시글 조회
    @GetMapping("/me/bookmarks")
    public ResponseEntity<ApiResponse<PageResponse<BookmarkedPostResponse>>> getMyBookmarks(
            @LoginUserId Long userId,
            @RequestParam(required = false) String sortType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<BookmarkedPostResponse> response =
                postService.getMyBookmarks(userId, PostListQuery.of(sortType, page, size));

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}