#-- 빌드용 이미지 --
#JDK가 포함된 java 21 이미지를 사용해서 프로젝트 빌드
FROM eclipse-temurin:25-jdk AS build
#컨테이너 내부 작업 디렉토리를 /app으로 설정
WORKDIR /app

#gradle wrapper 실행 파일을 컨테이너로 복사
COPY gradlew .
#gradle wrapper가 사용하는 gradle 폴더를 컨테이너로 복사
COPY gradle gradle
#gradle 빌드 설정 파일들을 컨테이너로 복사
COPY build.gradle settings.gradle ./
#실제 소스코드를 컨테이너로 복사
COPY src src

#gradlew 파일에 실행 권한을 부여
RUN chmod +x gradlew
#실행 가능한 jar 파일 생성
# clean은 기존 빌드 결과 삭제, bootJar는 실행 가능한 jar 생성, --no-daemon은 CI/Docker 환경에서 Gradle 데몬을 쓰지 않도록 하는 옵션
RUN ./gradlew clean bootJar --no-daemon

#-- 실행용 이미지 --
#빌드에는 JDK가 필요하지만 실행에는 JRE만 있으면 되므로 더 가벼운 JRE 이미지를 사용
FROM eclipse-temurin:25-jre
#실행용 컨테이너의 작업 디렉토리를 /app으로 설정
WORKDIR /app

#빌드 단계에서 생성된 jar 파일을 실행용 이미지로 복사(복사하면서 이름을 app.jar로 바꿈)
COPY --from=build /app/build/libs/*.jar app.jar

#이 컨테이너가 8080 포트를 사용한다는 것을 명시
#실제 포트 연결은 docker-compose.yml이나 docker run -p 옵션에서 설정될 예정
EXPOSE 8080

#컨테이너가 실행될 때 Spring Boot jar 파일을 실행
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]