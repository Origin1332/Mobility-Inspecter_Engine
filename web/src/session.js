import {CLASSES} from './core.js';
export const SESSION_VERSION=2;
export function copy(value){return structuredClone(value)}
export function validateBox(box,width,height){
 if(!Array.isArray(box)||box.length!==4||!box.every(Number.isFinite)||box[0]<0||box[1]<0||box[2]>width||box[3]>height||box[2]<=box[0]||box[3]<=box[1])throw Error('박스 좌표는 사진 안에서 좌상단보다 우하단이 커야 합니다.');
 return [...box];
}
export function fromPredictions(predictions){return predictions.map((p,i)=>({...copy(p),id:'ai-'+i,origin:'ai',excluded:false,candidate:null}))}
export function revise(row,cls,box,width,height){
 if(!CLASSES.includes(cls))throw Error('지원하지 않는 부품입니다.');
 const changed=cls!==row.class||JSON.stringify(box)!==JSON.stringify(row.box);
 return {...copy(row),class:cls,box:validateBox(box,width,height),score:changed?null:row.score,origin:changed?(row.origin==='manual'?'manual':'corrected'):row.origin,candidate:cls===row.class?copy(row.candidate):null};
}
export function validateSession(s){
 if(s?.schema_version!==SESSION_VERSION||typeof s.id!=='string'||!s.id||typeof s.title!=='string'||typeof s.note!=='string'||!Number.isFinite(s.width)||!Number.isFinite(s.height)||s.width<=0||s.height<=0||!Number.isFinite(s.threshold)||s.threshold<.05||s.threshold>.95||!Array.isArray(s.original)||!Array.isArray(s.reviewed)||s.reviewed.length>500)throw Error('검사 기록 형식이 올바르지 않습니다.');
 const ids=new Set();for(const row of s.reviewed){if(!CLASSES.includes(row.class)||typeof row.id!=='string'||ids.has(row.id)||!['ai','corrected','manual'].includes(row.origin)||typeof row.excluded!=='boolean')throw Error('검사 박스 정보가 올바르지 않습니다.');ids.add(row.id);validateBox(row.box,s.width,s.height)}
 return s;
}
export function record(s){validateSession(s);const {source_image,display_image,...metadata}=s;return copy(metadata)}
