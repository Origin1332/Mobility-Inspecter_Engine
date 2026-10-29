"""Package reviewed seed boxes and a local parts catalogue for the Android app."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path
from dataset_io import load_dataset
from catalog_index import build_index
from contract import NAMES_KO, CLASS_SCHEMA

def build(dataset,catalog,output):
    output=Path(output)
    if output.exists(): raise ValueError('Output exists; choose a new file')
    inspection,images=load_dataset(dataset)
    index=build_index(catalog)
    inspection.update(schema_version=1,class_schema=CLASS_SCHEMA,names_ko=NAMES_KO,
                      catalog_sha256=index['source_sha256'])
    for row in inspection['images']:
        for obj in row['objects']:
            if not any(obj['class'] in r['relations'] for r in index['rows']):
                raise ValueError('No catalogue group for '+obj['class'])
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'x',zipfile.ZIP_DEFLATED) as z:
        z.writestr('inspection.json',json.dumps(inspection,ensure_ascii=False,indent=2))
        z.writestr('catalog.json',json.dumps(index,ensure_ascii=False,indent=2))
        z.write(catalog,'catalog.pdf')
        for path,raw in images.items(): z.writestr(path,raw)
    return {'images':len(inspection['images']),'boxes':sum(inspection['counts'].values()),
            'counts':inspection['counts'],'catalog_rows':len(index['rows']),
            'catalog_pages':index['page_count'],'bundle_sha256':hashlib.sha256(output.read_bytes()).hexdigest(),
            'training_ready':False}

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--dataset',required=True);p.add_argument('--catalog',required=True);p.add_argument('--output',required=True)
    a=p.parse_args();print(json.dumps(build(a.dataset,a.catalog,a.output),ensure_ascii=False,indent=2))
