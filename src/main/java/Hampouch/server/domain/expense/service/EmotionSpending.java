package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.entity.ExpenseEmotion;

/**
 * Challenge 도메인이 결과 화면의 "소비 감정 분석" 그래프를 그릴 때 쓰는 조회 결과를
 * ResultResponse.EmotionRatio(String emotion, double ratio)를 이 모양으로 바꾸자는 제안을 코드로 적어둔 것.
 * Challenge 도메인 파일은 이 브랜치에서 건드리지 않으므로, 실제 교체는 Challenge 담당이 한다.
 * 교체 전까지 ResultResponse는 지금처럼 빈 배열을 유지하면 되고 컴파일에 영향이 없다.
 * 1. emotion이 String -> ExpenseEmotion.
 *    emotion은 Enum 형태로 저장되어 있으므로 data의 정확성을 위해 Enum type으로 Entity 설정과 통일한다.
 *    분석 API가 enum을 그대로 내보내므로 여기만 String이면 같은 데이터가 두 화면에서 다른 표현으로 나간다.
 * 2. ratio가 double(0~1) -> int(정수 퍼센트).
 *    화면이 표시하는 값 자체가 정수 퍼센트다. double로 두면 우리가 넘길 수 있는 값이 어차피 42 / 100.0 = 0.42라
 *    소수점 둘째 자리까지밖에 못 담으면서 타입만 더 정밀해 보인다.
 * 3. amount 추가.
 *    ratio가 정수 퍼센트면 반올림 때문에 합이 99나 101이 될 수 있다. 도넛 각도를 ratio로 누적해 그리면
 *    마지막 조각이 모자라거나 넘치는데, 지금 EmotionRatio에는 amount가 없어 그 회피로(각도는 amount로 계산)를
 *    쓸 수가 없다. CategoryAmount에는 amount가 있으니 대칭을 맞추는 셈이기도 하다.
 * ratio의 분모는 "그 챌린지 기간의 총 지출액"이며 분석 화면 도넛과 동일
 */
public record EmotionSpending(
        ExpenseEmotion emotion,
        int amount,
        int ratio
) {
}
