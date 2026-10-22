class Capture extends AudioWorkletProcessor {
  constructor() { super(); this.buffer = new Float32Array(2048); this.offset = 0;
    this.port.onmessage = (event) => {
      if (event.data === 'flush') {
        if(this.offset) this.port.postMessage(this.buffer.slice(0, this.offset));
        this.offset = 0; this.port.postMessage('flushed');
      }
    };
  }
  process(inputs) {
    const audio = inputs[0]?.[0];
    if(audio) for(const sample of audio) {
      this.buffer[this.offset++] = sample;
      if(this.offset === this.buffer.length) { this.port.postMessage(this.buffer.slice()); this.offset = 0; }
    }
    return true;
  }
}
registerProcessor('pcm-capture', Capture);
