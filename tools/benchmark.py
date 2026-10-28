"""Compare two checkpoints on one immutable held-out engine dataset."""
import argparse
import hashlib
import json
import math
from pathlib import Path
from contract import CLASSES, SIZES

def decision(baseline, candidate, min_gain):
    if not math.isfinite(min_gain) or min_gain<0: raise ValueError('Invalid minimum gain')
    values=[baseline['map50_95'],candidate['map50_95'],*baseline['recall'],*candidate['recall']]
    if (len(baseline['recall'])!=len(CLASSES) or len(candidate['recall'])!=len(CLASSES) or
        any(not math.isfinite(v) or not 0<=v<=1 for v in values)):
        raise ValueError('Incomplete or invalid metrics')
    gain=candidate['map50_95']-baseline['map50_95']
    return {'map_gain':gain,'accuracy_gate_passed':gain>=min_gain and all(
        c>=b for b,c in zip(baseline['recall'],candidate['recall'])),
        'deployment_ready':False,'note':'Target-tablet latency and field review still required.'}

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--data',required=True)
    p.add_argument('--baseline',required=True)
    p.add_argument('--candidate',required=True)
    p.add_argument('--baseline-size',type=int,choices=SIZES,default=640)
    p.add_argument('--candidate-size',type=int,choices=SIZES,default=960)
    p.add_argument('--device',default='cpu')
    p.add_argument('--min-gain',type=float,default=.03)
    p.add_argument('--output',default='reports/comparison.json')
    a=p.parse_args()
    import yaml
    from ultralytics import YOLO
    data_path=Path(a.data).resolve()
    data=yaml.safe_load(data_path.read_text(encoding='utf-8-sig'))
    if data['names']!=CLASSES or not data.get('test'): raise ValueError('Engine test data required')
    root=Path(data.get('path',data_path.parent))
    if not root.is_absolute(): root=(data_path.parent/root).resolve()
    # Hash actual images and labels, not just a possibly stale conversion report.
    test_path=Path(data['test'])
    if test_path.as_posix()!='images/test': raise ValueError('Use prepared images/test dataset')
    digest=hashlib.sha256()
    for kind in ('images','labels'):
        files=sorted((root/kind/'test').glob('*'))
        if not files: raise ValueError('Empty test split')
        for f in files:
            digest.update(f.relative_to(root).as_posix().encode()); digest.update(f.read_bytes())
    results={}
    for label,path,size in [('baseline',a.baseline,a.baseline_size),('candidate',a.candidate,a.candidate_size)]:
        checkpoint=Path(path)
        if not checkpoint.is_file(): raise ValueError('Supply trained local engine checkpoints')
        model=YOLO(str(checkpoint))
        if [model.names[i] for i in range(len(model.names))]!=CLASSES: raise ValueError('Class mismatch')
        m=model.val(data=str(data_path),split='test',imgsz=size,device=a.device,batch=1,
                    conf=.001,iou=.7,plots=True,project='runs/benchmark',name=label,exist_ok=False)
        ids=list(map(int,m.box.ap_class_index))
        if set(ids)!=set(range(len(CLASSES))): raise ValueError('Test split lacks classes')
        recall=[float(m.box.r[ids.index(i)]) for i in range(len(CLASSES))]
        results[label]={'map50_95':float(m.box.map),'map50':float(m.box.map50),'recall':recall,
                        'imgsz':size,'checkpoint_sha256':hashlib.sha256(checkpoint.read_bytes()).hexdigest(),
                        'speed_ms':{k:float(v) for k,v in m.speed.items()}}
    result={'test_sha256':digest.hexdigest(),'classes':CLASSES,**results,
            **decision(results['baseline'],results['candidate'],a.min_gain),
            'min_map_gain':a.min_gain,'recall_definition':'Per-class recall at validator-selected F1 operating point; not fixed-threshold field recall.'}
    out=Path(a.output)
    if out.exists(): raise ValueError('Report exists; choose a new output')
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))

if __name__=='__main__': main()

