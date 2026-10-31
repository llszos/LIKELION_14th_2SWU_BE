# LIKELION_14th_2SWU_BE

멋쟁이사자처럼 대학 14기 슈멋사 프로젝트  
2팀(2SWU) Backend Repository

---

## 로컬 실행 방법

### 준비
- JDK 21
- Docker Desktop (실행 중)
    - macOS는 칩에 맞는 버전 설치 (Apple Silicon / Intel)

### 1. 로컬 DB 실행 (최초 1회)
```
docker run --name gyeot-mysql -e MYSQL_ROOT_PASSWORD=gyeot1234 -e MYSQL_DATABASE=gyeot -e TZ=Asia/Seoul -p 127.0.0.1:3307:3306 -v gyeot-mysql-data:/var/lib/mysql -d mysql:8.4
```
재부팅 후에는 `docker start gyeot-mysql`

### 2. 실행

JWT 서명 키는 저장소에 올리지 않고 환경 변수로 전달합니다. Git Bash에서는 실행 전에 32바이트 이상의 임의 키를 생성하세요. `.env.example`은 참고용이며 자동으로 읽히지 않습니다.
```
export JWT_SECRET="$(openssl rand -base64 32)"
```

IntelliJ에서는 **Run/Debug Configurations → Environment variables**에 `JWT_SECRET`과 생성한 값을 등록하세요. 팀원별 로컬 키를 사용하면 되고 키를 공유할 필요는 없습니다.

```
# macOS / Linux
./gradlew bootRun

# Windows (PowerShell)
.\gradlew bootRun
```
IntelliJ에서는 `GyeotApplication` 실행 버튼(▶)으로 실행해도 됩니다.

Swagger: http://localhost:8080/swagger-ui/index.html

### 3. 테스트
```
# macOS / Linux
./gradlew test

# Windows (PowerShell)
.\gradlew test
```
Testcontainers가 MySQL 8.4를 자동으로 띄웁니다. (Docker 필요)

### 참고
- macOS에서 `permission denied`가 나오면 `chmod +x gradlew` 실행 후 다시 시도해 주세요.
