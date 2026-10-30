import {unzip} from './vendor/fflate.js';
export async function hash(bytes){return [...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(v=>v.toString(16).padStart(2,'0')).join('')}
export async function unpack(file,allowed,maxBytes){
 if(file.size>maxBytes)throw Error('ZIP 파일이 허용 크기를 초과했습니다.');const bytes=new Uint8Array(await file.arrayBuffer());
 return new Promise((resolve,reject)=>{let total=0;unzip(bytes,{filter:entry=>{if(!allowed.includes(entry.name))return false;total+=entry.originalSize;if(!Number.isFinite(total)||total>maxBytes)throw Error('압축 해제 크기를 초과했습니다.');return true}},(err,files)=>{if(err){reject(err);return}if(allowed.some(k=>!files[k])){reject(Error('ZIP에 '+allowed.join(', ')+' 파일이 필요합니다.'));return}resolve(files)})});
}
let dbPromise;
function db(){return dbPromise??=new Promise((resolve,reject)=>{const r=indexedDB.open('mobility-engine-web-v1',2);r.onupgradeneeded=()=>{if(!r.result.objectStoreNames.contains('resources'))r.result.createObjectStore('resources');if(!r.result.objectStoreNames.contains('sessions'))r.result.createObjectStore('sessions',{keyPath:'id'})};r.onblocked=()=>reject(Error('다른 검사 창을 닫고 다시 실행하세요.'));r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)})}
export async function readResource(key){const d=await db();return new Promise((resolve,reject)=>{const r=d.transaction('resources').objectStore('resources').get(key);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)})}
export async function storeResource(key,value){const d=await db();return new Promise((resolve,reject)=>{const t=d.transaction('resources','readwrite');t.objectStore('resources').put(value,key);t.oncomplete=()=>resolve();t.onerror=()=>reject(t.error);t.onabort=()=>reject(t.error||Error('저장 중단'))})}

export async function saveSession(session){const d=await db();return new Promise((resolve,reject)=>{const t=d.transaction('sessions','readwrite');t.objectStore('sessions').put(session);t.oncomplete=resolve;t.onerror=()=>reject(t.error);t.onabort=()=>reject(t.error||Error('검사 저장 중단'))})}
export async function listSessions(){const d=await db();return new Promise((resolve,reject)=>{const t=d.transaction('sessions'),r=t.objectStore('sessions').openCursor(),rows=[];r.onsuccess=()=>{const c=r.result;if(c){const s=c.value;rows.push({id:s.id,title:s.title,updated_at:s.updated_at});c.continue()}else resolve(rows.sort((a,b)=>b.updated_at.localeCompare(a.updated_at)))};r.onerror=()=>reject(r.error)})}
export async function readSession(id){const d=await db();return new Promise((resolve,reject)=>{const r=d.transaction('sessions').objectStore('sessions').get(id);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)})}
