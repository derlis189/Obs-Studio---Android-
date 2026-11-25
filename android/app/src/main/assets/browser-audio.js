// SPDX-License-Identifier: GPL-2.0-or-later
// Opt-in, per-page Web Audio capture. Normal origin/CORS restrictions apply.
(() => {
  if (window.__obsAudioCapture || !window.ObsPageAudio) return;
  const AudioCtx = window.AudioContext || window.webkitAudioContext;
  if (!AudioCtx) return;
  const context = new AudioCtx({sampleRate: 48000});
  const mix = context.createGain();
  const hooked = new Map();
  const elementSources = new WeakMap();
  let processor;
  function send(channels) {
    const n = channels[0].length;
    const pcm = new Uint8Array(n * 4);
    const view = new DataView(pcm.buffer);
    for (let i = 0; i < n; ++i) for (let ch = 0; ch < 2; ++ch) {
      const value = Math.max(-1, Math.min(1, channels[ch][i] || 0));
      view.setInt16((i * 2 + ch) * 2, Math.round(value * (value < 0 ? 32768 : 32767)), true);
    }
    let binary = '';
    for (let i = 0; i < pcm.length; ++i) binary += String.fromCharCode(pcm[i]);
    window.ObsPageAudio.pcm(btoa(binary), context.sampleRate, 2);
  }
  // One mixed stream per page avoids overlapping timestamps from multiple players.
  // ScriptProcessor is a compatibility path for pages whose CSP blocks worklets.
  processor = context.createScriptProcessor(1024, 2, 2);
  processor.onaudioprocess = event => {
    const input = event.inputBuffer;
    send([input.getChannelData(0), input.getChannelData(Math.min(1, input.numberOfChannels - 1))]);
    // The processor output stays silent: normal playback is routed separately.
  };
  mix.connect(processor);
  processor.connect(context.destination);
  function detach(element) {
    const entry = hooked.get(element);
    if (!entry) return;
    for (const event of ['volumechange', 'play', 'pause']) element.removeEventListener(event, entry.update);
    element.removeEventListener('emptied', entry.onEmptied);
    try { entry.source.disconnect(); entry.gain.disconnect(); } catch (_) {}
    hooked.delete(element);
  }
  function attach(element) {
    if (hooked.has(element)) return;
    try {
      let source;
      const stream = element.srcObject;
      if (stream && stream.getAudioTracks && stream.getAudioTracks().length) {
        source = context.createMediaStreamSource(new MediaStream(stream.getAudioTracks()));
      } else {
        // Avoid taking ownership of a cross-origin media element: the spec would
        // replace its playback with silence unless the resource opted into CORS.
        const url = new URL(element.currentSrc || element.src || '', location.href);
        if (!element.currentSrc && !element.src) return;
        if (url.origin !== location.origin && url.protocol !== 'blob:' && url.protocol !== 'data:' && !element.crossOrigin) return;
        source = elementSources.get(element);
        if (!source) { source = context.createMediaElementSource(element); elementSources.set(element, source); }
        source.connect(context.destination); // Preserve existing local playback.
      }
      const gain = context.createGain();
      source.connect(gain); gain.connect(mix);
      const update = () => { gain.gain.value = element.paused || element.muted ? 0 : element.volume; };
      element.addEventListener('volumechange', update);
      element.addEventListener('play', update);
      element.addEventListener('pause', update);
      const onEmptied = () => detach(element);
      element.addEventListener('emptied', onEmptied, {once: true});
      hooked.set(element, {source, gain, update, onEmptied});
      update();
      context.resume().catch(() => {});
    } catch (_) { /* Already owned by the page or origin policy: preserve playback. */ }
  }
  const scan = () => {
    document.querySelectorAll('audio,video').forEach(attach);
    for (const element of hooked.keys()) if (!element.isConnected) detach(element);
  };
  const observer = new MutationObserver(scan);
  observer.observe(document.documentElement, {childList: true, subtree: true});
  document.addEventListener('playing', event => attach(event.target), true);
  window.__obsAudioCapture = {context, connect: node => node.connect(mix)};
  window.addEventListener('pagehide', () => { observer.disconnect(); context.close(); }, {once: true});
  scan();
})();
