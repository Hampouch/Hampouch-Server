package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.PostImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PostImageRepository extends JpaRepository<PostImage, Long> {

    List<PostImage> findByPostIdOrderBySortOrderAsc(Long postId);

    //목록 조회 시 썸네일(첫 번째 이미지)만 필요한 경우를 위한 postId 여러 개를 한 번에 조회(N+1 방지)
    List<PostImage> findByPostIdInOrderByPostIdAscSortOrderAsc(List<Long> postIds);

    void deleteByPostId(Long postId);
}