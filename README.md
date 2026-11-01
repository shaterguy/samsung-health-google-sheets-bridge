# Samsung Health Bridge

Samsung Health가 Health Connect에 공유한 건강정보를 사용자가 지정한 Google Sheets로 직접 동기화하는 개인용 Android 앱입니다.

## 현재 버전과 업데이트

- 버전: `0.4.0` (`versionCode 13`).
- 설치 패키지: `com.personal.healthbridge.reinstall`.
- Android 9 (API 28) 이상이며 Health Connect 제공자의 지원 범위가 적용됩니다.
- 공개 `0.3.4` 이후와 같은 패키지명·고정 서명 인증서를 유지합니다. 기존 앱 위에 업데이트하며 설정과 동기화 커서를 보존합니다.
- Google Sheets API를 직접 사용하며 별도 개인 서버를 거치지 않습니다.

설치 파일은 [GitHub Release](https://github.com/shaterguy/samsung-health-google-sheets-bridge/releases/tag/v0.4.0)의 `HealthBridge-v0.4.0.apk`입니다. APK 인증서의 고정 지문과 보관 정책은 [SIGNING_POLICY.md](SIGNING_POLICY.md)에 있습니다.

## 화면 구성

| 메뉴 | 기능 |
| --- | --- |
| 동기화 | 마지막 성공 시각, 최근 실행 결과, 지금 동기화, 전체 다시 대조를 제공합니다. |
| 자동 실행 | 실행 사용 여부, 주기, 최소 배터리, 알람·배터리·백그라운드 읽기 상태를 관리합니다. |
| 연결 | Health Connect 읽기 권한, Google 계정, 스프레드시트 ID를 관리합니다. |

주기는 1·3·6·12·24시간 중 선택합니다. 최소 배터리는 20·30·40·50·60·70%이며 해당 잔량 이상에서 자동 실행합니다. 기존 80·90% 설정은 70%로 정규화합니다. Wi-Fi·모바일 데이터 등 인터넷 연결 종류에 따른 제한은 없습니다.

## 예약 실행

저장된 다음 예약을 앱 프로세스와 독립된 `AlarmManager` PendingIntent로 유지하며, 재부팅·앱 업데이트·시간 변경 뒤 복원합니다. 정확한 알람을 허용하면 절전 중에도 예약 시점에 실행을 요청하고, 실행 중에만 전경 동기화 서비스를 사용합니다. 정확한 알람을 허용하지 않은 기기에서는 비정확 알람과 WorkManager를 사용하므로 Android가 실행 시점을 늦출 수 있습니다. 네트워크·배터리 조건이 충족되지 않은 시도는 재시도하며 마지막 성공 커서를 유지합니다.

Health Connect의 일반 읽기 권한과 백그라운드 읽기 권한은 별개입니다. WorkManager를 통한 읽기는 기기에서 백그라운드 읽기를 지원하고 해당 권한을 허용한 경우에 실행합니다. 정확한 알람으로 시작한 전경 서비스는 일반 읽기 권한을 확인해 실행합니다. 자동 실행 화면에서 실제 권한·예약·배터리 제한 상태를 확인할 수 있습니다. 시스템 강제 종료, 앱 권한 회수, 삼성 절전 앱 제한 등 운영체제 차원의 제한이 적용되는 상태는 화면에 표시된 설정과 함께 확인합니다.

## 최초 연결

1. 연결 메뉴에서 Google 스프레드시트 ID를 저장합니다.
2. Health Connect의 건강정보 읽기 권한과 지원되는 백그라운드 읽기 권한을 허용합니다.
3. Google Sheets 연결을 눌러 사용할 Google 계정을 승인합니다.
4. 자동 실행 메뉴에서 주기·배터리 기준을 저장하고 자동 동기화를 켭니다.
5. 필요한 경우 알람 권한·배터리 제한·실행 알림 설정을 확인합니다.

Google OAuth는 APK의 패키지명과 서명 SHA-1을 확인합니다. 공개판 연결 정보는 [PUBLIC_OAUTH_SETUP.md](PUBLIC_OAUTH_SETUP.md)에 있습니다. 공개 `0.3.6`에서 업데이트할 때 계정·스프레드시트 설정을 초기화하지 않습니다.

## 데이터 범위와 보관

걸음 수, 심박수, 수면, 체중, 운동, 혈당, 산소포화도, 혈압, 총소모열량, 거리, 파워, 속도, 최대산소섭취량, 영양, 체지방, 기초대사량, 키를 읽습니다. 실제 범위는 Samsung Health가 Health Connect에 공유했고 사용자가 허용한 데이터로 정해집니다.

건강정보는 지정한 스프레드시트로 전송합니다. 연결 설정은 기기의 암호화 저장소에 보관하며 건강정보·OAuth 토큰·스프레드시트 ID를 GitHub에 저장하지 않습니다. 동기화는 기존 행 키를 대조해 누락된 행을 추가하고, 성공한 시점에만 커서를 갱신합니다.

## 빌드와 서명

테스트와 미서명 release APK 빌드는 로컬에서 수행합니다. 고정 개인키는 GitHub Actions secret `HEALTHBRIDGE_PUBLIC_SIGNING_BUNDLE_BASE64` 안에만 보관합니다. `sign-verified-apk.yml`은 정확한 소스 SHA와 미서명 APK 체크섬을 입력받아 기존 APK의 식별자를 검증한 뒤 정렬·서명·자산 readback·게시만 수행합니다. 동일 소스를 다시 빌드하거나 에뮬레이터 시험을 반복하지 않습니다.

검증 계약은 [BUILD-VERIFICATION.md](BUILD-VERIFICATION.md), Android 개발 정보는 [android/README.md](android/README.md)에 있습니다. 디자인 참조의 라이선스 표시는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 있습니다.
