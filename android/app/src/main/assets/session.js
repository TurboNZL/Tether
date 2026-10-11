/*
 * Injected into every page of the embedded web session (document start, and again after load; it is
 * idempotent). The native side talks to it through window.__tether and hears back through
 * window.TetherBridge.post(type, json).
 *
 *  - desktop emulation: desktop-sized layout viewport and desktop navigator properties
 *  - mic selection:     prefer the phone's own mic over a Bluetooth headset, or the one chosen in the app
 *  - mute:              switch the outgoing audio off and on without touching the page's own UI
 *  - volume:            0-200% gain on the other person's voice (only routed when it is not 100%)
 *  - level meters:      the live outgoing level, and a stand-alone mic test
 */
(function () {
  if (window.__tether) return;
  var T = window.__tether = {};
  var WIDTH = 1280;
  var BT = /bluetooth|bt[\s_-]|headset|buds|airpods|a2dp|hfp|sco/i;
  var cfg = { muted: false, volume: 1, mic: '', aec: true };
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
  function viewport() {
    var m = document.querySelector('meta[name=viewport]');
    if (!m) {
      if (!document.head) return;
      m = document.createElement('meta');
      m.name = 'viewport';
      document.head.appendChild(m);
    }
    if (m.content !== 'width=' + WIDTH) m.content = 'width=' + WIDTH;
  }
  viewport();
  new MutationObserver(viewport).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });

  // ---- audio context shared by the volume path and the meters --------------------------
  var ctx = null, gain = null;
  function ensureCtx() {
    if (!ctx) {
      var AC = window.AudioContext || window.webkitAudioContext;
      ctx = new AC();
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
  var muteTimer = null;
  function applyMute() {
    tracks = tracks.filter(function (t) { return t.readyState !== 'ended'; });
    tracks.forEach(function (t) { t.enabled = !cfg.muted; });
    outgoing().forEach(function (s) { s.track.enabled = !cfg.muted; });
  }
  T.setMute = function (m) {
    cfg.muted = !!m;
    applyMute();
    // The page may swap tracks behind our back; re-assert while muted.
    if (cfg.muted && !muteTimer) muteTimer = setInterval(applyMute, 2000);
    if (!cfg.muted && muteTimer) { clearInterval(muteTimer); muteTimer = null; }
  };

  // ---- microphone selection (getUserMedia wrapper) ----------------------------------------------
  var md = navigator.mediaDevices;
  var orig = md && md.getUserMedia ? md.getUserMedia.bind(md) : null;

  async function micList() {
    var all = async function () {
      return (await md.enumerateDevices()).filter(function (d) {
        return d.kind === 'audioinput' && d.deviceId && d.deviceId !== 'default' && d.deviceId !== 'communications';
      });
    };
    var devs = await all();
    if (devs.length && !devs[0].label) {
      // Labels are only exposed after a grant: take and release a throwaway stream.
      var tmp = await orig({ audio: true });
      tmp.getTracks().forEach(function (t) { t.stop(); });
      devs = await all();
    }
    return devs;
  }
  async function pickMic() {
    var devs = await micList();
    if (cfg.mic) {
      var chosen = devs.find(function (d) { return d.label === cfg.mic; });
      if (chosen) return chosen;
    }
    return devs.find(function (d) { return d.label && !BT.test(d.label); });
  }
  function adopt(stream) {
    stream.getAudioTracks().forEach(function (t) { tracks.push(t); t.enabled = !cfg.muted; });
  }

  if (orig) {
    md.getUserMedia = async function (c) {
      if (c && c.audio && (cfg.mic || !c.audio.deviceId)) {
        try {
          var dev = await pickMic();
          var audio = c.audio === true ? {} : Object.assign({}, c.audio);
          if (dev) audio.deviceId = { exact: dev.deviceId };
          if (!cfg.aec) { audio.echoCancellation = false; audio.noiseSuppression = false; audio.autoGainControl = false; }
          c = Object.assign({}, c, { audio: audio });
        } catch (e) { /* fall back to the page's own request */ }
      }
      var stream = await orig(c);
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
  T.setMic = async function (label) {
    cfg.mic = label || '';
    await recapture();
    if (testStream) await T.testMic(true);
  };
  T.setProcessing = async function (on) {
    cfg.aec = !!on;
    await recapture();
    if (testStream) await T.testMic(true);
  };
  T.listMics = async function () {
    try {
      var devs = await micList(), cur = await pickMic();
      post('devices', { list: devs.map(function (d) { return d.label; }), current: cur ? cur.label : '' });
    } catch (e) { post('devices', { list: [], current: '' }); }
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
  var callMeter = null, testMeter = null, testStream = null;

  // The outgoing level of the call itself (muted shows as silence).
  T.setMeter = function (on) {
    if (callMeter) { callMeter.stop(); callMeter = null; }
    if (on) callMeter = startMeter('level', function () {
      return tracks.find(function (t) { return t.readyState === 'live' && t !== (testStream && testStream.getAudioTracks()[0]); });
    });
  };

  // A stand-alone mic test that works before or during a call, using the same mic the call would use.
  T.testMic = async function (on) {
    if (testMeter) { testMeter.stop(); testMeter = null; }
    if (testStream) { testStream.getTracks().forEach(function (t) { t.stop(); }); testStream = null; }
    if (!on || !orig) return;
    try {
      testStream = await md.getUserMedia({ audio: true });
      var tt = testStream.getAudioTracks()[0];
      tracks = tracks.filter(function (x) { return x !== tt; }); // not part of the call, so never muted with it
      tt.enabled = true;
      testMeter = startMeter('testlevel', function () { return tt; });
    } catch (e) { post('testlevel', '-1'); }
  };

  // ---- misc ----------------------------------------------------------------------------------------
  T.configure = function (o) {
    if (!o) return;
    if ('mic' in o) cfg.mic = o.mic || '';
    if ('aec' in o) cfg.aec = !!o.aec;
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
    return { volume: cfg.volume, muted: cfg.muted, mic: cfg.mic, aec: cfg.aec, routed: routed.length, ctx: ctx ? ctx.state : null, tracks: tracks.length };
  };
})();
