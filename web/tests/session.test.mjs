import test from 'node:test';
import assert from 'node:assert/strict';
import {fromPredictions,revise,validateBox,validateSession,record} from '../src/session.js';
const prediction={class:'battery',box:[10,20,50,60],score:.82};
const session=()=>({schema_version:2,id:'one',title:'검사',note:'메모',width:100,height:80,threshold:.35,original:[structuredClone(prediction)],reviewed:fromPredictions([prediction]),source_image:new Blob(['photo']),display_image:'data:image/jpeg;base64,AA==',model:{sha256:'a'.repeat(64)}});
test('editing does not mutate original AI geometry and clears confidence and mismatched candidate',()=>{
 const s=session(),row=s.reviewed[0];row.candidate={part_number:'123',catalog_sha256:'b'.repeat(64)};
 const edited=revise(row,'fuse_box',[5,6,70,75],100,80);
 assert.deepEqual(s.original,[prediction]);assert.equal(row.class,'battery');assert.equal(edited.origin,'corrected');assert.equal(edited.score,null);assert.equal(edited.candidate,null);
});
test('unchanged edit retains AI confidence; geometry edit preserves same-class catalog choice',()=>{
 const row=fromPredictions([prediction])[0];row.candidate={part_number:'123'};
 assert.equal(revise(row,row.class,row.box,100,80).score,.82);
 const changed=revise(row,row.class,[1,2,30,40],100,80);assert.equal(changed.score,null);assert.deepEqual(changed.candidate,row.candidate);assert.notEqual(changed.candidate,row.candidate);
});
test('manual boxes never acquire AI origin and invalid geometry is rejected',()=>{
 const row={id:'manual',class:'battery',box:[1,2,30,40],origin:'manual',score:null,candidate:null};
 assert.equal(revise(row,'fuse_box',[5,6,50,60],100,80).origin,'manual');
 for(const b of [[0,0,101,20],[-1,0,10,10],[20,0,10,10],[0,0,0,10],[0,0,NaN,10]])assert.throws(()=>validateBox(b,100,80));
 assert.throws(()=>revise(row,'unknown',row.box,100,80));
});
test('record keeps original, exclusions, model and candidate provenance but omits photo bytes',()=>{
 const s=session();s.reviewed[0].excluded=true;s.reviewed[0].candidate={id:'catalog-1',catalog_sha256:'b'.repeat(64)};
 const r=record(s);assert.equal(r.source_image,undefined);assert.equal(r.display_image,undefined);assert.deepEqual(r.original,[prediction]);assert.equal(r.reviewed[0].excluded,true);assert.equal(r.model.sha256,'a'.repeat(64));assert.equal(r.reviewed[0].candidate.id,'catalog-1');
 r.original[0].box[0]=99;assert.equal(s.original[0].box[0],10);
});
test('structured clone roundtrip retains photos, edits, notes and original predictions',async()=>{
 const s=session();s.reviewed[0]=revise(s.reviewed[0],'battery',[1,2,50,60],100,80);
 const reopened=validateSession(structuredClone(s));assert.equal(await reopened.source_image.text(),'photo');assert.equal(reopened.note,'메모');assert.deepEqual(reopened.original,[prediction]);assert.equal(reopened.reviewed[0].origin,'corrected');
});
test('invalid session versions and duplicate box identifiers are rejected',()=>{
 assert.throws(()=>validateSession({...session(),schema_version:99}));
 const s=session();s.reviewed.push(structuredClone(s.reviewed[0]));assert.throws(()=>validateSession(s));
});
