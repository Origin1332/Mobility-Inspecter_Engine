"""Read existing sparse annotations without inventing boxes or training splits."""
import collections
import hashlib
import io
import json
import math
import re
import zipfile
from pathlib import Path, PurePosixPath
from PIL import Image
from contract import CLASSES

MAX_BYTES = 128 * 1024 * 1024

def load_dataset(source):
    source=Path(source)
    with zipfile.ZipFile(source) as z:
        entries=z.infolist()
        if sum(e.file_size for e in entries)>MAX_BYTES or len(entries)>10000:
            raise ValueError('Dataset is too large for this review importer')
        if len({e.filename for e in entries})!=len(entries):
            raise ValueError('Duplicate ZIP entries')
        paths=[n for n in z.namelist() if PurePosixPath(n).name=='annotations.json']
        if len(paths)!=1: raise ValueError('Exactly one annotations.json required')
        doc=json.loads(z.read(paths[0]).decode('utf-8-sig'))
        if doc.get('classes')!=CLASSES: raise ValueError('Seed class order mismatch (12-class v2 contract required)')
        if not 1<=len(doc.get('images',[]))<=5000: raise ValueError('No images or too many images')
        manifest=json.loads(z.read('source_manifest.json').decode('utf-8-sig')) if 'source_manifest.json' in z.namelist() else []
        by_name={r['file_name']:r for r in manifest if 'file_name' in r}
        rows=[]; blobs={}; seen=set(); hashes=set(); counts=collections.Counter()
        for row in doc['images']:
            name=row['file_name']
            if not isinstance(name,str) or not re.fullmatch(r'[A-Za-z0-9_-]+\.jpg',name) or name.casefold() in seen:
                raise ValueError('Image filename must be unique safe ASCII .jpg')
            seen.add(name.casefold())
            raw=z.read(str(PurePosixPath(paths[0]).parent/'images'/name))
            if len(raw)>20*1024*1024: raise ValueError('Image too large')
            with Image.open(io.BytesIO(raw)) as im:
                if im.format!='JPEG' or im.mode!='RGB' or max(im.size)>1280:
                    raise ValueError('Expected RGB JPEG <=1280px')
                im.load(); w,h=im.size
                if [w,h]!=[row['width'],row['height']]: raise ValueError('Image dimensions mismatch')
                digest=hashlib.sha256(f'{w},{h}'.encode()+im.tobytes()).hexdigest()
            if digest in hashes: raise ValueError('Duplicate decoded image')
            hashes.add(digest)
            objects=[]
            if len(row['objects'])>200: raise ValueError('Too many boxes')
            for obj in row['objects']:
                if obj['class'] not in CLASSES: raise ValueError('Unknown class')
                box=obj['bbox']
                if len(box)!=4 or any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in box):
                    raise ValueError('Invalid bbox')
                x1,y1,x2,y2=box
                if not (0<=x1<x2<=w and 0<=y1<y2<=h): raise ValueError('Out-of-bounds bbox')
                objects.append({'class':obj['class'],'bbox':box})
                counts[obj['class']]+=1
            rows.append({'file_name':name,'width':w,'height':h,'objects':objects,
                         'source':by_name.get(name,{}),'pixel_sha256':digest,'file_sha256':hashlib.sha256(raw).hexdigest()})
            blobs['images/'+name]=raw
    return {'classes':CLASSES,'images':rows,'counts':{c:counts[c] for c in CLASSES},
            'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
            'annotation_kind':'reviewed_boxes','training_ready':False,
            'note':'Sparse seed annotations. Unlabelled classes are unknown, not negatives.'},blobs
