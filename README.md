# Mobility-Inspecter_Engine

2023년형 일반 렉스턴 스포츠 엔진룸의 부품을 탐지하고, 바운딩 박스에서 관련 품번 후보·부속·카탈로그 원문을 찾는 오프라인 Android/웹 앱입니다.

## 기술 문서

**[현재 구조·기술 스택·구동 방식·사용법 전체 가이드](docs/TECHNICAL_GUIDE.md)**

현재 앱은 **0.4.0 / `com.mobility.engine`**, Android 8.0 이상, `arm64-v8a`·`x86_64` 대상입니다. 갤럭시 탭 Active 1세대는 현재 APK와 호환되지 않습니다.

| 모드 | 기능 |
|---|---|
| Android 검수 사진 | 검수 팩의 사진과 라벨 확인, 카탈로그 연결 |
| Android 실시간 카메라 | CameraX + 네이티브 ONNX 탐지, 박스 선택 |
| 오프라인 웹 검사 | 선택 사진의 실제 ONNX WASM 추론, PDF 원문/분해도, 검사 JSON 내보내기 |

현재 제공 모델은 **5종 부품 탐지**용입니다. 공통 데이터 계약의 12종 전체를 학습한 모델이나 고장 진단 모델이 아닙니다. 품번은 적용 옵션을 확인해야 하는 후보이며 사진만으로 확정하지 않습니다.

## 빠른 사용

1. [GitHub Actions](https://github.com/Origin1332/Mobility-Inspecter_Engine/actions)의 성공한 실행에서 `mobility-inspecter-engine-debug`를 내려받아 APK를 설치합니다.
2. **오프라인 웹 검사**에서 별도 제공 실행 팩의 `android-imports/model.zip`과 `android-imports/catalog.zip`을 가져옵니다.
3. **사진 선택 → 부품 탐지 → 박스/목록 선택 → 카탈로그 확인** 순서로 사용합니다.
4. 박스를 수정하거나 제외하고 품번 후보와 메모를 선택한 뒤 **검사 저장**을 누릅니다. **저장된 검사**에서 사진과 결과를 다시 엽니다.
5. **검사 기록 내보내기**로 사진을 제외한 JSON을 출력합니다.

실시간 화면의 **장면 고정·검사**로 같은 편집 흐름에 진입할 수 있습니다. 자세한 사용법과 데이터 규격: [검사 세션 가이드](docs/INSPECTION_SESSIONS.md).

네이티브 모드는 **검수 팩 ZIP**과 **YOLO 모델 ZIP**에서 별도로 자료를 가져옵니다. 두 모드의 자료 저장소는 공유되지 않습니다. 웹은 사진 단위 검사이며 연속 카메라 모드는 Android 메인 화면에 있습니다.

## 개발·검증·운영

- 빌드, 로컬 실행, 비공개 팩 생성: [기술 가이드](docs/TECHNICAL_GUIDE.md#6-개발-환경과-빌드), [웹 README](web/README.md)
- 검증 결과와 미확인 범위: [기술 가이드](docs/TECHNICAL_GUIDE.md#7-모델-및-검증-상태), [웹 런타임 검증](docs/WEB_VALIDATION.md)
- 데이터/카탈로그 계약: [DATASET.md](docs/DATASET.md), [CATALOG.md](docs/CATALOG.md)
- 초기 6장 검수 팩 작업의 역사 기록: [STATUS.md](docs/STATUS.md)

원본 사진·PDF·모델 가중치는 공개 Git에 포함하지 않습니다. CI APK는 런타임을 포함하지만 모델과 카탈로그는 별도로 가져와야 합니다. 실기기 카메라·성능·장시간 동작은 추가 검증 대상입니다.

`main`의 기능 개발을 먼저 완료한 후 기준 버전에서 `legacy_active`를 분리하여 저사양 대응을 진행합니다.
