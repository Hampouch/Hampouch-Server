package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.Post;
import Hampouch.server.domain.community.entity.PostCategory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    //게시글 목록 - 카테고리별 (sortType은 Pageable의 Sort로 전달)
    Slice<Post> findByCategory(PostCategory category, Pageable pageable);

    //인기글 전체보기
    @Query("SELECT p FROM Post p WHERE p.likeCount >= :threshold")
    Slice<Post> findPopularPosts(@Param("threshold") int threshold, Pageable pageable);

    //홈용 인기글 - 좋아요 threshold 이상, 최신순, 상위 N개는 호출부에서 PageRequest.of(0, 5)로 제한
    @Query("SELECT p FROM Post p WHERE p.likeCount >= :threshold ORDER BY p.createdAt DESC")
    List<Post> findTopPopularPosts(@Param("threshold") int threshold, Pageable pageable);

    //포치픽 전체보기
    Slice<Post> findByUserIdIn(List<Long> adminUserIds, Pageable pageable);

    //홈용 포치픽 - 관리자 작성 게시글, 최신순 상위 N개
    List<Post> findByUserIdInOrderByCreatedAtDesc(List<Long> adminUserIds, Pageable pageable);

    //내가 쓴 글
    Slice<Post> findByUserId(Long userId, Pageable pageable);

    //게시글 전체 목록 (홈 하단) - JpaRepository의 findAll(Pageable)은 Page 반환으로 고정되어 있어 이름이 겹치면 컴파일 에러가 나므로 별도 이름(findAllPosts) 사용
    @Query("SELECT p FROM Post p")
    Slice<Post> findAllPosts(Pageable pageable);

    //조회수 증가
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :postId")
    void increaseViewCount(@Param("postId") Long postId);
}