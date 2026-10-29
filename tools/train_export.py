"""Train a baseline/candidate or export a trained checkpoint for Android."""
import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path
from contract import CLASSES, TASK, SIZES, CLASS_SCHEMA, candidates

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--data',required=True)
    p.add_argument('--weights',choices=['yolov8n.pt','yolo11n.pt','yolo11s.pt'],default='yolo11s.pt')
    p.add_argument('--imgsz',type=int,choices=SIZES,default=960)
    p.add_argument('--epochs',type=int,default=150)
    p.add_argument('--batch',type=int,default=4)
    p.add_argument('--device',default='cpu')
    p.add_argument('--name',default='engine-yolo11s-960')
    p.add_argument('--output',default='models/engine-parts')
    p.add_argument('--checkpoint')
    p.add_argument('--architecture',choices=['yolov8','yolo11'],default='yolo11')
    a=p.parse_args()
    import numpy as np
    import onnx
    import onnxruntime as ort
    import yaml
    from ultralytics import YOLO
    data_path=Path(a.data).resolve()
    data=yaml.safe_load(data_path.read_text(encoding='utf-8-sig'))
    if data['names']!=CLASSES or not data.get('test'):
        raise ValueError('Require engine classes and held-out test split')
    report=json.loads((data_path.parent/'dataset_report.json').read_text(encoding='utf-8-sig'))
    if any(report['split_counts'].get(s,{}).get(c,0)<1 for s in ('train','val','test') for c in CLASSES):
        raise ValueError('Missing class coverage; prepare reviewed dataset first')
    output=Path(a.output)
    if output.exists() or output.with_suffix('.zip').exists():
        raise ValueError('Output exists; choose a new name')
    if a.checkpoint:
        if not Path(a.checkpoint).is_file(): raise ValueError('Checkpoint not found')
        model=YOLO(a.checkpoint)
        architecture=a.architecture
    else:
        model=YOLO(a.weights)
        model.train(data=str(data_path),epochs=a.epochs,imgsz=a.imgsz,batch=a.batch,device=a.device,
                    seed=42,deterministic=True,fliplr=0.0,flipud=0.0,workers=0,
                    close_mosaic=15,patience=30,project='runs',name=a.name,exist_ok=False)
        model=YOLO(model.trainer.best)
        architecture='yolov8' if a.weights.startswith('yolov8') else 'yolo11'
    if [model.names[i] for i in range(len(model.names))]!=CLASSES:
        raise ValueError('Checkpoint class order mismatch')
    exported=Path(model.export(format='onnx',imgsz=a.imgsz,batch=1,dynamic=False,
                              simplify=False,opset=17,half=False,nms=False,device='cpu'))
    onnx.checker.check_model(onnx.load(str(exported)))
    session=ort.InferenceSession(str(exported),providers=['CPUExecutionProvider'])
    inputs=session.get_inputs()
    if len(inputs)!=1 or inputs[0].shape!=[1,3,a.imgsz,a.imgsz] or len(session.get_outputs())!=1:
        raise ValueError('Unsupported ONNX contract')
    pred=session.run(None,{inputs[0].name:np.zeros((1,3,a.imgsz,a.imgsz),dtype=np.float32)})[0]
    if pred.shape!=(1,4+len(CLASSES),candidates(a.imgsz)) or not np.isfinite(pred).all():
        raise ValueError(f'Unsupported output: {pred.shape}')
    if exported.stat().st_size>99*1024*1024: raise ValueError('Model exceeds Android package limit')
    output.mkdir(parents=True)
    shutil.copyfile(exported,output/'model.onnx')
    manifest={'schema_version':1,'architecture':architecture,'task':TASK,'input_size':a.imgsz,
              'classes':CLASSES,'class_schema':CLASS_SCHEMA,'sha256':hashlib.sha256(exported.read_bytes()).hexdigest(),
              'dataset_sha256':report['source_sha256'],
              'description':'Parts detection only; no fault diagnosis. Field performance unverified.'}
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    with zipfile.ZipFile(output.with_suffix('.zip'),'w',zipfile.ZIP_DEFLATED) as z:
        for name in ('model.onnx','manifest.json'): z.write(output/name,name)
    print(output.with_suffix('.zip').resolve())

if __name__=='__main__': main()

