# 곁 백엔드 실행 이미지
# jar는 GitHub Actions에서 먼저 빌드하고(./gradlew bootJar), 이미지는 실행만 담당합니다.

# Java 21 실행 환경(JRE)만 포함한 이미지 (JDK보다 가벼움)
FROM eclipse-temurin:21-jre

# 컨테이너 안 작업 폴더
WORKDIR /app

# 보안을 위해 root가 아닌 전용 사용자(app)로 실행
RUN groupadd --system app && useradd --system --gid app app

# Actions에서 빌드한 실행용 jar 하나를 app.jar로 복사
COPY build/libs/*.jar app.jar

# 컨테이너 시간대를 한국 시간으로 (애플리케이션도 main()에서 Asia/Seoul로 고정)
ENV TZ=Asia/Seoul
USER app

# Spring Boot 기본 포트 (외부에는 nginx가 80으로 받아서 전달)
EXPOSE 8080

# 컨테이너 메모리 제한(docker-compose의 mem_limit 1g) 중 75%까지 힙으로 사용
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
