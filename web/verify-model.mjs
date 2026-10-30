// Optional numerical parity check using the same WASM engine as the browser.
// node verify-model.mjs model.onnx input-f32.bin expected-output-f32.bin
import * as ort from './node_modules/onnxruntime-web/dist/ort.wasm.min.mjs';
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const [model,input,expected]=process.argv.slice(2);
if(!expected)throw Error('Usage: node verify-model.mjs model.onnx input-f32.bin expected-output-f32.bin');
ort.env.wasm.numThreads=1;ort.env.wasm.proxy=false;
const bytes=await readFile(input);const x=new Float32Array(bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength));assert.equal(x.length,3*640*640);
const session=await ort.InferenceSession.create(await readFile(model),{executionProviders:['wasm']});
const tensor=new ort.Tensor('float32',x,[1,3,640,640]);const output=await session.run({[session.inputNames[0]]:tensor});const actual=output[session.outputNames[0]];
const e=await readFile(expected),target=new Float32Array(e.buffer.slice(e.byteOffset,e.byteOffset+e.byteLength));assert.equal(actual.data.length,target.length);let maxError=0;for(let i=0;i<target.length;i++){assert(Number.isFinite(actual.data[i]));maxError=Math.max(maxError,Math.abs(actual.data[i]-target[i]));}
assert(maxError<.01,`max absolute error ${maxError}`);console.log(JSON.stringify({engine:'ONNX Runtime Web WASM',output_shape:actual.dims,max_absolute_error:maxError,passed:true}));
Object.values(output).forEach(t=>t.dispose());tensor.dispose();await session.release();
