// SPDX-License-Identifier: GPL-2.0-or-later
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/browser-audio.js'), 'utf8');
let contexts = 0, createdSources = 0, processor, lastPcm;
const events = new Map();
function media(url) {
  const handlers = new Map();
  return {src: url, currentSrc: url, isConnected: true, paused: false, muted: false, volume: 1,
    addEventListener(name, callback) { handlers.set(name, callback); },
    removeEventListener(name, callback) { if (handlers.get(name) === callback) handlers.delete(name); },
    fire(name) { handlers.get(name)?.(); }};
}
const local = media('https://studio.invalid/tone.wav');
const foreign = media('https://foreign.invalid/no-cors.wav');
const node = () => ({connect() {}, disconnect() {}, gain: {value: 1}});
class Context {
  constructor() { ++contexts; this.sampleRate = 48000; this.destination = node(); }
  createGain() { return node(); }
  createScriptProcessor() { processor = node(); return processor; }
  createMediaElementSource(element) {
    assert.equal(element, local, 'Foreign media without CORS must keep its normal playback');
    ++createdSources;
    assert.equal(createdSources, 1, 'A changed URL must reuse its existing media-element node');
    return node();
  }
  resume() { return Promise.resolve(); }
  close() { return Promise.resolve(); }
}
const window = {AudioContext: Context, ObsPageAudio: {pcm(encoded, rate, channels) {
  lastPcm = {bytes: Buffer.from(encoded, 'base64'), rate, channels};
}}, addEventListener() {}};
const sandbox = vm.createContext({window, URL, WeakMap, Map, Uint8Array, DataView, Math,
  btoa: value => Buffer.from(value, 'binary').toString('base64'),
  location: new URL('https://studio.invalid/page'),
  MutationObserver: class {observe() {} disconnect() {}},
  document: {documentElement: {}, querySelectorAll: () => [local, foreign],
    addEventListener: (name, callback) => events.set(name, callback)}});
vm.runInContext(source, sandbox);
vm.runInContext(source, sandbox);
assert.equal(contexts, 1, 'Repeated injection must not duplicate the audio graph');
const signal = new Float32Array([-1, -.5, 0, .5, 1]);
processor.onaudioprocess({inputBuffer: {numberOfChannels: 1, getChannelData: () => signal}});
assert.equal(lastPcm.rate, 48000);
assert.equal(lastPcm.channels, 2);
assert.equal(lastPcm.bytes.length, 20);
assert.equal(lastPcm.bytes.readInt16LE(0), -32768);
assert.equal(lastPcm.bytes.readInt16LE(2), -32768);
assert.equal(lastPcm.bytes.readInt16LE(8), 0);
assert.equal(lastPcm.bytes.readInt16LE(16), 32767);
local.fire('emptied');
local.src = local.currentSrc = 'https://studio.invalid/next.wav';
events.get('playing')({target: local});
assert.equal(createdSources, 1);
console.log('BROWSER_AUDIO_PASS PCM16, stereo layout, origin handling, reinjection and media URL changes');
