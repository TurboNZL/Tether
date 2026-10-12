/*
 * Injected into every page of the embedded web session (document start, and again after load; it is
 * idempotent). The native side talks to it through window.__tether and hears back through
 * window.TetherBridge.post(type, json).
 *
 *  - desktop emulation: desktop-sized layout viewport (scalable: "zoom") and desktop navigator properties
 *  - virtual mic:       audio captured natively from a chosen input device, played into the page as its microphone
 *  - mute:              switch the outgoing audio off and on without touching the page's own UI
 *  - volume:            0-200% gain on the other person's voice (only routed when it is not 100%)
 *  - mic volume:        0-300% gain on what the other person hears
 *  - level meter:       the live outgoing level of the call
 *  - playback test:     records a few seconds the way the call captures, plays them back, and rates the level
 */
(function () {
  if (window.__tether) return;
  var T = window.__tether = {};
  var WIDTH = 1280;
  var cfg = { muted: false, volume: 1, aec: true, zoom: 1, micGain: 1 };
  var tracks = [], pcs = [];

  function post(type, data) {
    try { if (window.TetherBridge) window.TetherBridge.post(type, typeof data === 'string' ? data : JSON.stringify(data)); } catch (e) { /* no bridge */ }
  }

  // ---- desktop emulation ----------------------------------------------------------------
  try {
    var navProto = Object.getPrototypeOf(navigator);
    Object.defineProperty(navProto, 'platform', { get: function () { return 'Linux x86_64'; }, configurable: true });
    if (window.NavigatorUAData) {
      Object.defineProperty(window.NavigatorUAData.prototype, 'mobile', { get: function () { return false; }, configurable: true });
      Object.defineProperty(window.NavigatorUAData.prototype, 'platform', { get: function () { return 'Linux'; }, configurable: true });
    }
  } catch (e) { /* best effort */ }

  // Pages that ask for a device-width layout would otherwise get the phone layout whatever the UA says.
  // The layout width is also the zoom: a narrower layout shows the same page bigger (like browser zoom,
  // everything is laid out again, nothing is just magnified).
  function viewport() {
    var m = document.querySelector('meta[name=viewport]');
    if (!m) {
      if (!document.head) return;
      m = document.createElement('meta');
      m.name = 'viewport';
      document.head.appendChild(m);
    }
    var w = Math.round(WIDTH / cfg.zoom);
    var want = 'width=' + w + ', initial-scale=' + ((window.screen.width || w) / w).toFixed(3);
    if (m.content !== want) m.content = want;
  }
  viewport();
  window.addEventListener('orientationchange', function () { setTimeout(viewport, 300); });
  T.setZoom = function (z) { cfg.zoom = Math.max(0.5, Math.min(3, +z || 1)); viewport(); };
  new MutationObserver(viewport).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });

  // ---- audio context shared by the volume path and the meters --------------------------
  var ctx = null, gain = null;
  function ensureCtx() {
    if (!ctx) {
      var AC = window.AudioContext || window.webkitAudioContext;
      // 48 kHz so the natively captured frames need no resampling (and so no seams between them).
      try { ctx = new AC({ sampleRate: 48000 }); } catch (e) { ctx = new AC(); }
      gain = ctx.createGain();
      gain.connect(ctx.destination);
    }
    if (ctx.state === 'suspended') ctx.resume().catch(function () {});
    return ctx;
  }

  // ---- peer connections: remembered so the outgoing audio can be muted and replaced ---------
  ['RTCPeerConnection', 'webkitRTCPeerConnection'].forEach(function (n) {
    var P = window[n];
    if (!P) return;
    window[n] = new Proxy(P, {
      construct: function (target, args, newTarget) {
        var pc = Reflect.construct(target, args, newTarget);
        pcs.push(pc);
        return pc;
      }
    });
  });
  function livePcs() {
    pcs = pcs.filter(function (p) { return p.connectionState !== 'closed'; });
    return pcs;
  }
  function outgoing() {
    var senders = [];
    livePcs().forEach(function (p) {
      p.getSenders().forEach(function (s) { if (s.track && s.track.kind === 'audio') senders.push(s); });
    });
    return senders;
  }

  // ---- mute ----------------------------------------------------------------------------------
  // The page flips the very same `enabled` switch on its tracks (its own mute button does), so a plain
  // `track.enabled = false` can be undone by the page at any moment. Instead every outgoing audio track is
  // locked: the page still reads and writes `enabled` as usual (and its own mute keeps working), but the
  // real value underneath is always "what the page set AND NOT our mute", applied synchronously on every
  // write. Every route a track can take to the call is hooked below, so none gets past the lock.
  var enabledProp = Object.getOwnPropertyDescriptor(MediaStreamTrack.prototype, 'enabled');
  var locked = [];
  function lock(t, initialWant) {
    if (!t || t.kind !== 'audio' || t.__tetherLocked || !enabledProp) return t;
    var want = initialWant === undefined ? enabledProp.get.call(t) : !!initialWant;
    Object.defineProperty(t, '__tetherLocked', { value: true, configurable: true });
    Object.defineProperty(t, '__tetherApply', {
      value: function () { enabledProp.set.call(t, want && !cfg.muted); }, configurable: true
    });
    Object.defineProperty(t, 'enabled', {
      configurable: true,
      get: function () { return want; },
      set: function (v) { want = !!v; enabledProp.set.call(t, want && !cfg.muted); }
    });
    t.__tetherApply();
    locked.push(t);
    return t;
  }
  function unlock(t) { // a track that is not part of the call (a test), so never muted with it
    if (!t || !t.__tetherLocked) return;
    delete t.enabled; delete t.__tetherLocked; delete t.__tetherApply;
    enabledProp.set.call(t, true);
    locked = locked.filter(function (x) { return x !== t; });
  }
  function hook(proto, name, before) {
    var o = proto && proto[name];
    if (typeof o !== 'function') return;
    proto[name] = function () {
      try { before.apply(this, arguments); } catch (e) { /* never break the page */ }
      return o.apply(this, arguments);
    };
  }
  hook(window.RTCPeerConnection && RTCPeerConnection.prototype, 'addTrack', function (t) { lock(t); });
  hook(window.RTCPeerConnection && RTCPeerConnection.prototype, 'addTransceiver', function (t) { if (t && typeof t !== 'string') lock(t); });
  hook(window.RTCRtpSender && RTCRtpSender.prototype, 'replaceTrack', function (t) { lock(t); });
  (function () {
    var oc = MediaStreamTrack.prototype.clone;
    MediaStreamTrack.prototype.clone = function () {
      var c = oc.apply(this, arguments);
      // A clone of a locked track is locked too, and starts with the page's own setting (not our mute).
      try { if (this.__tetherLocked) lock(c, this.enabled); } catch (e) { /* ignore */ }
      return c;
    };
  })();
  function silent() { // is every audio track the call is sending really switched off underneath?
    var senders = outgoing();
    return { senders: senders.length, silent: senders.every(function (s) { return enabledProp.get.call(s.track) === false; }) };
  }
  function applyMute() {
    tracks = tracks.filter(function (t) { return t.readyState !== 'ended'; });
    locked = locked.filter(function (t) { return t.readyState !== 'ended'; });
    outgoing().forEach(function (s) { lock(s.track); }); // anything that reached a sender some other way
    locked.forEach(function (t) { t.__tetherApply(); });
  }
  setInterval(applyMute, 500);
  T.setMute = function (m) {
    cfg.muted = !!m;
    applyMute();
  };
  // The app asks (evaluateJavascript is not slowed down like page timers are in the background) and gets
  // the real state back: are the tracks the call is sending really switched off underneath?
  T.muteStatus = function () {
    applyMute();
    var r = silent();
    return { muted: cfg.muted, senders: r.senders, silent: cfg.muted ? r.silent : true };
  };

  // ---- microphone selection (getUserMedia wrapper) ----------------------------------------------
  var md = navigator.mediaDevices;
  var orig = md && md.getUserMedia ? md.getUserMedia.bind(md) : null;

  function adopt(stream) {
    stream.getAudioTracks().forEach(function (t) { tracks.push(t); lock(t); });
  }

  // ---- virtual mic: audio captured natively from a chosen device, played into the page ----------------
  // The page cannot see the phone's individual inputs, so the app captures the chosen one itself and
  // pushes 16-bit PCM here; it is scheduled into a MediaStreamDestination that stands in for the mic.
  var vm = { label: '', on: false, dest: null, gain: null, nextFrame: 0, live: false, tracks: [] };
  function vmStream() {
    var c = ensureCtx();
    if (!vm.dest) {
      vm.dest = c.createMediaStreamDestination();
      vm.gain = c.createGain();
      vm.gain.gain.value = cfg.micGain;
      vm.gain.connect(vm.dest);
    }
    var t = vm.dest.stream.getAudioTracks()[0].clone();
    vm.tracks.push(t);
    vmCheck();
    return new MediaStream([t]);
  }
  // Tell the app when the page does / does not hold the virtual mic, so capture runs only when needed.
  function vmCheck() {
    vm.tracks = vm.tracks.filter(function (t) { return t.readyState === 'live'; });
    var want = vm.on && vm.tracks.length > 0;
    if (want !== vm.live) { vm.live = want; post('vmic', want ? 'on' : 'off'); }
  }
  setInterval(vmCheck, 1000);
  T.pcm = function (b64) {
    if (!ctx || !vm.dest) return;
    var bin = atob(b64), n = bin.length >> 1, u8 = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    var i16 = new Int16Array(u8.buffer, 0, n), f = new Float32Array(n);
    for (var j = 0; j < n; j++) f[j] = i16[j] / 32768;
    var buf = ctx.createBuffer(1, n, 48000);
    buf.copyToChannel(f, 0);
    var src = ctx.createBufferSource();
    src.buffer = buf;
    src.connect(vm.gain);
    // Schedule in whole samples, aimed at the middle of the sample: a fractional start time can round one
    // sample early and overlap the previous frame, which is an audible click every 40 ms.
    var sr = ctx.sampleRate, nowFrame = Math.round(ctx.currentTime * sr);
    if (vm.nextFrame < nowFrame + 0.03 * sr) vm.nextFrame = nowFrame + Math.round(0.16 * sr);   // (re)start with a cushion
    else if (vm.nextFrame > nowFrame + 0.6 * sr) return;       // too far behind: drop this frame to stay close to live
    src.start((vm.nextFrame + 0.5) / sr);
    vm.nextFrame += Math.round(n * sr / 48000);
  };

  // Mic volume for the page's own capture: route it through a gain stage, but only when the gain is not
  // 100% and only if the audio context is really running, so the call can never go silent because of it.
  var pageGains = [];
  function unity() { return Math.abs(cfg.micGain - 1) < 0.01; }
  async function processed(raw) {
    if (unity() || raw.getAudioTracks().length === 0) return raw;
    var c = ensureCtx();
    if (c.state !== 'running') { try { await c.resume(); } catch (e) { /* stays suspended */ } }
    if (c.state !== 'running') return raw;
    var src = c.createMediaStreamSource(raw), g = c.createGain(), dest = c.createMediaStreamDestination();
    g.gain.value = cfg.micGain;
    src.connect(g);
    g.connect(dest);
    pageGains.push(g);
    var out = dest.stream, t = out.getAudioTracks()[0], stop = t.stop.bind(t);
    t.stop = function () { stop(); raw.getTracks().forEach(function (r) { r.stop(); }); try { src.disconnect(); } catch (e) { /* gone */ } };
    raw.getVideoTracks().forEach(function (v) { out.addTrack(v); });
    return out;
  }

  // ---- device list: pages check it before asking for the mic ------------------------------------------
  // A WebView may list no microphone (or only nameless, id-less entries) until the page has captured once, and
  // web apps then say "no microphone found" without ever asking. The app picks the real input itself, so the
  // page is always shown one usable, named microphone; its id is accepted (and ignored) when it asks for it.
  var MIC_ID = 'default';
  function fakeMic() {
    var d = { deviceId: MIC_ID, kind: 'audioinput', label: vm.label || 'Default microphone', groupId: 'tether-mic' };
    d.toJSON = function () { return { deviceId: d.deviceId, kind: d.kind, label: d.label, groupId: d.groupId }; };
    return d;
  }
  if (md && md.enumerateDevices) {
    var origEnum = md.enumerateDevices.bind(md);
    md.enumerateDevices = async function () {
      var list = [];
      try { list = Array.prototype.slice.call(await origEnum()); } catch (e) { /* none listed */ }
      var usable = list.some(function (d) { return d.kind === 'audioinput' && d.deviceId; });
      if (usable && !vm.on) return list;
      var out = list.filter(function (d) { return d.kind !== 'audioinput' || (d.deviceId && !vm.on); });
      out.unshift(fakeMic());
      return out;
    };
  }
  // The page's device choice means nothing here (the app or the system decides), and an id it was handed from
  // the list above must not make the capture fail, so device ids are dropped from the audio constraints.
  function plainAudio(a) {
    if (!a || a === true) return a;
    var o = Object.assign({}, a);
    delete o.deviceId;
    return o;
  }

  if (orig) {
    md.getUserMedia = async function (c) {
      var stream;
      if (c && c.audio && vm.on) {
        stream = vmStream();
        if (c.video) {
          try { (await orig({ video: c.video })).getVideoTracks().forEach(function (t) { stream.addTrack(t); }); } catch (e) { /* audio only */ }
        }
      } else {
        if (c && c.audio) c = Object.assign({}, c, { audio: plainAudio(c.audio) });
        if (c && c.audio && !cfg.aec) {
          var audio = c.audio === true ? {} : Object.assign({}, c.audio);
          audio.echoCancellation = false; audio.noiseSuppression = false; audio.autoGainControl = false;
          c = Object.assign({}, c, { audio: audio });
        }
        try {
          stream = await orig(c);
        } catch (e) {
          // An over-specific request (rate, channels, ...) the device cannot meet reads as "no microphone".
          if (!(c && c.audio && e && (e.name === 'OverconstrainedError' || e.name === 'NotFoundError'))) throw e;
          stream = await orig(Object.assign({}, c, { audio: true }));
        }
        if (c && c.audio) stream = await processed(stream);
      }
      adopt(stream);
      return stream;
    };
  }

  // Swap the live outgoing track (a new mic, or echo cancellation toggled) without renegotiating.
  async function recapture() {
    var senders = outgoing();
    if (!senders.length || !orig) return;
    var fresh = (await md.getUserMedia({ audio: true })).getAudioTracks()[0];
    for (var i = 0; i < senders.length; i++) {
      var old = senders[i].track;
      await senders[i].replaceTrack(fresh);
      old.stop(); // stop() does not fire 'ended', so the page does not mistake it for a lost mic
    }
    applyMute();
  }
  // Use the natively captured device (true) or the page's own default capture (false); switches a live call.
  T.setVirtualMic = async function (on) {
    // Moving from one native input to another changes only what the app feeds in: the track the call is
    // already sending stays, so nothing is renegotiated or replaced (which is what broke calls).
    if (vm.on === !!on) { vm.nextFrame = 0; return; }
    vm.on = !!on;
    vmCheck();
    await recapture();
  };
  T.setMicGain = async function (g) {
    var wasUnity = unity();
    cfg.micGain = Math.max(0, Math.min(3, +g || 0));
    pageGains.forEach(function (n) { n.gain.value = cfg.micGain; });
    if (vm.gain) vm.gain.gain.value = cfg.micGain;
    // Moving to or from 100% switches the live page capture between the plain and the gain path.
    if (!vm.on && wasUnity !== unity()) await recapture();
  };
  T.setProcessing = async function (on) {
    cfg.aec = !!on;
    if (!vm.on) await recapture();   // with the virtual mic the app applies it natively
  };

  // ---- volume: gain on the other person's voice ----------------------------------------------------
  var routed = [];
  function remoteTracks() {
    var out = [];
    livePcs().forEach(function (p) {
      p.getReceivers().forEach(function (r) {
        if (r.track && r.track.kind === 'audio' && r.track.readyState === 'live') out.push(r.track);
      });
    });
    return out;
  }
  function isRouted(t) { return routed.some(function (r) { return r.track === t; }); }
  function sweep() {
    // Only silence the page's own playback once our path is actually running, so a stuck audio
    // context can never make the call inaudible.
    if (!ctx || ctx.state !== 'running') return;
    document.querySelectorAll('audio,video').forEach(function (el) {
      var s = el.srcObject;
      if (!s || !s.getAudioTracks || !s.getAudioTracks().some(isRouted)) return;
      if (!el.__tetherMuted) { el.__tetherMuted = true; el.__tetherWas = el.muted; }
      el.muted = true;
    });
  }
  function unroute() {
    routed.forEach(function (r) { try { r.src.disconnect(); } catch (e) { /* gone */ } });
    routed = [];
    document.querySelectorAll('audio,video').forEach(function (el) {
      if (el.__tetherMuted) { el.muted = el.__tetherWas; el.__tetherMuted = false; }
    });
  }
  function applyVolume() {
    if (Math.abs(cfg.volume - 1) < 0.01) { if (routed.length) unroute(); return; }
    ensureCtx();
    gain.gain.value = cfg.volume;
    remoteTracks().forEach(function (t) {
      if (isRouted(t)) return;
      var src = ctx.createMediaStreamSource(new MediaStream([t]));
      src.connect(gain);
      routed.push({ track: t, src: src });
    });
    sweep();
  }
  setInterval(applyVolume, 1000);
  T.setVolume = function (v) { cfg.volume = Math.max(0, Math.min(2, +v || 0)); applyVolume(); };

  // ---- level meters -------------------------------------------------------------------------------
  function startMeter(kind, getTrack) {
    var m = { timer: null, track: null, src: null, an: null, buf: null };
    m.timer = setInterval(function () {
      var t = getTrack();
      if (t !== m.track) {
        if (m.src) { try { m.src.disconnect(); } catch (e) { /* gone */ } m.src = null; }
        m.track = t;
        if (t) {
          var c = ensureCtx();
          m.src = c.createMediaStreamSource(new MediaStream([t]));
          m.an = c.createAnalyser();
          m.an.fftSize = 4096; // ~85 ms of samples per reading, so nothing falls between ticks
          m.buf = new Float32Array(4096);
          m.src.connect(m.an);
        }
      }
      var rms = 0;
      if (t && m.an) {
        m.an.getFloatTimeDomainData(m.buf);
        var sum = 0;
        for (var i = 0; i < m.buf.length; i++) sum += m.buf[i] * m.buf[i];
        rms = Math.sqrt(sum / m.buf.length);
      }
      post(kind, rms.toFixed(4));
    }, 100);
    m.stop = function () {
      clearInterval(m.timer);
      if (m.src) { try { m.src.disconnect(); } catch (e) { /* gone */ } }
    };
    return m;
  }
  var callMeter = null;

  // The outgoing level of the call itself (muted shows as silence).
  T.setMeter = function (on) {
    if (callMeter) { callMeter.stop(); callMeter = null; }
    if (on) callMeter = startMeter('level', function () {
      return tracks.find(function (t) { return t.readyState === 'live'; });
    });
  };

  // ---- playback test: record the way the call captures (including the mic volume), play it back, rate it ----
  var lb = null;
  function rate(peak) {
    var db = peak > 0 ? 20 * Math.log10(peak) : -120;
    if (db >= -3) return 'clipping';
    if (db >= -21) return 'good';
    if (db >= -32) return 'quiet';
    return 'silent';
  }
  T.loopback = async function (sec) {
    if (lb) return;
    lb = true;
    var stream = null, sp = null, src = null, quiet = null;
    try {
      if (!orig) throw new Error('no microphone API');
      stream = await md.getUserMedia({ audio: true });
      var tr = stream.getAudioTracks()[0];
      tracks = tracks.filter(function (x) { return x !== tr; });
      unlock(tr); // a test is never part of the call, so never muted with it
      var c = ensureCtx(), chunks = [];
      src = c.createMediaStreamSource(stream);
      sp = c.createScriptProcessor(4096, 1, 1);
      quiet = c.createGain();
      quiet.gain.value = 0;
      src.connect(sp); sp.connect(quiet); quiet.connect(c.destination);
      sp.onaudioprocess = function (e) { chunks.push(new Float32Array(e.inputBuffer.getChannelData(0))); };
      post('loopback', { state: 'recording', sec: sec });
      await new Promise(function (r) { setTimeout(r, sec * 1000); });
      sp.onaudioprocess = null;
      try { src.disconnect(); sp.disconnect(); quiet.disconnect(); } catch (e) { /* gone */ }
      stream.getTracks().forEach(function (t) { t.stop(); });
      var total = 0, peak = 0, sum = 0, i, j;
      chunks.forEach(function (k) { total += k.length; });
      var buf = c.createBuffer(1, Math.max(1, total), c.sampleRate), ch = buf.getChannelData(0), at = 0;
      chunks.forEach(function (k) {
        for (j = 0; j < k.length; j++) { var v = k[j]; ch[at++] = v; var a = Math.abs(v); if (a > peak) peak = a; sum += v * v; }
      });
      var rms = total ? Math.sqrt(sum / total) : 0, result = { peak: peak, rms: rms, verdict: rate(peak) };
      var play = c.createBufferSource();
      play.buffer = buf;
      play.connect(c.destination);
      play.onended = function () { lb = null; post('loopback', Object.assign({ state: 'done' }, result)); };
      post('loopback', Object.assign({ state: 'playing' }, result));
      play.start();
    } catch (e) {
      lb = null;
      if (stream) stream.getTracks().forEach(function (t) { t.stop(); });
      post('loopback', { state: 'error', message: (e && e.name ? e.name + ': ' : '') + (e && e.message || e) });
    }
  };

  // ---- misc ----------------------------------------------------------------------------------------
  T.configure = function (o) {
    if (!o) return;
    if ('aec' in o) cfg.aec = !!o.aec;
    if ('zoom' in o) T.setZoom(o.zoom);
    if ('micGain' in o) { cfg.micGain = Math.max(0, Math.min(3, +o.micGain || 0)); if (vm.gain) vm.gain.gain.value = cfg.micGain; }
    if ('micLabel' in o) vm.label = String(o.micLabel || '');
    if ('virtual' in o) { vm.on = !!o.virtual; vmCheck(); }
    if ('volume' in o) T.setVolume(o.volume);
    if ('muted' in o) T.setMute(o.muted);
  };
  T.info = function () {
    var vp = document.querySelector('meta[name=viewport]');
    var uad = navigator.userAgentData;
    post('info', {
      ua: navigator.userAgent, platform: navigator.platform, width: window.innerWidth, dpr: window.devicePixelRatio,
      viewport: vp ? vp.content : null, uaMobile: uad ? uad.mobile : null, host: location.host,
      mediaDevices: !!md, calls: livePcs().length
    });
  };
  T.state = function () {
    return { volume: cfg.volume, muted: cfg.muted, aec: cfg.aec, zoom: cfg.zoom, micGain: cfg.micGain, virtual: vm.on, routed: routed.length, ctx: ctx ? ctx.state : null, tracks: tracks.length };
  };
})();
