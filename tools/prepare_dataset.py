"""Reviewed pixel boxes -> YOLO with explicit vehicle-group splits."""
import argparse
import collections
import hashlib
import io
import json
import math
from pathlib import Path, PurePosixPath
import zipfile
from PIL import Image
from contract import CLASSES

def convert(source, output):
    source, output = Path(source), Path(output)
    if output.exists():
        raise ValueError('Output exists; use a new directory')
    rows, groups, pixels, filenames = [], {}, set(), set()
    counts = {s: collections.Counter() for s in ('train', 'val', 'test')}
    with zipfile.ZipFile(source) as z:
        paths = [n for n in z.namelist() if PurePosixPath(n).name == 'annotations.json']
        if len(paths) != 1 or len(set(z.namelist())) != len(z.namelist()):
            raise ValueError('Require one annotations.json and unique ZIP entries')
        doc = json.loads(z.read(paths[0]).decode('utf-8-sig'))
        if doc['classes'] != CLASSES:
            raise ValueError('Engine class order mismatch')
        for row in doc['images']:
            name, split, group = row['file_name'], row['split'], row['group_id']
            if (not isinstance(name, str) or not name.lower().endswith('.jpg') or
                any(c in name for c in '/\\:') or name in ('.', '..') or name.casefold() in filenames):
                raise ValueError('Unsafe or duplicate image filename')
            filenames.add(name.casefold())
            if split not in counts or not isinstance(group, str) or not group.strip():
                raise ValueError('Require train/val/test and nonempty vehicle group_id')
            if groups.setdefault(group, split) != split:
                raise ValueError('Vehicle group leakage across splits')
            if (row.get('vehicle') != 'rexton_sports' or row.get('model_year') != 2023 or
                row.get('variant') != 'standard' or row.get('reviewed') is not True):
                raise ValueError('Require reviewed 2023 standard Rexton Sports; exclude Khan/Culmen/SUV')
            raw = z.read(str(PurePosixPath(paths[0]).parent / 'images' / name))
            with Image.open(io.BytesIO(raw)) as im:
                im.load()
                w, h = im.size
                if (im.format != 'JPEG' or im.mode != 'RGB' or max(w,h)>1280 or
                    [w,h] != [row['width'],row['height']]):
                    raise ValueError('Require RGB JPEG <=1280px with matching dimensions')
                digest = hashlib.sha256(f'{w},{h}'.encode()+im.tobytes()).hexdigest()
            if digest in pixels:
                raise ValueError('Duplicate decoded image; review near-duplicates manually too')
            pixels.add(digest)
            lines=[]
            for obj in row['objects']:
                cid=CLASSES.index(obj['class'])
                box=obj['bbox']
                if len(box)!=4 or any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in box):
                    raise ValueError('Invalid bbox numbers')
                x1,y1,x2,y2=box
                if not (0<=x1<x2<=w and 0<=y1<y2<=h):
                    raise ValueError('Invalid pixel bbox bounds')
                lines.append(f'{cid} {(x1+x2)/2/w:.8f} {(y1+y2)/2/h:.8f} {(x2-x1)/w:.8f} {(y2-y1)/h:.8f}')
                counts[split][CLASSES[cid]]+=1
            rows.append((row,raw,lines,digest))
    missing={s:[c for c in CLASSES if not counts[s][c]] for s in counts}
    if any(missing.values()):
        raise ValueError(f'All classes need reviewed examples in each split: {missing}')
    report={'classes':CLASSES,'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
            'split_counts':{s:dict(counts[s]) for s in counts},
            'images':[{k:r[k] for k in ('file_name','split','group_id')} | {'pixel_sha256':d} for r,_,_,d in rows]}
    for row,raw,lines,_ in rows:
        name,split=row['file_name'],row['split']
        for kind in ('images','labels'):
            (output/kind/split).mkdir(parents=True,exist_ok=True)
        (output/'images'/split/name).write_bytes(raw)
        (output/'labels'/split/Path(name).with_suffix('.txt')).write_text('\n'.join(lines)+'\n',encoding='utf-8')
    config={'path':output.resolve().as_posix(),'train':'images/train','val':'images/val','test':'images/test','names':CLASSES}
    (output/'data.yaml').write_text(json.dumps(config,indent=2),encoding='utf-8')
    (output/'dataset_report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('--source',required=True); p.add_argument('--output',required=True)
    a=p.parse_args(); print(json.dumps(convert(a.source,a.output),indent=2))

