# 개발 환경 안내

[README로 돌아가기](../README.md)

JDK·DB·실행·테스트·인증 환경을 준비하는 상세 안내입니다. 명령은 저장소 최상위에서 실행합니다.

## 개발 환경 준비

- JDK 21을 설치하고 IntelliJ의 **Project SDK와 Gradle JVM을 모두 JDK 21**로 설정합니다.
- 저장소를 Clone한 뒤 Gradle을 동기화합니다. 별도로 Spring Initializr 프로젝트를 만들 필요는 없습니다.
- Docker Desktop을 설치하고 실행합니다. macOS는 Apple Silicon·Intel 환경에 맞는 버전을 사용합니다.
- 개인 PC의 별도 Gradle 대신 저장소의 Wrapper를 사용합니다.
- Lombok 오류가 있으면 Gradle 동기화와 IDE의 Lombok·Annotation Processing 설정을 확인합니다.

```text
java -version
javac -version
docker version
```

`java`, `javac`는 21인지, Docker는 서버에 연결되는지 확인합니다. 아래 명령은 저장소 최상위 폴더에서 실행합니다.

## 로컬 실행

### 1. DB 기본값 또는 사용자 지정 비밀번호 선택

local은 `application-local.yaml`의 DB URL·계정·비밀번호 기본값을 사용할 수 있습니다. 이미 기본값으로 만든 컨테이너는 `DB_PASSWORD` 없이 실행할 수 있습니다. 다른 비밀번호로 DB를 만들었다면 생성 당시 값으로 설정합니다. 빈 환경변수는 기본값 사용과 다르므로 사용하지 않을 변수는 비워 넣지 말고 설정하지 않습니다.

아래 비밀번호 입력은 새 컨테이너 생성 또는 기본값과 다른 DB를 사용할 때만 필요합니다. 새 컨테이너를 기본값으로 만들려면 설정 파일의 로컬 기본 비밀번호를 입력하고, 사용자 지정 값을 입력했다면 같은 환경변수를 서버에도 전달합니다. DB 생성과 서버 실행은 같은 터미널에서 진행합니다.

**Windows PowerShell**

```powershell
$localDbPassword = Read-Host '로컬 DB 비밀번호' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $localDbPassword).Password
```

**macOS / Linux — Bash 또는 Zsh**

```bash
printf '로컬 DB 비밀번호: '
IFS= read -r -s DB_PASSWORD
printf '\n'
export DB_PASSWORD
```

비밀번호는 비어 있지 않게 입력합니다. 이 설정은 현재 터미널과 여기서 실행한 프로세스에 적용됩니다. IntelliJ 실행 버튼을 사용할 때는 Run Configuration의 환경변수에도 같은 `DB_PASSWORD`를 설정해야 합니다.

### 2. 로컬 DB 생성 — 최초 1회

**Windows PowerShell**

```powershell
docker run --name gyeot-mysql --env "MYSQL_ROOT_PASSWORD=$env:DB_PASSWORD" --env MYSQL_DATABASE=gyeot --env TZ=Asia/Seoul -p 127.0.0.1:3307:3306 -v gyeot-mysql-data:/var/lib/mysql -d mysql:8.4
```

**macOS / Linux — Bash 또는 Zsh**

```bash
docker run --name gyeot-mysql --env "MYSQL_ROOT_PASSWORD=$DB_PASSWORD" --env MYSQL_DATABASE=gyeot --env TZ=Asia/Seoul -p 127.0.0.1:3307:3306 -v gyeot-mysql-data:/var/lib/mysql -d mysql:8.4
```

호스트 포트는 기존 로컬 설정과 같은 `3307`을 사용하며, 컨테이너 데이터는 `gyeot-mysql-data` 볼륨에 보관합니다. MySQL이 연결을 받을 준비가 된 뒤 서버를 실행합니다.

컨테이너를 이미 만든 경우 다시 생성하지 않습니다. 재부팅 후에는 다음 명령으로 시작합니다.

```text
docker start gyeot-mysql
```

기존 컨테이너·볼륨을 사용할 때는 DB 생성 당시의 비밀번호를 입력해야 합니다. 환경변수 값을 바꾸는 것만으로 기존 DB 비밀번호가 변경되지는 않습니다. 문제 해결을 위해 기존 데이터나 볼륨을 임의로 삭제하지 않습니다.

### 3. 서버 실행

**Windows PowerShell**

```powershell
.\gradlew.bat bootRun
```

**macOS / Linux**

```bash
./gradlew bootRun
```

IntelliJ에서는 `GyeotApplication` 실행 버튼으로 시작할 수 있습니다. 로컬 DB와 실행 환경변수가 먼저 준비되어 있어야 합니다.

- 서버 시작 로그와 포트를 확인합니다.
- 기본 로컬 Swagger 경로: [Swagger UI](http://localhost:8080/swagger-ui/index.html)
- 초기 상태에는 루트 경로에서 404가 나와도 서버가 정상 실행 중일 수 있습니다. 아직 해당 Endpoint가 없기 때문입니다.
- macOS/Linux에서 Wrapper 실행 권한 오류가 발생하면 `chmod +x gradlew`를 실행한 뒤 다시 시도합니다.

## 환경변수와 프로필

변수 이름은 [.env.example](../.env.example)을 참고합니다. 실제 비밀번호·Secret·운영 접속값은 README, GitHub, Notion에 기록하지 않습니다.

| 변수 | 용도 | 현재 코드 기준 |
| --- | --- | --- |
| `DB_URL` | DB 연결 URL | local은 기본값 사용 가능, prod는 필수 |
| `DB_USERNAME` | DB 계정 | local은 기본값 사용 가능, prod는 필수 |
| `DB_PASSWORD` | DB 비밀번호 | local은 application-local.yaml 기본값 사용 가능. 다른 값으로 DB를 만든 경우 직접 설정. prod는 필수 |
| `JWT_SECRET` | JWT 서명 키 | #33 적용 후 필수. 아래 인증 환경변수 절 참고 |
| `INVITATION_BASE_URL` | 초대 링크의 프론트 기본 경로 (`.../invitations`) | local은 `http://localhost:5173/invitations`, prod는 필수 |

**`.env` 파일은 자동으로 로드되지 않습니다.** 파일을 복사하는 것만으로 설정이 적용되지 않으므로, 터미널 환경변수 또는 IntelliJ Run Configuration에서 주입합니다. `.env`·`application-secret.yaml`은 Git 제외 대상입니다.

| 설정 파일 | 용도 | 스키마 관리 |
| --- | --- | --- |
| [application.yaml](../src/main/resources/application.yaml) | 공통 설정, 기본 프로필 `local` | — |
| [application-local.yaml](../src/main/resources/application-local.yaml) | 로컬 개발 | `ddl-auto: update` |
| [application-test.yaml](../src/main/resources/application-test.yaml) | 자동 테스트 | `ddl-auto: create-drop` |
| [application-prod.yaml](../src/main/resources/application-prod.yaml) | 배포 환경 | `ddl-auto: validate` |

테스트 DB 연결은 Testcontainers가 제공합니다. 운영은 `validate`이므로 테이블을 자동 생성하지 않으며, 운영 스키마 준비는 팀의 배포 절차를 따릅니다.

서비스 시각 기준은 `Asia/Seoul`입니다. 날짜는 `DATE`, 시각은 `DATETIME(6)`을 기준으로 설계하며, JVM·Hibernate·테스트에도 한국 시간대 설정이 있습니다.

## 빌드와 테스트

| 목적 | Windows PowerShell | macOS / Linux |
| --- | --- | --- |
| 테스트 실행 | `.\gradlew.bat test` | `./gradlew test` |
| 테스트 포함 전체 빌드 | `.\gradlew.bat build` | `./gradlew build` |
| 테스트 제외 빌드 | `.\gradlew.bat build -x test` | `./gradlew build -x test` |

현재 `GyeotApplicationTests`는 Testcontainers로 MySQL 8.4를 실행하므로 **Docker가 필요합니다**. 개발용 DB 컨테이너와 별도로 테스트용 컨테이너를 생성합니다.

`Could not find a valid Docker environment`가 나오면 Docker 설치·실행 및 연결 상태를 확인합니다. 개발용 MySQL을 시작하는 것만으로 Testcontainers 실행 조건이 충족되는 것은 아닙니다.

`build -x test`는 컴파일·JAR 생성을 확인하는 명령이며, **테스트를 통과했다는 의미가 아닙니다**. 전체 검증에는 Docker가 준비된 환경에서 `build`를 실행해야 합니다. 실제 확인한 결과만 PR에 기록합니다.

CI는 [ci.yaml](../.github/workflows/ci.yaml)에 정의되어 있으며 `develop`·`main` 대상 PR과 `develop` Push에서 `./gradlew build`를 실행합니다.


## 인증 환경변수: PR #33 적용 후

이 절은 [PR #33](https://github.com/llszos/LIKELION_14th_2SWU_BE/pull/33)의 검토 커밋 `caa373bd85a72789a6806e93e66d05c3f71c4216` 기준입니다. #25만 적용된 브랜치에는 인증 설정·문서가 아직 없을 수 있습니다. #33 적용 후에는 다음 조건을 충족해야 합니다.

### JWT 키 준비

`JWT_SECRET`은 Base64 인코딩한 32바이트 이상 난수 키입니다. 누락·빈 값·잘못된 Base64·너무 짧은 키이면 서버 시작에 실패합니다. 아래 명령은 키를 화면에 출력하지 않고 현재 터미널에만 설정합니다.

**Windows PowerShell**

```powershell
$jwtKeyBytes = New-Object byte[] 32
$jwtKeyGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $jwtKeyGenerator.GetBytes($jwtKeyBytes)
    $env:JWT_SECRET = [Convert]::ToBase64String($jwtKeyBytes)
} finally {
    $jwtKeyGenerator.Dispose()
    [Array]::Clear($jwtKeyBytes, 0, $jwtKeyBytes.Length)
}
```

**macOS / Linux — Bash 또는 Zsh, OpenSSL 필요**

```bash
export JWT_SECRET="$(openssl rand -base64 32)"
```

서버 재시작 때도 같은 키를 사용하려면 개인 Secret 저장소·IDE 환경변수에서 관리합니다. 매번 새 키를 생성하면 기존 토큰을 검증할 수 없습니다. 팀원별 로컬 키를 사용하며 키를 공유하지 않습니다. IntelliJ 실행 시 Run/Debug Configurations → Environment variables에 `JWT_SECRET`을 설정합니다.

### JWT·쿠키·CORS 설정

| 변수 | #33 기본값 | 설명 |
| --- | --- | --- |
| `JWT_SECRET` | 없음 | 필수 비밀 키 |
| `JWT_ISSUER` | gyeot | 토큰 발급자 |
| `JWT_ACCESS_TTL` | PT15M | Access Token 유효기간 |
| `JWT_REFRESH_TTL` | P30D | Refresh Token 최초 만료 기간 |
| `REFRESH_COOKIE_NAME` | refresh_token | Refresh 쿠키 이름 |
| `REFRESH_COOKIE_PATH` | /api/auth | Refresh 쿠키 경로 |
| `COOKIE_DOMAIN` | 빈 값 | 미설정 시 host-only. 배포 도메인 기준 설정 |
| `COOKIE_SECURE` | local false / prod true | HTTPS 운영 환경에서는 true |
| `COOKIE_SAME_SITE` | Lax | 배포 구조에 맞게 결정. cross-site 쿠키의 None 설정에는 Secure 필요 |
| `CORS_ALLOWED_ORIGINS` | local http://localhost:3000 / prod 기본값 없음 | 허용할 프론트 origin. prod에서는 반드시 주입 |

#33의 쿠키 인증 요청에는 브라우저의 `credentials: "include"`와 CSRF 연동이 필요합니다. Access Token은 메모리에 보관하고 Refresh Token은 HttpOnly 쿠키로 전달하며 프론트에서 읽거나 저장하지 않습니다. CORS에는 실제 프론트 origin을 지정하고 운영 주소·쿠키 속성을 배포 구조와 함께 확인합니다.

#33의 `.env.example`에는 `COOKIE_SECURE=false`가 있으므로 운영에 그대로 적용하지 않습니다. prod 설정의 true 기본값도 환경변수 false가 있으면 덮어써집니다.

[인증 연동 원문](https://github.com/llszos/LIKELION_14th_2SWU_BE/blob/caa373bd85a72789a6806e93e66d05c3f71c4216/docs/authentication.md): 로그인 사용자 ID, Bearer 토큰, CSRF 토큰, 쿠키, 재발급·로그아웃 안내. #33 병합 후 저장소의 `docs/authentication.md` 링크로 전환합니다.

### #25·#33 병합 시 문서 확인

두 PR이 README의 같은 실행 안내 부분을 변경하므로 충돌 해결 시 아래 항목을 모두 유지합니다.

- README는 소개와 실행 요약을 유지하고 이 문서를 연결합니다.
- `JWT_SECRET` 필수 조건과 PowerShell·Bash 키 생성법을 유지합니다.
- JWT·쿠키·CORS 변수와 prod Secure·origin 필수 조건을 유지합니다.
- `global/security`의 auth·jwt·refresh·cookie·cors·csrf 책임 설명을 유지합니다.
- #33의 인증 문서는 원본을 유지하고 링크를 병합 후 로컬 경로로 갱신합니다.
- #33 미적용 브랜치의 현재 동작과 적용 후 요구 조건을 구분합니다.

## 협업 상세

상세 기준과 예외는 [백엔드 협업 가이드](https://app.notion.com/p/4098b259919e80f79149e110d1ddd180)를 따릅니다.

1. **Issue 생성 → develop 최신화 → 작업 브랜치 → 개발·Commit → PR → 리뷰 → develop 병합** 순서로 진행합니다. 기본 단위는 **1 Issue → 1 Branch → 1 PR**입니다.
2. 브랜치 형식은 `{type}/{issue-number}-{description}`입니다. 설명은 영문 소문자·kebab-case를 사용하며, type은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`를 사용합니다.
3. 커밋 형식은 `type: 작업 내용 (#이슈번호)`입니다. 의미 있는 변경 단위로 나눕니다.
4. PR은 작업 브랜치에서 `develop`으로 생성하고 저장소의 [PR 템플릿](../.github/pull_request_template.md)을 사용합니다. 제목은 `[Type] 작업 내용`, Issue 연결은 본문에 `Closes #번호` 또는 일부 작업이면 `Related to #번호`로 작성합니다.
5. CI 통과와 최소 1명 이상의 Approve가 원칙입니다. 관련 영역 담당자를 우선 리뷰어로 지정하고, 리뷰는 PR 생성 후 12시간 안에 진행합니다. 공통 코드·타 영역 변경은 수정 전 팀에 공유합니다.
6. Merge 전 수정이 필요한 의견은 반영하거나 리뷰어와 논의해 해결합니다. 충돌은 다른 팀원 코드를 임의로 삭제하지 않고 해결합니다.
7. 병합 방식은 GitHub의 **Merge pull request (Create a merge commit)**입니다. `main`·`develop`에 직접 Push하거나 로컬에서 직접 Merge하지 않습니다.
8. `develop → main` 배포 PR 생성·Merge는 백엔드 팀장이 담당합니다.
9. 작업 중 최신화는 최신 `develop`을 작업 브랜치에 Merge하는 팀 방식을 따릅니다. 공용 브랜치의 force push와 공유 이력의 임의 변경은 금지합니다.

선병합은 후속 작업이 막힌 경우에만 협업 가이드의 조건·확인·공유 절차에 따라 적용합니다. 주요 Entity·연관관계, DB 스키마, 인증·인가, 권한 공통 로직, 공통 응답·예외 처리는 사전 Approve가 필요합니다. 예외를 일반적인 무리뷰 병합 허용으로 해석하지 않습니다.


## 관련 문서

- [프로젝트 Notion](https://app.notion.com/p/4098b259919e8072a7c9f56aa43c977c)
- [기획안](https://app.notion.com/p/4098b259919e80b99b8ce12ad4092e11)
- [백엔드 초기 설정](https://app.notion.com/p/4098b259919e8020a5cde63bc926e326)
- [백엔드 역할 분담](https://app.notion.com/p/4098b259919e80b58c9ffb324dddc1fb)
- [ERD 및 데이터 모델](https://app.notion.com/p/4098b259919e8050a531f3e54d6ab6cb)
- [API 공통규칙](https://app.notion.com/p/4098b259919e8037adf6cc193b4638c5)
- [백엔드 협업 가이드](https://app.notion.com/p/4098b259919e80f79149e110d1ddd180)

Notion 문서는 연결 계정의 접근 권한이 필요할 수 있습니다.
