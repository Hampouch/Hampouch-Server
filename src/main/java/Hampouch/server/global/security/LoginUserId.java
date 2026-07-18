package Hampouch.server.global.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증된 사용자의 userId를 컨트롤러 파라미터로 주입받기 위한 애노테이션.
 * 실제 파싱 로직(LoginUserIdArgumentResolver)은 JWT 인증 필터 작업 시 구현 예정.
 * 그 전까지는 @LoginUserId를 붙인 파라미터가 있는 API를 호출하면 리졸버가 없어 에러가 남 —
 * 정상적인 중간 상태이며, 필터 작업 완료 전까지는 해당 API들이 동작하지 않는 게 맞음.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUserId {
}