package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.CustomEmotion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** CustomCategoryRepository와 동일한 find-or-create 진입점. */
public interface CustomEmotionRepository extends JpaRepository<CustomEmotion, Long> {

    Optional<CustomEmotion> findByUser_IdAndName(Long userId, String name);

    /** GET /expenses/custom-tags — 유저가 등록한 커스텀 감정 태그 전체를 등록(생성) 순서로 조회 */
    List<CustomEmotion> findAllByUser_IdOrderByCreatedAtAsc(Long userId);
}
