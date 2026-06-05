# secrets/

런타임 시크릿(깃·이미지에 포함되지 않음)을 두는 디렉터리.

## FCM 서비스 계정 키
1. Firebase 콘솔 → 프로젝트 설정 → 서비스 계정 → "새 비공개 키 생성"으로 JSON 다운로드.
2. 이 파일을 `secrets/fcm-service-account.json` 으로 저장.
3. `.env` 에 컨테이너 내부 경로를 지정:
   ```
   FCM_ENABLED=true
   FCM_CREDENTIALS_PATH=/backend/secrets/fcm-service-account.json
   ```

docker-compose 가 `./secrets` 를 컨테이너의 `/backend/secrets` 에 read-only 로 마운트한다(WORKDIR=/backend).
파일이 없으면 FCM 푸시만 비활성화되고 토큰/뮤트/인박스는 정상 동작한다.

## 주의
- 이 디렉터리의 실제 시크릿 파일은 `.gitignore` 로 제외된다(`.gitkeep`·`README.md` 만 추적).
- 운영(Ubuntu)에서는 호스트 파일 권한을 `chmod 600` 으로 제한하고 서비스 유저 소유로 둘 것.
