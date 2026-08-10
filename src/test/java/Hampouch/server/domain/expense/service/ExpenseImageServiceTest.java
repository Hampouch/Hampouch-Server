package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.dto.ExpenseImagePresignRequest;
import Hampouch.server.domain.expense.dto.ExpenseImagePresignResponse;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseDetail;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.ExpenseDetailRepository;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.net.URI;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ExpenseImageService 단위 테스트. S3Presigner/S3Client는 Mockito 목 — 실제 S3 호출 없음.
 * bucket/region은 @Value로 주입되는 필드라 ReflectionTestUtils로 직접 채운다(Spring 컨텍스트 없이 순수 단위 테스트).
 */
@ExtendWith(MockitoExtension.class)
class ExpenseImageServiceTest {

    private static final Long OWNER = 1L;
    private static final Long OTHER = 2L;

    @Mock
    S3Presigner s3Presigner;
    @Mock
    S3Client s3Client;
    @Mock
    ExpenseRepository expenseRepository;
    @Mock
    ExpenseDetailRepository expenseDetailRepository;
    @Mock
    PresignedPutObjectRequest presignedPutObjectRequest;

    private ExpenseImageService service() {
        ExpenseImageService service = new ExpenseImageService(s3Presigner, s3Client, expenseRepository, expenseDetailRepository);
        ReflectionTestUtils.setField(service, "bucket", "hampouch-bucket");
        ReflectionTestUtils.setField(service, "region", "ap-northeast-2");
        return service;
    }

    private static User user(Long id) {
        User user = User.createLocalUser("u" + id + "@hampouch.com", "encoded", "user" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Expense expenseOf(Long ownerId) {
        return Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                LocalDate.of(2026, 6, 5), user(ownerId));
    }

    // ---------- presign ----------

    @Test
    @DisplayName("expenseId 없이 presign하면 소유권 확인 없이 userId를 key 접두어로 심은 imageKey/uploadUrl/expiresInSeconds(600)를 발급한다")
    void presign_withoutExpenseIdSkipsOwnershipCheck() throws Exception {
        when(s3Presigner.presignPutObject(any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class)))
                .thenReturn(presignedPutObjectRequest);
        when(presignedPutObjectRequest.url()).thenReturn(new URI("https://hampouch-bucket.s3.ap-northeast-2.amazonaws.com/expenses/1/abc.jpg?X-Amz-Signature=xxx").toURL());

        var req = new ExpenseImagePresignRequest("image/jpeg", 1000L);
        ExpenseImagePresignResponse res = service().presign(OWNER, null, req);

        assertThat(res.imageKey()).startsWith("expenses/" + OWNER + "/").endsWith(".jpg");
        assertThat(res.uploadUrl()).contains("hampouch-bucket");
        assertThat(res.expiresInSeconds()).isEqualTo(600);
        verify(expenseRepository, never()).findByIdAndStatus(any(), any());
    }

    @Test
    @DisplayName("expenseId가 있으면 presign 전에 소유권을 확인하고, 남의 지출이면 403(EXPENSE_FORBIDDEN)을 던진다")
    void presign_withExpenseIdRejectsWhenNotOwner() {
        Expense expense = expenseOf(OTHER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        var req = new ExpenseImagePresignRequest("image/jpeg", 1000L);

        assertThatThrownBy(() -> service().presign(OWNER, 1L, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_FORBIDDEN);
        verifyNoInteractions(s3Presigner);
    }

    @Test
    @DisplayName("expenseId로 존재하지 않는 지출을 지정하면 404(EXPENSE_NOT_FOUND)를 던진다")
    void presign_withExpenseIdNotFound() {
        when(expenseRepository.findByIdAndStatus(99L, ExpenseStatus.ACTIVE)).thenReturn(Optional.empty());

        var req = new ExpenseImagePresignRequest("image/jpeg", 1000L);

        assertThatThrownBy(() -> service().presign(OWNER, 99L, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_NOT_FOUND);
    }

    @Test
    @DisplayName("파일 크기가 10MB를 초과하면 400(EXPENSE_IMAGE_SIZE_EXCEEDED)을 던지고 S3는 호출하지 않는다")
    void presign_rejectsFileOverSizeLimit() {
        var req = new ExpenseImagePresignRequest("image/jpeg", 10 * 1024 * 1024 + 1L);

        assertThatThrownBy(() -> service().presign(OWNER, null, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_IMAGE_SIZE_EXCEEDED);
        verifyNoInteractions(s3Presigner);
    }

    @Test
    @DisplayName("S3Presigner가 예상 못한 이유로 실패하면 500(EXPENSE_IMAGE_UPLOAD_FAILED)으로 감싸 던진다")
    void presign_wrapsUnexpectedPresignerFailure() {
        when(s3Presigner.presignPutObject(any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class)))
                .thenThrow(new RuntimeException("boom"));

        var req = new ExpenseImagePresignRequest("image/jpeg", 1000L);

        assertThatThrownBy(() -> service().presign(OWNER, null, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_IMAGE_UPLOAD_FAILED);
    }

    // ---------- resolveImageUrl ----------

    @Test
    @DisplayName("imageKey가 이 userId 접두어로 시작하고 HeadObject도 성공하면 공개 조회 URL을 만들어 반환한다")
    void resolveImageUrl_returnsPublicUrlWhenOwnedAndHeadObjectSucceeds() {
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        String url = service().resolveImageUrl(OWNER, "expenses/" + OWNER + "/abc.jpg");

        assertThat(url).isEqualTo("https://hampouch-bucket.s3.ap-northeast-2.amazonaws.com/expenses/" + OWNER + "/abc.jpg");
    }

    @Test
    @DisplayName("HeadObject가 NoSuchKeyException을 던지면(업로드 안 됨) 400(EXPENSE_IMAGE_NOT_UPLOADED)으로 변환한다")
    void resolveImageUrl_throwsWhenNotUploaded() {
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        assertThatThrownBy(() -> service().resolveImageUrl(OWNER, "expenses/" + OWNER + "/missing.jpg"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_IMAGE_NOT_UPLOADED);
    }

    @Test
    @DisplayName("imageKey가 다른 userId 접두어면 S3는 확인하지도 않고 403(EXPENSE_IMAGE_KEY_FORBIDDEN)을 던진다(#4)")
    void resolveImageUrl_throwsWhenKeyOwnedByAnotherUser() {
        assertThatThrownBy(() -> service().resolveImageUrl(OWNER, "expenses/" + OTHER + "/abc.jpg"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_IMAGE_KEY_FORBIDDEN);
        verifyNoInteractions(s3Client);
    }

    // ---------- attach ----------

    @Test
    @DisplayName("ExpenseDetail이 없으면 새로 생성해서 imageKey/imageUrl을 채운다 — get-or-create")
    void attach_createsDetailWhenAbsent() {
        Expense expense = expenseOf(OWNER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.empty());
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());
        ArgumentCaptor<ExpenseDetail> captor = ArgumentCaptor.forClass(ExpenseDetail.class);
        when(expenseDetailRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service().attach(OWNER, 1L, "expenses/" + OWNER + "/abc.jpg");

        assertThat(captor.getValue().getImageKey()).isEqualTo("expenses/" + OWNER + "/abc.jpg");
    }

    @Test
    @DisplayName("ExpenseDetail이 이미 있으면 새로 저장하지 않고 기존 행을 갱신한다")
    void attach_updatesExistingDetailWithoutSaving() {
        Expense expense = expenseOf(OWNER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        ExpenseDetail existing = ExpenseDetail.of(expense, "기존 메모");
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.of(existing));
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        service().attach(OWNER, 1L, "expenses/" + OWNER + "/new.jpg");

        assertThat(existing.getImageKey()).isEqualTo("expenses/" + OWNER + "/new.jpg");
        assertThat(existing.getMemo()).isEqualTo("기존 메모"); // memo는 그대로 유지
        verify(expenseDetailRepository, never()).save(any());
    }

    @Test
    @DisplayName("기존에 다른 이미지가 붙어있었다면 교체 후 그 옛 S3 객체를 삭제한다")
    void attach_deletesOldS3ObjectWhenReplacingExistingImage() {
        Expense expense = expenseOf(OWNER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        ExpenseDetail existing = ExpenseDetail.of(expense, null);
        existing.attachImage("expenses/" + OWNER + "/old.jpg", "https://hampouch-bucket.s3.ap-northeast-2.amazonaws.com/expenses/" + OWNER + "/old.jpg");
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.of(existing));
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        service().attach(OWNER, 1L, "expenses/" + OWNER + "/new.jpg");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().key()).isEqualTo("expenses/" + OWNER + "/old.jpg");
    }

    @Test
    @DisplayName("이미지가 처음 붙는 경우엔(옛 이미지 없음) S3 delete를 호출하지 않는다(#3)")
    void attach_skipsS3DeleteWhenNoPreviousImage() {
        Expense expense = expenseOf(OWNER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.empty());
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());
        when(expenseDetailRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().attach(OWNER, 1L, "expenses/" + OWNER + "/abc.jpg");

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("다른 유저 접두어의 imageKey를 붙이려 하면 403(EXPENSE_IMAGE_KEY_FORBIDDEN)을 던진다(#4)")
    void attach_forbiddenWhenImageKeyOwnedByAnotherUser() {
        Expense expense = expenseOf(OWNER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        assertThatThrownBy(() -> service().attach(OWNER, 1L, "expenses/" + OTHER + "/abc.jpg"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_IMAGE_KEY_FORBIDDEN);
        verify(expenseDetailRepository, never()).findByExpenseId(any());
    }

    // ---------- remove ----------

    @Test
    @DisplayName("ExpenseDetail이 있으면 removeImage()가 호출되고 S3 객체도 삭제된다")
    void remove_clearsImageAndDeletesS3ObjectWhenDetailPresent() {
        Expense expense = expenseOf(OWNER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        ExpenseDetail existing = ExpenseDetail.of(expense, null);
        existing.attachImage("expenses/" + OWNER + "/abc.jpg", "https://hampouch-bucket.s3.ap-northeast-2.amazonaws.com/expenses/" + OWNER + "/abc.jpg");
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.of(existing));

        service().remove(OWNER, 1L);

        assertThat(existing.getImageKey()).isNull();
        assertThat(existing.getImageUrl()).isNull();
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().key()).isEqualTo("expenses/" + OWNER + "/abc.jpg");
    }

    @Test
    @DisplayName("S3 객체 삭제가 실패해도 예외를 삼키고 DB 필드는 그대로 비워진 채 성공 처리한다")
    void remove_swallowsS3DeleteFailure() {
        Expense expense = expenseOf(OWNER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        ExpenseDetail existing = ExpenseDetail.of(expense, null);
        existing.attachImage("expenses/" + OWNER + "/abc.jpg", "https://hampouch-bucket.s3.ap-northeast-2.amazonaws.com/expenses/" + OWNER + "/abc.jpg");
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.of(existing));
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(new RuntimeException("S3 boom"));

        service().remove(OWNER, 1L); // 예외 전파 없이 끝나야 함

        assertThat(existing.getImageKey()).isNull();
    }

    @Test
    @DisplayName("ExpenseDetail은 있지만 이미지가 없었으면(memo만 있던 경우) S3는 호출하지 않는다(#3)")
    void remove_skipsS3DeleteWhenNoImageWasAttached() {
        Expense expense = expenseOf(OWNER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        ExpenseDetail existing = ExpenseDetail.of(expense, "메모만 있음");
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.of(existing));

        service().remove(OWNER, 1L);

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("ExpenseDetail 자체가 없으면(애초에 메모도 이미지도 없던 지출) 아무 것도 하지 않고 예외 없이 끝난다 — 멱등")
    void remove_noOpWhenDetailAbsent() {
        Expense expense = expenseOf(OWNER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.empty());

        service().remove(OWNER, 1L);

        verify(expenseDetailRepository, never()).save(any());
        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("남의 지출의 사진을 삭제하려 하면 403(EXPENSE_FORBIDDEN)을 던진다")
    void remove_forbiddenWhenNotOwner() {
        Expense expense = expenseOf(OTHER);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        assertThatThrownBy(() -> service().remove(OWNER, 1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_FORBIDDEN);
        verify(expenseDetailRepository, never()).findByExpenseId(any());
    }
}
