# 오프라인 엔진룸 웹앱

사진을 기기 내부의 YOLO11 ONNX 모델로 추론하고, 박스를 선택해 부품 카탈로그의 품번 후보·관련 부속·원문 행·분해도를 확인합니다. 저장된 예측을 재생하는 목업이 아닙니다.

## 실행

완성 실행 팩: ZIP 압축을 풀고 `python serve.py` 실행 → `http://127.0.0.1:8765/` 접속. Python은 정적 파일만 제공하며 이미지 업로드나 서버 추론을 하지 않습니다. 설치 준비 이후 인터넷은 필요하지 않습니다. **index.html을 file://로 직접 열면 모듈·WASM·Worker 제약으로 실행되지 않습니다.**

Android 태블릿: 저장소의 Android 앱에서 **오프라인 웹 검사** 버튼을 누릅니다. CI APK에는 화면/ONNX WASM/PDF 뷰어가 포함됩니다. 완성 실행 팩의 `android-imports/model.zip` 및 `android-imports/catalog.zip`을 태블릿에 복사하세요. 처음 한 번 모델 ZIP과 카탈로그 팩 ZIP을 가져오면 IndexedDB에 저장되고 이후 비행기 모드로 실행할 수 있습니다. Android System WebView는 모듈 Worker·WASM SIMD·PDF.js를 지원하는 최신 버전이 필요하며, 실제 기기 성능은 별도 검증해야 합니다. 사진 선택/촬영은 기기 파일 선택기 지원에 따라 달라집니다. 웹 모드는 연속 실시간 카메라 추론이 아니라 사진 단위 추론입니다.

## 자료

- 모델 ZIP: `model.onnx` + `manifest.json`. 100 MiB 제한, SHA-256 검사. `engine58_model_checked.zip`과 호환.
- 카탈로그 ZIP: `catalog.json` + `catalog.pdf`. 기존 `engine_inspection_catalog_v2.zip`도 호환하며 ZIP의 사진은 읽지 않습니다. 200 MiB 제한, PDF SHA-256/페이지 수/행 좌표 검사.
- 모델은 RGB/0–1/CHW, 640 정사각 letterbox(패딩 114), 출력 `[1,4+C,8400]`. 클래스 이름으로 연결하며 objectness를 별도 곱하지 않습니다. 클래스별 NMS IoU 0.45, 최대 50개, 점수 하한 0.05. 슬라이더는 이 결과를 필터링합니다.
- 후보 품번은 생산일·옵션·ISG·변속기에 따라 다릅니다. 사진으로 호환성을 확정하지 않습니다. 전체 카탈로그가 아닌 기존 엔진룸 관련 235개 행 색인을 재사용합니다.
- 모델 검증의 OpenCV 리사이즈와 브라우저 Canvas 리사이즈는 픽셀 보간이 다를 수 있습니다. 이전 Python 평가 수치를 브라우저 재평가 결과로 주장하지 않습니다.

## 개발 및 오프라인 팩 생성

```sh
cd web
npm ci --ignore-scripts
npm test
npm run build
cd ..
python -m pip install -r tools/requirements-catalog.txt
python tools/build_web_bundle.py --model-zip /path/model.zip --catalog /path/catalog.pdf --output /path/engine-web-offline.zip --android-assets android/app/src/main/assets/web
```

`--sample /path/photo.jpg`를 반복해 시작 샘플을 포함할 수 있습니다. 모델·PDF·사진은 `web/dist`, APK 로컬 assets, 실행 ZIP에만 들어가며 공개 Git에는 올리지 않습니다. 사용자 가져오기는 앱 원점의 IndexedDB에 보관됩니다. 저장 실패 시 화면에서 경고하고 다음 실행에 다시 가져오도록 안내합니다. 브라우저 데이터를 지우면 자료도 삭제됩니다.

일반 브라우저에서는 **오프라인 화면 저장** 버튼으로 같은 원점의 런타임과 번들 파일을 캐시합니다. 캐시 완료 후 같은 주소로 다시 접속해야 합니다. 로컬 서버 방식은 오프라인에도 서버가 실행 중이어야 하며, Android 앱은 내장 자산을 사용하므로 서버가 필요 없습니다.

의존성: ONNX Runtime Web 1.20.1, PDF.js 4.10.38, fflate 0.8.2. 버전은 package-lock.json에 고정, 실행 시 CDN 요청 없음. 라이선스는 빌드 산출물 vendor에 포함합니다.

## 확인 범위

`npm test`: letterbox 좌표 복원, 클래스별 NMS, 부분 클래스 순서, 손상된 모델 계약, 카탈로그 범위 검색·페이지 좌표 검증.
`python -m unittest discover -s tests -v`: 기존 데이터·카탈로그 회귀 검사.

CPU/WASM 추론이므로 모델 초기화·추론 시간은 태블릿에 따라 달라집니다. 브라우저 데스크톱 검증은 Android 실기기 검증을 대신하지 않습니다. 결함 진단 모델이 아니며, 5개 학습 클래스 외의 부품 탐지 성능을 주장하지 않습니다.
