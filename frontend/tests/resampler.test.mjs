import test from 'node:test';
import assert from 'node:assert/strict';
import { PcmResampler } from '../src/app/audio-resampler.mjs';
test('48kHz stream stays aligned over many packets', () => {
 const r = new PcmResampler(48000); let count = 0;
 for(let i=0;i<480;i++) count += r.process(new Float32Array(1000)).byteLength / 2;
 assert.ok(Math.abs(count - 160000) <= 1);
});
test('44.1kHz fractional samples are preserved', () => {
 const r = new PcmResampler(44100); let count = 0;
 for(let i=0;i<441;i++) count += r.process(new Float32Array(1000)).byteLength / 2;
 assert.ok(Math.abs(count - 160000) <= 1);
});
test('PCM is clipped and little endian', () => {
 const r=new PcmResampler(16000);
 const view=new DataView(r.process(new Float32Array([2,-2,.5,0])));
 assert.equal(view.getInt16(0,true),32767);assert.equal(view.getInt16(2,true),-32768);
});
