# 인프라 / 배포 환경

> 실제 서버 상태를 확인해 작성한 문서 (최종 확인: 2026-08-08).

## 머신 구성

| | 개발 | 배포 |
|---|---|---|
| OS | macOS | **Ubuntu 24.04.4 LTS** (hostname `bombiserver`) |
| 역할 | 코드 작성, 로컬 테스트 | Podman 컨테이너 + 호스트 JVM 구동 |
| Java | 21 (toolchain) | **OpenJDK 21.0.11** 설치됨 |

## 서버 접속

SSH 별칭 `bombi-server`로 접속한다. 실제 주소·포트·키 경로는 각 개발 머신의
`~/.ssh/config`에만 있고 **이 저장소에는 없다** .

```bash
ssh bombi-server 'hostname'
```

계정 `bombi`는 **NOPASSWD sudo (ALL)** 권한을 가진다. 즉 `sudo` 명령이 비밀번호 없이 실행된다.
편리하지만 그만큼 파괴적인 명령도 곧바로 실행되므로, 서비스를 내리거나 데이터를 지우는
명령은 실행 전에 반드시 사용자 확인을 받는다.

## ⚠️ 컨테이너는 root podman으로 돌아간다

**이 프로젝트의 컨테이너는 `sudo podman`으로 띄워져 있다.** 그냥 `podman ps`를 치면
이 프로젝트 컨테이너가 하나도 안 보이고, bombi 계정의 rootless 컨테이너(다른 프로젝트들)만 나온다.

```bash
ssh bombi-server 'sudo podman ps -a --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"'
```

→ **새 포트를 할당하기 전에 반드시 사용 중인 포트를 확인할 것.**

```bash
ssh bombi-server 'ss -tlnp'
```

## 서버 디렉터리 구조

```
/home/bombi/deploy/potato-coin-tracker/
├── infrastructure/          # Zone 1 — 컨테이너 영역
│   ├── compose.yml
│   ├── .env                 # CLOUDFLARE_TUNNEL_TOKEN, JENKINS_ADMIN_ID, JENKINS_ADMIN_PASSWORD
│   ├── jenkins_data/        # Jenkins 홈 볼륨
│   ├── mongodb_data/        # MongoDB 데이터 볼륨
│   └── clickhouse_data/     # ClickHouse 데이터 볼륨
└── app/                     # Zone 2 — 호스트 실행 영역
    ├── app.jar              # 배포된 실행 파일
    ├── app.log
    └── deploy.sh            # 배포 스크립트
```

## 현재 가동 상태

| 구성요소 | 상태 |
|---|---|
| `potato-coin-tracker-jenkins` | ✅ 가동 중 (8080, 50000) |
| `potato-coin-tracker-mongodb` | ✅ 가동 중 (27017) |
| `potato-coin-tracker-clickhouse` | ✅ 가동 중 (8123, 9000) |
| `infrastructure_cloudflared_1` | ✅ 가동 중 |
| `potato-coin.service` (호스트 앱) | ✅ **active (running)**, 부팅 시 자동 실행 활성화 |

앱은 MongoDB(`localhost:27017`)에 정상 연결되어 있다.

> 참고: 컨테이너보다 앱이 먼저 뜨면 MongoDB 연결에 실패한다. systemd의 `Restart=always`
> 덕분에 컨테이너가 올라온 뒤 자동으로 복구되지만, 기동 순서 의존성은 남아 있다.

## 포트 배치

| 포트 | 사용처 | 위치 |
|---|---|---|
| 8080 | Jenkins Web UI | 컨테이너 |
| 50000 | Jenkins Agent (미사용) | 컨테이너 |
| 27017 | MongoDB | 컨테이너 |
| 8123 | ClickHouse HTTP | 컨테이너 |
| 9000 | ClickHouse Native | 컨테이너 |
| **9090** | **Spring Boot 앱** (java PID가 직접 LISTEN) | **호스트** |

⚠️ Prometheus 기본 포트도 9090이다. 나중에 Prometheus 컨테이너를 추가할 때
`9090:9090`으로 매핑하면 호스트 앱과 충돌한다. 호스트 쪽 포트를 다른 값으로 매핑할 것.

## systemd 유닛 (`/etc/systemd/system/potato-coin.service`)

```ini
[Unit]
Description=Potato Coin Tracker Spring Boot App
After=syslog.target network.target

[Service]
User=bombi
WorkingDirectory=/home/bombi/deploy/potato-coin-tracker/app
ExecStart=/usr/bin/java -jar /home/bombi/deploy/potato-coin-tracker/app/app.jar
SuccessExitStatus=143          # Spring Boot의 정상 종료 코드
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

나중에 Scouter Agent를 붙일 때 이 `ExecStart`에 `-javaagent:...` 옵션을 추가하게 된다.

## CI/CD 파이프라인

Jenkins Job: `potato-coin-tracker-pipeline` (Declarative Pipeline, **GitHub Push Trigger** 설정됨)

| 단계 | 내용 |
|---|---|
| 1. Git Checkout | `github.com/bom-b/potato-coin-tracker`의 `main` 브랜치 |
| 2. Gradle Build | `chmod +x ./gradlew` → `./gradlew clean bootJar` |
| 3. Transfer | `scp`로 `build/libs/*-SNAPSHOT.jar` → 호스트의 `app/app.jar` |
| 4. Deploy | `ssh`로 호스트의 `deploy.sh` 실행 |

**컨테이너 → 호스트 연결 방식**: Jenkins 컨테이너가 자체 SSH 키
(`/var/jenkins_home/.ssh/id_rsa`)로 호스트 LAN IP `192.168.0.177:20253`에 접속한다.
`.jar` 교체는 3단계의 `scp`가, 재기동은 4단계가 담당하는 **역할 분리 구조**다.

### 배포 스크립트 (`app/deploy.sh`)

```bash
#!/bin/bash
echo "=== 배포 시작 ==="
sudo systemctl restart potato-coin
echo "=== 배포 완료 ==="
```

`.jar` 교체는 파이프라인 3단계에서 이미 끝나므로, 이 스크립트는 재기동만 담당하면 된다.
`sudo`가 비밀번호 없이 실행되는 것은 bombi 계정의 NOPASSWD 설정 덕분이다.

### ⚠️ 현재 systemd 버전은 파이프라인으로 검증되지 않았다

- **빌드 #8** (8월 3일) — 전체 SUCCESS. 배포된 `app.jar`이 이 빌드 산출물이다.
- **빌드 #9** (8월 7일) — SUCCESS이지만 **4단계부터 restart한 빌드**라 1~3단계가 skip됐다.
  이때 실행된 `deploy.sh`는 `kill` + `nohup`으로 프로세스를 직접 다루던 **구버전**이었고,
  로그에 `kill: 명령을 허용하지 않음` 오류가 남아 있다.
- 그 직후 `deploy.sh`를 systemd 방식으로 교체하고 `potato-coin.service`를 등록했다.

→ **지금의 systemd 기반 `deploy.sh`는 아직 Jenkins 파이프라인을 통해 한 번도 실행된 적이 없다.**
다음 커밋을 푸시할 때 1단계부터 전체 빌드가 도는지 확인이 필요하다.

## compose.yml

`infrastructure/compose.yml`에 4개 서비스가 정의되어 있다 — jenkins, cloudflared, mongodb, clickhouse.
볼륨에 Podman/SELinux 충돌 방지용 `:Z` 태그, DB들은 `/etc/localtime:ro`로 호스트 시간대를 따라간다.
ClickHouse는 계정이 설정되어 있고, **MongoDB는 아직 인증이 없다.**

### 네트워크

`potato-net`은 compose가 만들지 않고 외부에서 미리 생성한다. 호스트 기본 리졸버(`127.0.0.53`)에
컨테이너가 접근하지 못해 cloudflared 터널 연결이 실패하는 문제 때문에 상위 DNS를 명시한다.

```bash
sudo podman network create --dns 8.8.8.8 --dns 1.1.1.1 infrastructure_potato-net
```

### JDK 버전 정렬 (의도된 제약)

**Jenkins 컨테이너의 JDK와 앱의 Java toolchain은 항상 같은 버전(현재 21)으로 맞춘다.**
빌드가 Jenkins 컨테이너 안에서 일어나므로, 컨테이너 JDK가 앱 toolchain보다 낮으면 빌드가 깨진다.

| 위치 | 설정 | 현재 |
|---|---|---|
| Jenkins 이미지 | `jenkins/jenkins:lts-jdk21` | 21 |
| 앱 빌드 | `build.gradle`의 `JavaLanguageVersion.of(21)` | 21 |
| 호스트 런타임 | `/usr/bin/java` | 21.0.11 ✅ |

### 정리하면 좋은 것들

- `version: '3.8'` — 최신 compose 구현에서는 무시되는 항목. 삭제 가능.
- jenkins 서비스 주석이 "Spring Boot 3.x (Java 17)"로 되어 있다. 이미지 태그와 실제 구성은
  맞고 **주석만 낡았다.** `# Spring Boot 4.x (Java 21) 빌드를 위해 JDK 21이 포함된 LTS 버전 사용`으로 수정.

## 자주 쓰는 명령

전부 Mac에서 `ssh bombi-server '...'` 형태로 실행할 수 있다.

컨테이너 상태:

```bash
ssh bombi-server 'sudo podman ps -a --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"'
```

컨테이너 로그:

```bash
ssh bombi-server 'sudo podman logs --tail 100 potato-coin-tracker-jenkins'
```

앱 상태:

```bash
ssh bombi-server 'systemctl status potato-coin --no-pager'
```

앱 로그 (최근 100줄):

```bash
ssh bombi-server 'journalctl -u potato-coin -n 100 --no-pager'
```

컨테이너 스택 기동:

```bash
ssh bombi-server 'cd /home/bombi/deploy/potato-coin-tracker/infrastructure && sudo podman compose up -d'
```

## ClickHouse 계정

로컬·배포 모두 `potato` / `potato`, 데이터베이스는 `potato_coin`으로 통일했다.
`application.yaml` 하나가 양쪽에서 그대로 동작한다.

`CLICKHOUSE_USER`/`CLICKHOUSE_PASSWORD`를 지정하지 않으면 엔트리포인트가 `default` 유저를
127.0.0.1 전용으로 잠그기 때문에 컨테이너 밖에서 접속할 수 없다. 이때 뜨는 메시지는
"Authentication failed"지만 실제 원인은 네트워크 제한이다.

`CLICKHOUSE_DB`는 데이터 디렉터리가 비어 있을 때만 적용된다. 기존 볼륨이 있는 상태에서
계정만 바꾸면 DB는 생기지 않으므로, 볼륨을 지우고 재생성하거나 `CREATE DATABASE`를 따로 실행한다.

## 아직 없는 것

- 시세 생성 로직 (앱 본체)
- ClickHouse 스키마 (테이블 DDL)
- Prometheus / Grafana / Scouter Server 컨테이너
- MongoDB 인증
