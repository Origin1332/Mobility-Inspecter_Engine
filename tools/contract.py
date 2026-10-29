"""Canonical IDs from engine_room_seed_dataset.zip, in the original order."""
CLASSES = ['battery', 'fuse_box', 'air_cleaner', 'coolant_reservoir', 'fuel_filter',
           'oil_filler_cap', 'oil_dipstick', 'alternator', 'ac_compressor',
           'belt_tensioner', 'egr_valve', 'washer_fluid_reservoir']
NAMES_KO = ['배터리', '퓨즈 박스', '에어클리너', '냉각수 보조탱크', '연료 필터',
            '엔진오일 주입구 캡', '엔진오일 레벨 게이지', '발전기', '에어컨 컴프레서',
            '벨트 텐셔너', 'EGR 밸브', '워셔액 탱크']
TASK = 'rexton_sports_2023_engine_parts'
CLASS_SCHEMA = 'engine-seed-v2'
SIZES = (640, 960, 1280)

def candidates(size):
    if size not in SIZES:
        raise ValueError('Supported input sizes: 640, 960, 1280')
    return sum((size // stride) ** 2 for stride in (8, 16, 32))
