# 엔진룸 라벨 규격 v1

기존 형식인 `dataset/images/*.jpg` + `dataset/annotations.json`을 유지하며 차량·검수·분할 메타데이터를 추가합니다. YOLO 출력은 images/{train,val,test}, labels/{train,val,test}, data.yaml, dataset_report.json입니다.

| ID | class_name | 한글 | 박스 기준 |
|---|---|---|---|
| 0 | engine_cover | 엔진 커버 | 눈에 보이는 커버 외곽 |
| 1 | battery | 배터리 | 배터리 본체, 주변 배선 제외 |
| 2 | coolant_reservoir | 냉각수 보조탱크 | 식별 가능한 탱크 본체와 캡 |
| 3 | intake_hose | 흡기 호스 | 식별 가능한 연속 호스 구간; 다른 호스와 혼동 시 제외 |
| 4 | fuse_box | 퓨즈 박스 | 외부 하우징/덮개 |
| 5 | air_cleaner | 에어클리너 | 외부 하우징; 연결 호스 별도 |
| 6 | brake_fluid_reservoir | 브레이크액 탱크 | 식별 가능한 탱크와 캡 |
| 7 | washer_fluid_filler | 워셔액 주입구 | 식별 가능한 주입구와 캡; 숨은 탱크 추정 금지 |
| 8 | oil_filler_cap | 엔진오일 주입구 캡 | 캡 외곽 |
| 9 | oil_dipstick | 엔진오일 레벨 게이지 | 외부에서 보이는 손잡이 |

실차 사진과 정비 자료를 대조해 이 초안을 검수한 후 라벨을 제작합니다. 클래스 변경 시 Python contract.py와 Android Parts.kt를 함께 변경하고 기존 모델은 재학습합니다.

- 2023년형 일반 렉스턴 스포츠임을 출처/차량 정보로 확인합니다. 칸·쿨멘·SUV·연식 불명은 제외 또는 보류합니다.
- JPG/RGB, 긴 변 최대 1280px, 종횡비 유지, 확대 금지. 이 변환 도구는 이미 규격화된 이미지를 입력받습니다.
- bbox는 규격화된 이미지의 픽셀 좌표 [x_min,y_min,x_max,y_max]. 불확실한 위치·흐림·극소 물체는 추정하지 않습니다.
- 여러 인스턴스는 모두 라벨링하며, 보이지 않는 외곽을 추정해 확장하지 않습니다. 부품이 가려져 판단 불가하면 추가 촬영합니다.
- 동일 차량 및 연속 프레임·근접 중복은 같은 group_id에 묶고 한 분할에만 배정합니다. 서로 다른 촬영 세션이라도 같은 차량은 같은 그룹입니다.
- train/val/test는 먼저 차량 단위로 고정합니다. 권장 출발점은 70/15/15이며 각 분할에 모든 클래스를 포함합니다. 클래스별 1개 예시는 코드 검증 최소조건일 뿐 충분한 학습량이 아닙니다.
- 조명·오염·각도·거리·가림·기기별 예시, 부품이 없는 배경 음성 샘플도 수집합니다. 가까운 중복은 별도 육안 검수합니다.
- 출처 URL 또는 자체 촬영 기록과 사용 권한을 보관합니다. 번호판 등 불필요한 개인정보는 수집 단계에서 제외합니다.
- test는 모델/임계값 선택에 반복 사용하지 않습니다. 후보 선택은 val에서 끝내고 test는 최종 비교에 사용합니다.

아래는 형식 설명용 레코드이며 **실제 라벨이 아닙니다**. classes에는 contract.py의 10개 ID를 순서대로 넣습니다.

```json
{
  "classes": ["engine_cover", "battery", "coolant_reservoir", "intake_hose", "fuse_box", "air_cleaner", "brake_fluid_reservoir", "washer_fluid_filler", "oil_filler_cap", "oil_dipstick"],
  "images": [{
    "file_name": "vehicle001_001.jpg",
    "width": 1280,
    "height": 960,
    "vehicle": "rexton_sports",
    "model_year": 2023,
    "variant": "standard",
    "reviewed": true,
    "group_id": "vehicle001",
    "split": "train",
    "source": "자체 촬영 기록 또는 허용된 출처",
    "objects": [{"class": "battery", "bbox": [100, 100, 300, 300]}]
  }]
}
```

엔진 내부 상태, 액량, 누유, 부식 등 결함 여부는 이 부품 탐지 라벨로 학습되지 않습니다.

