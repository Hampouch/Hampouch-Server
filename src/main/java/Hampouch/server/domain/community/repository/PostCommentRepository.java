package Hampouch.server.domain.community.repository;

import Hampouch.server.domain.community.entity.PostComment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostCommentRepository
        extends JpaRepository<PostComment, Long> {

    Slice<PostComment>
    findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAscIdAsc(
            Long postId,
            Pageable pageable
    );

    //댓글을 중복 삭제하더라도 commentCount가 여러번 감소 x
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE PostComment pc
            SET pc.deleted = true
            WHERE pc.id = :commentId
            AND pc.userId = :userId
            AND pc.deleted = false
            """)
    int markDeletedIfActive(@Param("commentId") Long commentId, @Param("userId") Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PostComment pc WHERE pc.postId = :postId")
    void deleteAllByPostId(@Param("postId") Long postId);

    @Query(
            value = """
                    SELECT ranked.comment_id,
                           ranked.post_id,
                           ranked.user_id,
                           ranked.parent_comment_id,
                           ranked.content,
                           ranked.is_deleted,
                           ranked.created_at,
                           ranked.updated_at
                    FROM (
                        SELECT pc.comment_id,
                               pc.post_id,
                               pc.user_id,
                               pc.parent_comment_id,
                               pc.content,
                               pc.is_deleted,
                               pc.created_at,
                               pc.updated_at,
                               ROW_NUMBER() OVER (
                                   PARTITION BY pc.parent_comment_id
                                   ORDER BY pc.created_at ASC,
                                            pc.comment_id ASC
                               ) AS row_number_in_parent
                        FROM post_comment pc
                        WHERE pc.parent_comment_id
                              IN (:parentCommentIds)
                    ) ranked
                    WHERE ranked.row_number_in_parent
                          <= :maxReplies
                    ORDER BY ranked.parent_comment_id ASC,
                             ranked.created_at ASC,
                             ranked.comment_id ASC
                    """,
            nativeQuery = true
    )
    List<PostComment> findRepliesWithinLimit(
            @Param("parentCommentIds")
            List<Long> parentCommentIds,

            @Param("maxReplies")
            int maxReplies
    );

    @Query("""
            SELECT p.parentCommentId AS parentCommentId,
                   COUNT(p.id) AS replyCount
            FROM PostComment p
            WHERE p.parentCommentId IN :parentCommentIds
            GROUP BY p.parentCommentId
            """)
    List<ReplyCountView> countRepliesByParentCommentIdIn(
            @Param("parentCommentIds")
            List<Long> parentCommentIds
    );

    interface ReplyCountView {
        Long getParentCommentId();

        long getReplyCount();
    }
}
