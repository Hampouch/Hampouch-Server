package Hampouch.server.domain.community.controller;

import Hampouch.server.domain.community.dto.request.PostListQuery;
import Hampouch.server.domain.community.dto.response.HomeResponse;
import Hampouch.server.domain.community.dto.response.PageResponse;
import Hampouch.server.domain.community.dto.response.PostDetailResponse;
import Hampouch.server.domain.community.dto.response.PostListResponse;
import Hampouch.server.domain.community.service.PostService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
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
    @GetMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<PostDetailResponse>> getPostDetail(
            @LoginUserId Long userId,
            @PathVariable Long postId
    ) {
        PostDetailResponse response = postService.getPostDetail(userId, postId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}