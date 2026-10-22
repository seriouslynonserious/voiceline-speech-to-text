// Stateful linear resampler. Retaining the fractional position avoids drift
// when the microphone runs at 44.1kHz or 48kHz instead of the model's 16kHz.
export class PcmResampler {
  constructor(inputRate, outputRate = 16000) {
    this.ratio = inputRate / outputRate;
    this.position = 0;
    this.carry = new Float32Array(0);
  }
  process(input) {
    const data = new Float32Array(this.carry.length + input.length);
    data.set(this.carry);
    data.set(input, this.carry.length);
    const values = [];
    while (this.position + 1 < data.length) {
      const i = Math.floor(this.position),
        f = this.position - i;
      const sample = Math.max(
        -1,
        Math.min(1, data[i] * (1 - f) + data[i + 1] * f),
      );
      values.push(Math.round(sample < 0 ? sample * 32768 : sample * 32767));
      this.position += this.ratio;
    }
    const consumed = Math.min(Math.floor(this.position), data.length);
    this.carry = data.slice(consumed);
    this.position -= consumed;
    const bytes = new ArrayBuffer(values.length * 2),
      view = new DataView(bytes);
    values.forEach((value, i) => view.setInt16(i * 2, value, true));
    return bytes;
  }
}
