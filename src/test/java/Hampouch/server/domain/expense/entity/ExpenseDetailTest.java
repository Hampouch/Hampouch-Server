package Hampouch.server.domain.expense.entity;

import Hampouch.server.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExpenseDetail의 of()/updateMemo()/attachImage()/removeImage() 상태 전이를 엔티티 단위로 검증.
 * "언제 생성할지"(memo/imageKey 둘 다 없으면 안 만든다)는 호출부(ExpenseService/ExpenseImageService) 책임이라
 * 여기서는 엔티티 자체의 필드 전이만 다룬다.
 * imageUrl은 더 이상 엔티티에 저장하지 않는다 — 조회 시점에 ExpenseImageService.presignGetUrl()로
 * imageKey를 매번 새 presigned GET URL로 변환해서 응답한다.
 */
class ExpenseDetailTest {

    private static User user() {
        User user = User.createLocalUser("u1@hampouch.com", "encoded", "user1");
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    private static Expense expense() {
        return Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                LocalDate.of(2026, 6, 5), user());
    }

    @Test
    @DisplayName("of()로 생성하면 memo만 채워지고 imageKey는 null이다")
    void of_startsWithMemoOnly() {
        ExpenseDetail detail = ExpenseDetail.of(expense(), "오늘 기분 좋아서");

        assertThat(detail.getMemo()).isEqualTo("오늘 기분 좋아서");
        assertThat(detail.getImageKey()).isNull();
    }

    @Test
    @DisplayName("of()에 memo로 null을 넘겨도 예외 없이 그대로 null로 저장된다 — imageKey만 있는 케이스 지원")
    void of_allowsNullMemo() {
        ExpenseDetail detail = ExpenseDetail.of(expense(), null);

        assertThat(detail.getMemo()).isNull();
    }

    @Test
    @DisplayName("updateMemo()는 memo만 바꾸고 imageKey는 건드리지 않는다")
    void updateMemo_onlyChangesMemo() {
        ExpenseDetail detail = ExpenseDetail.of(expense(), "기존 메모");
        detail.attachImage("expenses/abc.jpg");

        detail.updateMemo("바뀐 메모");

        assertThat(detail.getMemo()).isEqualTo("바뀐 메모");
        assertThat(detail.getImageKey()).isEqualTo("expenses/abc.jpg");
    }

    @Test
    @DisplayName("attachImage()는 imageKey만 바꾸고 memo는 건드리지 않는다 — updateMemo()와 대칭 케이스")
    void attachImage_onlyChangesImageKey() {
        ExpenseDetail detail = ExpenseDetail.of(expense(), "메모는 유지되어야 함");

        detail.attachImage("expenses/new.jpg");

        assertThat(detail.getImageKey()).isEqualTo("expenses/new.jpg");
        assertThat(detail.getMemo()).isEqualTo("메모는 유지되어야 함");
    }

    @Test
    @DisplayName("attachImage()를 다시 호출하면(재첨부) 이전 imageKey를 새 값으로 덮어쓴다")
    void attachImage_overwritesPreviousImage() {
        ExpenseDetail detail = ExpenseDetail.of(expense(), null);
        detail.attachImage("expenses/old.jpg");

        detail.attachImage("expenses/new.jpg");

        assertThat(detail.getImageKey()).isEqualTo("expenses/new.jpg");
    }

    @Test
    @DisplayName("removeImage()는 imageKey만 비우고 memo는 그대로 유지한다 — DELETE .../photos의 계약")
    void removeImage_clearsImageKeyButKeepsMemo() {
        ExpenseDetail detail = ExpenseDetail.of(expense(), "지우면 안 되는 메모");
        detail.attachImage("expenses/abc.jpg");

        detail.removeImage();

        assertThat(detail.getImageKey()).isNull();
        assertThat(detail.getMemo()).isEqualTo("지우면 안 되는 메모");
    }
}
