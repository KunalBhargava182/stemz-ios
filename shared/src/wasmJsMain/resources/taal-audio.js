// Stemz Web: thin Web Audio glue called from Kotlin/Wasm (WebAudioProbe.kt).
// Capture is raw: no echo cancellation, noise suppression or auto gain (Android UNPROCESSED equivalent).
var TaalAudio = {
  ctx: null, stream: null, source: null, proc: null, sink: null, running: false,

  requestPermission: function (cb) {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { cb(false); return; }
    navigator.mediaDevices.getUserMedia({ audio: true })
      .then(function (s) { s.getTracks().forEach(function (t) { t.stop(); }); cb(true); })
      .catch(function () { cb(false); });
  },

  // Returns "label\tdeviceId" lines.
  listInputs: function (cb) {
    navigator.mediaDevices.enumerateDevices().then(function (list) {
      cb(list.filter(function (d) { return d.kind === 'audioinput'; })
             .map(function (d) { return (d.label || 'Unnamed input') + '\t' + d.deviceId; })
             .join('\n'));
    }).catch(function () { cb(''); });
  },

  onDeviceChange: function (cb) {
    navigator.mediaDevices.addEventListener('devicechange', function () { cb(); });
  },

  start: function (deviceId, onData, onError, onEnded) {
    var self = this;
    self.stop();
    // Create the AudioContext synchronously inside the tap (Safari user-gesture rule).
    var AC = window.AudioContext || window.webkitAudioContext;
    var ctx = new AC();
    self.ctx = ctx;
    ctx.resume();
    var constraints = { audio: {
      deviceId: deviceId ? { exact: deviceId } : undefined,
      echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1
    } };
    navigator.mediaDevices.getUserMedia(constraints).then(function (stream) {
      if (self.ctx !== ctx) { stream.getTracks().forEach(function (t) { t.stop(); }); return; }
      self.stream = stream;
      var track = stream.getAudioTracks()[0];
      track.onended = function () { if (self.running) { self.stop(); onEnded(); } };
      self.source = ctx.createMediaStreamSource(stream);
      self.proc = ctx.createScriptProcessor(4096, 1, 1);
      self.sink = ctx.createGain();
      self.sink.gain.value = 0;
      self.proc.onaudioprocess = function (e) {
        var input = e.inputBuffer.getChannelData(0);
        onData(new Float32Array(input), ctx.sampleRate);
      };
      self.source.connect(self.proc);
      self.proc.connect(self.sink);
      self.sink.connect(ctx.destination);
      self.running = true;
    }).catch(function (err) { self.stop(); onError(String(err && err.message ? err.message : err)); });
  },

  // Settings the browser actually applied, as "key=value; ..." text.
  trackInfo: function () {
    if (!this.stream) return '';
    var t = this.stream.getAudioTracks()[0];
    if (!t || !t.getSettings) return '';
    var s = t.getSettings();
    return 'sampleRate=' + s.sampleRate + '; channels=' + s.channelCount +
      '; echoCancel=' + s.echoCancellation + '; noiseSupp=' + s.noiseSuppression + '; autoGain=' + s.autoGainControl;
  },

  stop: function () {
    this.running = false;
    try { if (this.proc) { this.proc.onaudioprocess = null; this.proc.disconnect(); } } catch (e) {}
    try { if (this.source) this.source.disconnect(); } catch (e) {}
    try { if (this.sink) this.sink.disconnect(); } catch (e) {}
    if (this.stream) this.stream.getTracks().forEach(function (t) { t.stop(); });
    if (this.ctx) { try { this.ctx.close(); } catch (e) {} }
    this.ctx = null; this.stream = null; this.source = null; this.proc = null; this.sink = null;
  }
};
