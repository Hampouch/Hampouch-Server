package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.CustomEmotion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** CustomCategoryRepository와 동일한 find-or-create 진입점. */
public interface CustomEmotionRepository extends JpaRepository<CustomEmotion, Long> {

    Optional<CustomEmotion> findByUser_IdAndName(Long userId, String name);
}
