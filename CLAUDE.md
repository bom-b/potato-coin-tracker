# CLAUDE.md

## 이 프로젝트는 무엇인가

**회사에서 쓰는 기술 스택을 익히기 위한 학습용 프로젝트.**
1초마다 가상의 "감자 코인" 시세를 생성하고, 그 데이터를 저장·집계·모니터링하는
전체 파이프라인을 직접 구축해보는 것이 목적이다.

동작하는 서비스를 만드는 것보다 **각 기술을 실무에서 쓰는 방식대로 써보는 것**이 우선이다.
따라서 코드를 작성할 때는:

- 결과만 주지 말고 **왜 그 구조/설정을 선택했는지** 짧게 설명한다.
- 지름길보다 실무에서 통용되는 표준적인 방식을 택한다.
- 새로운 기술 요소를 도입할 때는 그 기술이 이 아키텍처에서 맡는 역할을 먼저 짚어준다.

전체 그림은 [docs/architecture.md](docs/architecture.md), 서버·포트·운영 명령은
[docs/infrastructure.md](docs/infrastructure.md) 참고.

## 기술 스택

| 항목 | 버전 |
|---|---|
| Spring Boot | **4.1.0** (Spring Framework 7.0.8) |
| Java | 21 (toolchain) |
| Gradle | 9.5.1 (wrapper) |
| MongoDB | 7.0 — 원본 시세 데이터 |
| ClickHouse | latest — 시계열 집계, `clickhouse-jdbc` 0.6.1 |

- 그룹/패키지: `com.potatonetwork` / `com.potatonetwork.potatocointracker`
- 앱 포트: **9090** (`src/main/resources/application.yaml`)
- 현재 소스는 뼈대 수준. `TestApiController`의 `GET /test` 하나뿐이고 시세 생성 로직은 없다.

## ⚠️ Spring Boot 4 주의사항

**이 프로젝트는 Boot 4 / Spring Framework 7 세대다. 웹에서 찾을 수 있는 자료 대부분은 Boot 3.x 기준이라 그대로 적용되지 않는다.**

이미 확인된 차이:

- `spring-boot-starter-web` → **`spring-boot-starter-webmvc`**
- 테스트 슬라이스가 모듈별 스타터로 분리됨 — `spring-boot-starter-webmvc-test`,
  `spring-boot-starter-data-mongodb-test`, `spring-boot-starter-actuator-test`

따라서 **Boot 3 기준의 기억이나 예제로 API·설정 키·스타터 이름을 추정하지 말 것.**
확신이 서지 않으면 실제 의존성 트리나 클래스 시그니처를 확인하고 쓴다:

```bash
sh gradlew -q dependencies --configuration runtimeClasspath
```

## 빌드 / 실행

`gradlew`에 실행 권한이 없으므로 `./gradlew`가 아니라 `sh gradlew`로 실행한다
(아래 "알려진 이슈" 참고).

빌드:

```bash
sh gradlew build
```

테스트:

```bash
sh gradlew test
```

로컬 실행:

```bash
sh gradlew bootRun
```

배포용 jar 생성:

```bash
sh gradlew bootJar
```

## 로컬 개발 환경

MongoDB와 ClickHouse를 Docker 컨테이너로 띄운다 (Mac은 Docker Desktop).
배포 서버와 **이미지 버전·포트를 동일하게** 맞췄기 때문에 `application.yaml` 하나로
로컬과 배포 환경을 모두 커버한다.

DB 기동 (healthy 될 때까지 대기):

```bash
docker compose -f compose.local.yml up -d --wait
```

DB 중지:

```bash
docker compose -f compose.local.yml down
```

데이터까지 초기화:

```bash
docker compose -f compose.local.yml down -v
```

| | 접속 정보 |
|---|---|
| MongoDB | `mongodb://localhost:27017/potato_coin` |
| ClickHouse | `jdbc:clickhouse://localhost:8123/potato_coin` (`potato` / `potato`) |

`bootRun` 후 아래가 `UP`이면 양쪽 DB 연결이 정상이다.

```bash
curl -s http://localhost:9090/actuator/health
```

### ⚠️ ClickHouse 컨테이너는 계정을 명시해야 한다

`CLICKHOUSE_USER`/`CLICKHOUSE_PASSWORD`를 주지 않으면 엔트리포인트가 `default` 유저를
**127.0.0.1 전용으로 잠근다.** 컨테이너 밖에서 접속하면 "Authentication failed"가 뜨는데,
실제 원인은 비밀번호가 아니라 네트워크 제한이라 헷갈리기 쉽다.

### ⚠️ clickhouse-jdbc는 서드파티 라이브러리를 포함하지 않는다

`clickhouse-jdbc:0.6.1` 단독으로는 커넥션 획득이 실패한다. `build.gradle`의
`httpclient5`와 `lz4-java`는 그래서 있는 것이므로 지우면 안 된다. 이유는 해당 주석 참고.

## 작업할 때 지킬 것

**배포 서버에 SSH로 접속할 수 있다.** 별칭은 `bombi-server` (주소·키는 `~/.ssh/config`에만 있고
저장소에는 없다). `compose.yml`, systemd 유닛, 컨테이너 로그를 직접 확인·수정할 수 있으므로
**인프라 상태를 추측하지 말고 확인한다.**

```bash
ssh bombi-server 'sudo podman ps -a'
```

주의할 점 두 가지:
- 이 프로젝트 컨테이너는 **root podman**으로 돌아간다. `podman`이 아니라 **`sudo podman`**을 써야 보인다.
- `bombi` 계정은 NOPASSWD sudo 권한을 가진다. 서비스를 내리거나(`compose down`, `systemctl stop`)
  데이터를 지우는 명령은 **실행 전에 사용자 확인을 받는다.** 조회는 그냥 해도 된다.
- 같은 서버에 다른 프로젝트(`happy-memories`, `msa-commerce`)가 함께 돌고 있다.
  포트를 새로 할당하기 전에 `ss -tlnp`로 충돌을 확인하고, 다른 프로젝트의 컨테이너는 건드리지 않는다.

**주석은 짧게.** 설정 파일에 배경 설명이나 튜토리얼식 주석을 늘어놓지 않는다.
없으면 실수하기 쉬운 부분만 한 줄로 남기고, 긴 설명은 `docs/`에 쓴다.

**진짜 시크릿은 저장소에 넣지 않는다.** Jenkins 계정과 Cloudflare 토큰은 서버 `.env`에 있다.
DB 계정(`potato`/`potato`)은 로컬·배포 공통 개발용 값이라 `application.yaml`에 그대로 둔다.

**커밋은 요청받았을 때만 한다.** 컨벤션은 기존 이력을 따른다 — Conventional Commits
접두사(`feat:`, `chore:`, `fix:` 등) + 한글/영문 혼용 제목. 예: `chore: JDK17 -> JDK21`

**문서를 최신으로 유지한다.** 아키텍처가 바뀌거나 미배포 항목(Prometheus, Grafana,
Scouter 등)이 실제로 올라가면 `docs/` 하위 문서의 상태 표를 갱신한다.

## 알려진 이슈

1. **`gradlew`에 실행 권한이 없다** (git 모드 `100644`).
   로컬에서는 `sh gradlew`로 우회하고, Jenkins 파이프라인은 빌드 단계에서
   `chmod +x ./gradlew`를 먼저 실행해 넘어가고 있다. 즉 **당장 깨지지는 않는다.**
   다만 그 `chmod` 한 줄은 원래 필요 없는 것이므로, git 모드를 고치면 양쪽 다 정리된다:

   ```bash
   git update-index --chmod=+x gradlew
   ```

2. **`build.gradle`에 `spring-boot-starter-data-mongodb`가 두 번 선언되어 있다.**
   동작에 문제는 없지만 아래쪽 "// mongo DB 연동" 블록의 중복 선언은 정리하는 게 좋다.

3. **미해결 설계 이슈들** — 컨테이너 Jenkins가 호스트 `systemctl`을 호출하는 방법,
   컨테이너에서 호스트 Actuator 접근, Prometheus와 앱의 9090 포트 충돌 등은
   [docs/architecture.md](docs/architecture.md)의 "미해결 설계 이슈" 절에 정리되어 있다.
   관련 작업을 시작하기 전에 먼저 결정이 필요하다.
