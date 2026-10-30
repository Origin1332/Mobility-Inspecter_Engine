import {decode,validateManifest} from './core.js';
import * as ort from './vendor/ort.wasm.min.mjs';
let session=null,manifest=null;
self.onmessage=async({data:m})=>{try{
 if(m.type==='load'){
 const next=validateManifest(m.manifest);const bytes=m.bytes;
 const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),v=>v.toString(16).padStart(2,'0')).join('');
 if(hash!==next.sha256.toLowerCase())throw Error('모델 SHA-256 불일치');
 ort.env.wasm.numThreads=1;ort.env.wasm.proxy=false;ort.env.wasm.wasmPaths=new URL('./vendor/',self.location.href).href;
 const candidate=await ort.InferenceSession.create(bytes,{executionProviders:['wasm'],graphOptimizationLevel:'all'});
 try{
 if(candidate.inputNames.length!==1||candidate.outputNames.length!==1)throw Error('단일 입력/출력 모델만 지원합니다.');
 const tensor=new ort.Tensor('float32',new Float32Array(3*640*640),[1,3,640,640]);
 const out=await candidate.run({[candidate.inputNames[0]]:tensor});
 try{decode(out[candidate.outputNames[0]].data,out[candidate.outputNames[0]].dims,next.classes,{left:0,top:0,scale:1,width:640,height:640})}finally{Object.values(out).forEach(t=>t.dispose());tensor.dispose()}
 }catch(e){await candidate.release();throw e}
 if(session)await session.release();session=candidate;manifest=next;
 self.postMessage({id:m.id,ok:true,result:{classes:manifest.classes,sha256:manifest.sha256}});
 }else if(m.type==='infer'){
 if(!session)throw Error('모델을 먼저 불러오세요.');
 const start=performance.now(),tensor=new ort.Tensor('float32',m.pixels,[1,3,640,640]);
 let result;try{result=await session.run({[session.inputNames[0]]:tensor});const output=result[session.outputNames[0]];self.postMessage({id:m.id,ok:true,result:{detections:decode(output.data,output.dims,manifest.classes,m.geometry),elapsed_ms:performance.now()-start}})}finally{tensor.dispose();if(result)Object.values(result).forEach(t=>t.dispose())}
 }else throw Error('지원하지 않는 작업입니다.');
 }catch(e){self.postMessage({id:m.id,ok:false,error:String(e.message||e)})}};
