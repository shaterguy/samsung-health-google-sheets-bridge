# Health Bridge Android 0.4.0

Samsung Health → Health Connect → Google Sheets의 기존 데이터 경로를 유지하는 Android 앱입니다. 공개 `0.3.6`의 설치 패키지 `com.personal.healthbridge.reinstall`과 고정 서명을 이어가므로 앱을 제거하지 않고 업데이트합니다. 암호화된 연결 설정과 마지막 성공 커서를 보존합니다.

## 화면과 자동 실행

- 동기화 메뉴에서 마지막 성공 시각과 최근 시도 결과를 구분해 확인하고 수동 동기화·전체 다시 대조를 실행합니다.
- 자동 실행 메뉴에서 주기 1·3·6·12·24시간과 최소 배터리 20·30·40·50·60·70%를 선택합니다. 인터넷 연결 종류는 제한하지 않습니다.
- 연결 메뉴에서 Health Connect 권한, Google 계정, 스프레드시트 ID를 관리합니다.

예약은 `AlarmManager`와 WorkManager를 함께 사용합니다. 정확한 알람 권한이 있는 경우 해당 알람에서 실행 중에만 전경 동기화 서비스를 사용합니다. 재부팅·앱 업데이트 후 예약을 복원하며, 배터리·네트워크 조건이 부족한 시도는 성공 커서를 보존하고 재시도합니다. 백그라운드 건강정보 읽기는 Health Connect의 기능 지원과 별도 권한을 확인합니다.

## 빌드

프로젝트는 Android Gradle Plugin 8.9.1, Kotlin 2.0.21, Java 17 바이트코드, compileSdk 36, targetSdk 35를 사용합니다. JDK 17 이상과 프로젝트 호환 Gradle을 사용합니다.

```bash
cd android
gradle --no-daemon --console=plain testDebugUnitTest assembleRelease
```

Termux에서는 실행 가능한 native AAPT2 경로를 명시합니다.

```bash
gradle --no-daemon --console=plain \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 \
  testDebugUnitTest assembleRelease
```

미서명 결과는 `app/build/outputs/apk/release/app-release-unsigned.apk`입니다. `assembleDebug`의 디버그 서명 APK는 기존 공개 앱의 업데이트용 결과가 아닙니다.

## 서명과 전달

정식 APK의 개인키는 로컬로 복제하지 않습니다. 로컬 테스트·release 빌드·파일 검증 후 정확한 소스 commit을 GitHub에 보존하고, 해당 태그의 비공개 초안 Release에 미서명 파일을 전달합니다. `sign-verified-apk.yml`을 main의 동일 source SHA에서 실행하면 미서명 체크섬·패키지·버전·태그를 확인하고 고정 인증서로 서명합니다. 최종 파일을 다시 내려받아 바이트와 체크섬을 대조한 뒤 공개합니다.

서명 정책은 [../SIGNING_POLICY.md](../SIGNING_POLICY.md), 최종 증거 기준은 [../BUILD-VERIFICATION.md](../BUILD-VERIFICATION.md)에 있습니다. 실제 Health Connect·Google OAuth와 장기 절전 동작은 확보한 실기기 증거의 범위를 구분해 기록합니다.
