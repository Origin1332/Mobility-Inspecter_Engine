"""Versioned engine-parts detection contract shared with Android."""
CLASSES = ['engine_cover', 'battery', 'coolant_reservoir', 'intake_hose', 'fuse_box',
           'air_cleaner', 'brake_fluid_reservoir', 'washer_fluid_filler', 'oil_filler_cap', 'oil_dipstick']
TASK = 'rexton_sports_2023_engine_parts'
SIZES = (640, 960, 1280)

def candidates(size):
    if size not in SIZES:
        raise ValueError('Supported input sizes: 640, 960, 1280')
    return sum((size // stride) ** 2 for stride in (8, 16, 32))

