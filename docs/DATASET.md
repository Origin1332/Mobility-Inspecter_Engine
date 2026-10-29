# 엔진룸 seed v2 데이터 규격

최신 engine_room_seed_dataset.zip의 `dataset/annotations.json`과 `dataset/images/*.jpg`를 읽습니다. 검수용 가져오기는 기존 파일에 없는 차량/분할 정보를 추정해서 추가하지 않습니다.

클래스 ID 순서:
0 battery, 1 fuse_box, 2 air_cleaner, 3 coolant_reservoir, 4 fuel_filter, 5 oil_filler_cap, 6 oil_dipstick, 7 alternator, 8 ac_compressor, 9 belt_tensioner, 10 egr_valve, 11 washer_fluid_reservoir.

검수 규격:
- RGB JPG, 긴 변 1280px 이하, 원래 비율 유지. 이미지를 확대하거나 박스를 재추정하지 않습니다.
- bbox = [x_min, y_min, x_max, y_max] 픽셀 좌표. NaN·역전·영역 밖 좌표 거부.
- 여러 인스턴스는 모두 표시합니다. 빈 객체 목록은 허용하지만 완전한 음성 라벨로 보증하지 않습니다.
- source_manifest.json의 출처·검수·사용 조건을 검수 팩의 각 이미지에 보존합니다.
- 파일 경로·해상도·정확 중복을 검증합니다. 근접 중복은 기존 육안 검수와 추가 검토가 필요합니다.

현재 6장·30박스는 battery/fuse_box/air_cleaner/coolant_reservoir/oil_dipstick만 각 6개입니다. 나머지 7종은 라벨 0개이며 해당 부품이 존재하지 않는다는 뜻이 아닙니다.

검수 팩은 training_ready=false와 annotation_kind=reviewed_boxes를 저장합니다. 화면의 검수 박스에는 AI 신뢰도를 부여하지 않습니다.

별도의 완전한 학습 데이터에는 기존 prepare_dataset.py의 reviewed=true, vehicle=rexton_sports, model_year=2023, variant=standard, group_id, split(train/val/test) 필드가 필요합니다. 동일 차량은 한 분할에만 넣고 모든 클래스의 실제 예시를 준비해야 합니다. 현재 seed에 임의 분할을 붙여 모든 클래스가 학습되었다고 주장하지 않습니다.
