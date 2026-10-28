# Mobility-Inspecter_Engine

2023년형 **일반 렉스턴 스포츠 엔진룸**의 부품 위치를 Android 태블릿 후면 카메라에 표시하는 오프라인 검사 보조 앱입니다. 칸·쿨멘·렉스턴 SUV는 학습 대상에서 제외합니다.

**현재 상태: 앱 소스와 학습·평가 파이프라인 구현. 학습된 엔진룸 가중치와 실측 성능은 아직 없습니다.** COCO 사전학습 모델만 가져와서는 엔진룸 부품을 검사할 수 없습니다. 모델이 없으면 카메라 화면만 표시됩니다.

기존 [Mobility-Inspecter PR #1](https://github.com/Origin1332/Mobility-Inspecter/pull/1)의 CameraX + ONNX Runtime 흐름을 엔진룸용으로 확장했습니다. 박스·한글 부품명·신뢰도, 박스 터치 시 좌표와 점검 안내, 임계값 조절, 일시정지, 모델 ZIP 가져오기를 유지합니다. 별도 applicationId `com.mobility.engine`으로 기존 앱과 함께 설치할 수 있습니다.

## 성능 개선 구성

| 항목 | 기존 구성 | 엔진룸 구성 |
|---|---|---|
| 기준 모델 | YOLOv8n / 640 | 동일 엔진룸 데이터로 YOLOv8n / 640 재학습 |
| 개선 후보 | 없음 | YOLO11s / 960, 경량 비교 YOLO11n / 640 |
| 입력 크기 | 640 | 640 / 960 / 1280 검증 및 ONNX 처리 |
| 분할 | 이미지 무작위 train/val | 차량 group_id별 train/val/test 고정 |
| 데이터 검증 | 일부 클래스 학습 예시 없음 | 모든 분할의 클래스 예시·차종·검수·중복·좌표 검사 |
| 성능 판정 | 실측 없음 | 동일 test 이미지 해시, mAP50-95·부품별 recall 기록 |

큰 모델과 높은 해상도는 개선 **후보**이며, 태블릿에서는 더 느릴 수 있습니다. 전면부 mAP와 엔진룸 mAP를 직접 비교하지 않습니다. 같은 엔진룸 데이터로 학습한 기준 모델과 비교합니다. 기본 정확도 통과 조건은 mAP50-95 **0.03 이상 증가** 및 클래스별 recall 하락 없음입니다. 이는 개발 목표이며 달성한 수치가 아닙니다. 배포 결정에는 별도 태블릿 지연시간·실차 검수가 필요합니다.

## 검사 항목 v1 (실차 라벨 검수 전 초안)

엔진 커버, 배터리, 냉각수 보조탱크, 흡기 호스, 퓨즈 박스, 에어클리너, 브레이크액 탱크, 워셔액 주입구, 엔진오일 주입구 캡, 엔진오일 레벨 게이지의 10개 클래스입니다. 정확한 영어 ID와 경계 규칙은 [데이터 규격](docs/DATASET.md)에 있습니다. 초기 5개 항목은 이전 엔진룸 작업에서 언급된 항목이고, 나머지는 검수할 후보로 추가했습니다. 실제 사진에서 보이지 않거나 식별이 불확실한 부품은 박스를 추정하지 않습니다.

**부품 탐지는 고장·누유·액량·전기 상태 자동 진단이 아닙니다.** 미검출은 부품이 없다는 뜻이 아닙니다. 고장 자동 판정에는 별도의 결함 라벨과 검증이 필요합니다.

## PC 학습 → 태블릿

Python 3.11/3.12 가상환경에서 저장소 루트 기준으로 실행합니다. NVIDIA GPU 학습은 호환 PyTorch/CUDA 설치 후 `--device 0`을 사용하세요. CPU 실행도 가능하지만 느립니다.

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install -r tools/requirements.txt
python tools/prepare_dataset.py --source C:/datasets/engine-reviewed.zip --output data/engine-v1
python tools/train_export.py --data data/engine-v1/data.yaml --weights yolov8n.pt --imgsz 640 --epochs 150 --name engine-baseline --output models/engine-baseline --device 0
python tools/train_export.py --data data/engine-v1/data.yaml --weights yolo11s.pt --imgsz 960 --epochs 150 --name engine-candidate --output models/engine-candidate --device 0
python tools/benchmark.py --data data/engine-v1/data.yaml --baseline runs/engine-baseline/weights/best.pt --candidate runs/engine-candidate/weights/best.pt --output reports/comparison-v1.json --device 0
```

모델 다운로드에는 인터넷이 필요합니다. 출력 폴더는 기존 결과를 덮어쓰지 않는 새 경로로 지정합니다. 960 입력 후보가 너무 느리면 YOLO11n / 640을 별도 학습·평가하세요. 1280은 메모리·속도 측정 후 선택하는 실험 옵션입니다.

내보내기만 다시 수행할 때:
```powershell
python tools/train_export.py --data data/engine-v1/data.yaml --checkpoint runs/engine-candidate/weights/best.pt --architecture yolo11 --imgsz 960 --output models/engine-export
```

생성된 `models/engine-candidate.zip`에는 `model.onnx`와 `manifest.json`이 들어 있습니다. 태블릿에서 **모델 ZIP** 버튼으로 가져옵니다. 클래스 순서·엔진룸 task·입출력 크기·체크섬을 검증합니다. 모델·원본 사진·라벨은 Git에 올리지 않습니다.

## Android 빌드와 검증

JDK 17, Android SDK 35, Gradle 8.9:
```powershell
python -m unittest discover -s tests -v
gradle -p android testDebugUnitTest lintDebug assembleDebug --no-daemon
```

[GitHub Actions](https://github.com/Origin1332/Mobility-Inspecter_Engine/actions)에서 main push / PR마다 Python 테스트, Android 단위 테스트, lint, APK 빌드를 수행합니다. 성공한 실행의 `mobility-inspecter-engine-debug` 아티팩트를 내려받아 APK를 설치합니다. APK에는 학습 모델이 포함되지 않습니다.

[개발 근거와 남은 검증](docs/STATUS.md) · [Ultralytics YOLO11](https://docs.ultralytics.com/models/yolo11/) · [ONNX 내보내기](https://docs.ultralytics.com/modes/export/)

Ultralytics 및 각 의존성의 라이선스를 배포 전에 확인하세요. 이 저장소는 원본 엔진룸 사진이나 정비 매뉴얼을 재배포하지 않습니다.

