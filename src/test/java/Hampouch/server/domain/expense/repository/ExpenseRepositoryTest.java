package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.*;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.config.ClockConfig;
import Hampouch.server.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ExpenseRepository/CustomCategoryRepository/CustomEmotionRepository 파생 쿼리 + 유니크 제약을
 * H2에 실제 적용해 검증 (ChallengeDayRepositoryTest와 동일 스타일. @CreatedDate 위해 Clock·Auditing 설정 import).
 */
@DataJpaTest
@Import({ClockConfig.class, JpaAuditingConfig.class})
class ExpenseRepositoryTest {

    @Autowired
    UserRepository userRepository;
    @Autowired
    ExpenseRepository expenseRepository;
    @Autowired
    CustomCategoryRepository customCategoryRepository;
    @Autowired
    CustomEmotionRepository customEmotionRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocalUser("owner@hampouch.com", "encoded", "포치owner"));
    }

    @Test
    @DisplayName("findByIdAndStatus는 ACTIVE 행만 찾고, DELETED로 바뀐 행은 존재하지 않는 것처럼 취급한다")
    void findByIdAndStatus_excludesDeleted() {
        Expense active = expenseRepository.save(
                Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 5), user));
        Expense deleted = expenseRepository.save(
                Expense.of("배달의민족", 15000, ExpenseCategory.DELIVERY, ExpenseEmotion.COMPENSATION, LocalDate.of(2026, 6, 5), user));
        deleted.delete();
        expenseRepository.flush();

        assertThat(expenseRepository.findByIdAndStatus(active.getId(), ExpenseStatus.ACTIVE)).isPresent();
        assertThat(expenseRepository.findByIdAndStatus(deleted.getId(), ExpenseStatus.ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("findByUser_IdAndExpenseDateAndStatus는 그 유저의 그 날짜, ACTIVE 지출만 돌려준다")
    void findByUserAndDateAndStatus_filtersCorrectly() {
        LocalDate target = LocalDate.of(2026, 6, 5);
        Expense onTarget = expenseRepository.save(
                Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target, user));
        expenseRepository.save(
                Expense.of("다른 날 지출", 3000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target.plusDays(1), user));
        Expense deletedOnTarget = expenseRepository.save(
                Expense.of("삭제된 지출", 2000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target, user));
        deletedOnTarget.delete();
        expenseRepository.flush();

        List<Expense> result = expenseRepository.findByUser_IdAndExpenseDateAndStatus(
                user.getId(), target, ExpenseStatus.ACTIVE);

        assertThat(result).extracting(Expense::getId).containsExactly(onTarget.getId());
    }

    @Test
    @DisplayName("CustomCategoryRepository.findByUser_IdAndName은 find-or-create 조회 진입점으로 정상 동작한다")
    void customCategoryRepository_findsByUserAndName() {
        customCategoryRepository.save(CustomCategory.of(user, "스터디카페"));

        assertThat(customCategoryRepository.findByUser_IdAndName(user.getId(), "스터디카페")).isPresent();
        assertThat(customCategoryRepository.findByUser_IdAndName(user.getId(), "존재안함")).isEmpty();
    }

    @Test
    @DisplayName("CustomEmotionRepository.findByUser_IdAndName도 동일하게 동작한다")
    void customEmotionRepository_findsByUserAndName() {
        customEmotionRepository.save(CustomEmotion.of(user, "억울해서"));

        assertThat(customEmotionRepository.findByUser_IdAndName(user.getId(), "억울해서")).isPresent();
    }

    @Test
    @DisplayName("같은 유저가 같은 이름의 커스텀 카테고리를 두 번 저장하면 유니크 제약 위반이 터진다 (find-or-create 동시성 가드)")
    void customCategory_uniqueConstraintOnDuplicateName() {
        customCategoryRepository.saveAndFlush(CustomCategory.of(user, "스터디카페"));

        assertThatThrownBy(() -> customCategoryRepository.saveAndFlush(CustomCategory.of(user, "스터디카페")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 유저가 같은 이름의 커스텀 감정을 두 번 저장해도 동일하게 유니크 제약 위반이 터진다 — CustomCategory와 대칭 케이스")
    void customEmotion_uniqueConstraintOnDuplicateName() {
        customEmotionRepository.saveAndFlush(CustomEmotion.of(user, "억울해서"));

        assertThatThrownBy(() -> customEmotionRepository.saveAndFlush(CustomEmotion.of(user, "억울해서")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
