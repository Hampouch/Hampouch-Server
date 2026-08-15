package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.PostComment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

    //댓글 무제한 로드 방지: 최상위 댓글을 페이지네이션 (parentCommentId가 null)
    //Page 대신 Slice - hasNext만 필요하고 COUNT 쿼리는 불필요
    Slice<PostComment> findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAsc(Long postId, Pageable pageable);

    //위에서 조회한 최상위 댓글 id들에 대한 대댓글을 한 번에 조회 (N+1 방지, 상한은 서비스에서 자름)
    List<PostComment> findByParentCommentIdInOrderByCreatedAtAscIdAsc(List<Long> parentCommentIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PostComment pc WHERE pc.postId = :postId")
    void deleteAllByPostId(@Param("postId") Long postId);
}