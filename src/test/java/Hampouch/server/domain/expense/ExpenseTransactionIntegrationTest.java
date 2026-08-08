package Hampouch.server.domain.expense;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.NoSpendRecordRequest;
import Hampouch.server.domain.expense.entity.*;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.expense.repository.NoSpendDayRepository;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ExpenseTransactionIntegrationTest {

    @Autowired
    ExpenseService expenseService;
    @Autowired
    ExpenseRepository expenseRepository;
    @Autowired
    NoSpendDayRepository noSpendDayRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ChallengeRepository challengeRepository;

    @Test
    @DisplayName("무지출 기록과 지출 생성·수정·삭제가 끝날 때마다 DB를 다시 조회해도 행 상태와 마지막 기록일이 유지된다")
    void noSpendAndExpenseChangesCommitAfterServiceCall() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate previousNoSpendDate = today.minusDays(1);
        User user = userRepository.save(User.createLocalUser(
                "expense-transaction@hampouch.test", "encoded", "지출트랜잭션"));
        Long userId = user.getId();

        expenseService.recordNoSpend(userId, new NoSpendRecordRequest(previousNoSpendDate));
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, previousNoSpendDate)).isTrue();
        assertThat(userRepository.findById(userId).orElseThrow().getLastUpdated())
                .isEqualTo(previousNoSpendDate);

        expenseService.recordNoSpend(userId, new NoSpendRecordRequest(today));
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, today)).isTrue();
        assertThat(userRepository.findById(userId).orElseThrow().getLastUpdated()).isEqualTo(today);

        ExpenseCreateResponse created = expenseService.create(userId, request("점심", 0, today));
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, today)).isFalse();
        assertThat(expenseRepository.findByIdAndStatus(created.expenseId(), ExpenseStatus.ACTIVE)).isPresent();
        assertThat(userRepository.findById(userId).orElseThrow().getLastUpdated()).isEqualTo(today);

        expenseService.recordNoSpend(userId, new NoSpendRecordRequest(today));
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, today)).isFalse();

        User reloadedUser = userRepository.findById(userId).orElseThrow();
        noSpendDayRepository.save(NoSpendDay.of(reloadedUser, today));
        expenseService.update(userId, created.expenseId(), request("저녁", 1000, today));

        Expense updated = expenseRepository.findById(created.expenseId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("저녁");
        assertThat(updated.getPrice()).isEqualTo(1000);
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, today)).isFalse();

        expenseService.delete(userId, created.expenseId());

        Expense deleted = expenseRepository.findById(created.expenseId()).orElseThrow();
        assertThat(deleted.getStatus()).isEqualTo(ExpenseStatus.DELETED);
        assertThat(userRepository.findById(userId).orElseThrow().getLastUpdated())
                .isEqualTo(previousNoSpendDate);
    }

    @Test
    @DisplayName("최종 종료된 챌린지 기간의 지출은 수정·삭제와 무지출 기록이 모두 409로 막히고, 막힌 뒤 DB의 지출 행도 그대로다")
    void closedChallengePeriodBlocksExpenseChanges() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate lockedDate = today.minusDays(3);
        User user = userRepository.save(User.createLocalUser(
                "expense-closed-challenge@hampouch.test", "encoded", "종료잠금"));
        Long userId = user.getId();
        // 지출은 서비스가 아니라 리포지토리로 심는다 — 다른 통합 테스트가 숫자 유저 id를 직접 박아 심은
        // 진행 중 챌린지와 이 유저의 발급 id가 겹칠 수 있어서, 생성 경로의 기간 검증에 결과가 좌우된다
        Expense seeded = expenseRepository.save(Expense.of(
                "점심", 8000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, lockedDate, user));

        // 잠금은 지출을 넣은 뒤에 걸린다 — 기간 중에 기록하고 끝난 뒤 종료를 누르는 실제 순서
        Challenge challenge = Challenge.builder()
                .userId(userId).durationDays(7).startDate(today.minusDays(7))
                .budgetTotal(70000).dailyLimit(10000).build();
        challenge.applyResult(ChallengeStatus.SUCCESS);
        challenge.close(LocalDateTime.now());
        challengeRepository.save(challenge);

        assertThatThrownBy(() -> expenseService.update(userId, seeded.getId(), request("저녁", 99000, lockedDate)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CHALLENGE_CLOSED);
        assertThatThrownBy(() -> expenseService.delete(userId, seeded.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CHALLENGE_CLOSED);
        assertThatThrownBy(() -> expenseService.recordNoSpend(userId, new NoSpendRecordRequest(lockedDate)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CHALLENGE_CLOSED);

        Expense untouched = expenseRepository.findById(seeded.getId()).orElseThrow();
        assertThat(untouched.getName()).isEqualTo("점심");
        assertThat(untouched.getPrice()).isEqualTo(8000);
        assertThat(untouched.getStatus()).isEqualTo(ExpenseStatus.ACTIVE);
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, lockedDate)).isFalse();

        // 잠긴 것은 그 기간뿐이라 기간 밖 날짜의 무지출 기록은 그대로 저장된다
        expenseService.recordNoSpend(userId, new NoSpendRecordRequest(today));
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(userId, today)).isTrue();
    }

    private ExpenseCreateRequest request(String name, int price, LocalDate date) {
        return new ExpenseCreateRequest(
                name,
                price,
                ExpenseCategory.CAFE,
                null,
                ExpenseEmotion.STRESS,
                null,
                date);
    }
}
