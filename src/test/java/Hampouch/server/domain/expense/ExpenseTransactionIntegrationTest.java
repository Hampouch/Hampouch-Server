package Hampouch.server.domain.expense;

import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.NoSpendRecordRequest;
import Hampouch.server.domain.expense.entity.*;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.expense.repository.NoSpendDayRepository;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

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
