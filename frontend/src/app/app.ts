import {
  Component,
  OnInit,
  OnDestroy,
  HostListener,
  signal,
} from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { MatProgressSpinnerModule } from "@angular/material/progress-spinner";
import { A11yModule } from "@angular/cdk/a11y";
import { PcmResampler } from "./audio-resampler.mjs";
interface Transcript {
  id: string;
  title: string;
  text: string;
  originalText: string;
  status: string;
  durationMs: number;
  model: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}
@Component({
  selector: "app-root",
  standalone: true,
  imports: [CommonModule, FormsModule, MatProgressSpinnerModule, A11yModule],
  templateUrl: "./app.html",
})
export class AppComponent implements OnInit, OnDestroy {
  view = signal<"studio" | "history">("studio");
  state = signal<"idle" | "connecting" | "recording" | "stopping">("idle");
  ready = signal(false);
  backend = signal(false);
  loading = signal(true);
  error = signal("");
  notice = signal("");
  confirmed = signal("");
  partial = signal("");
  seconds = signal(0);
  level = signal(0);
  model = signal("");
  items = signal<Transcript[]>([]);
  total = signal(0);
  page = signal(0);
  pages = signal(0);
  selected = signal<Transcript | null>(null);
  deleting = signal<Transcript | null>(null);
  saving = signal(false);
  title = "Untitled recording";
  editTitle = "";
  editText = "";
  query = "";
  bars = Array.from(
    { length: 52 },
    (_, i) => 12 + Math.sin(i * 1.7) ** 2 * 36 + Math.cos(i * 0.6) ** 2 * 20,
  );
  private csrf = "";
  private ws?: WebSocket;
  private media?: MediaStream;
  private ctx?: AudioContext;
  private source?: MediaStreamAudioSourceNode;
  private worklet?: AudioWorkletNode;
  private mute?: GainNode;
  private timer?: ReturnType<typeof setInterval>;
  private healthTimer?: ReturnType<typeof setInterval>;
  private searchTimer?: ReturnType<typeof setTimeout>;
  private timeout?: ReturnType<typeof setTimeout>;
  private flushDone?: () => void;
  private recordId = "";
  private listRequest = 0;
  @HostListener("document:keydown.escape") escape() {
    if (!this.saving()) {
      this.deleting.set(null);
      this.selected.set(null);
    }
  }
  @HostListener("window:beforeunload", ["$event"]) beforeUnload(
    event: BeforeUnloadEvent,
  ) {
    if (this.active) {
      event.preventDefault();
      event.returnValue = "";
    }
  }
  ngOnInit() {
    void this.initialize();
    this.healthTimer = setInterval(() => void this.health(), 15000);
  }
  ngOnDestroy() {
    this.cleanup();
    this.ws?.close();
    clearInterval(this.healthTimer);
    clearTimeout(this.searchTimer);
  }
  get active() {
    return this.state() !== "idle";
  }
  get words() {
    return (this.confirmed() + " " + this.partial())
      .trim()
      .split(/\s+/)
      .filter(Boolean).length;
  }
  time(seconds: number) {
    return `${Math.floor(seconds / 60)
      .toString()
      .padStart(2, "0")}:${Math.floor(seconds % 60)
      .toString()
      .padStart(2, "0")}`;
  }
  async api(path: string, method = "GET", body?: unknown) {
    const r = await fetch("/api" + path, {
      method,
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-Token": this.csrf,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok) {
      const e = await r.json().catch(() => ({}));
      throw new Error(
        e.message || `Request failed (${r.status}). Please try again.`,
      );
    }
    return r.status === 204 ? null : r.json();
  }
  async initialize() {
    try {
      this.csrf = (await this.api("/session")).csrfToken;
      this.backend.set(true);
      await Promise.all([this.refresh(), this.health()]);
    } catch (e) {
      this.showError(e);
    } finally {
      this.loading.set(false);
    }
  }
  async health() {
    try {
      const r = await fetch("/api/readiness");
      this.ready.set(r.ok);
      this.backend.set(true);
    } catch {
      this.ready.set(false);
      this.backend.set(false);
    }
  }
  async refresh() {
    const request = ++this.listRequest;
    this.loading.set(true);
    try {
      const p = await this.api(
        `/transcriptions?q=${encodeURIComponent(this.query)}&page=${this.page()}`,
      );
      if (request === this.listRequest) {
        this.items.set(p.items);
        this.total.set(p.total);
        this.pages.set(p.pages);
      }
    } catch (e) {
      this.showError(e);
    } finally {
      if (request === this.listRequest) this.loading.set(false);
    }
  }
  search() {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.page.set(0);
      void this.refresh();
    }, 250);
  }
  changePage(delta: number) {
    this.page.update((p) => p + delta);
    void this.refresh();
  }
  async start() {
    if (this.active) return;
    this.error.set("");
    this.notice.set("");
    this.confirmed.set("");
    this.partial.set("");
    this.seconds.set(0);
    this.state.set("connecting");
    try {
      if (!this.csrf) await this.initialize();
      if (!this.csrf)
        throw new Error("Backend is unavailable. Try again after it starts.");
      if (!navigator.mediaDevices?.getUserMedia)
        throw new Error("Microphone access requires HTTPS or localhost.");
      this.media = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
        },
        video: false,
      });
      this.ctx = new AudioContext();
      await this.ctx.resume();
      await this.ctx.audioWorklet.addModule("/pcm-worklet.js");
      const record: Transcript = await this.api("/transcriptions", "POST", {
        title: this.title.trim() || "Untitled recording",
      });
      this.recordId = record.id;
      const ws = new WebSocket(
        `${location.protocol === "https:" ? "wss:" : "ws:"}//${location.host}/api/transcriptions/${record.id}/stream`,
      );
      this.ws = ws;
      this.timeout = setTimeout(
        () =>
          this.fail(
            "The speech service took too long to respond. Please try again.",
          ),
        20000,
      );
      ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data);
          if (message.type === "ready") {
            clearTimeout(this.timeout);
            this.model.set(message.model);
            this.beginAudio(ws);
            this.state.set("recording");
            this.timer = setInterval(() => {
              this.seconds.update((s) => s + 1);
              if (this.seconds() >= 599) void this.stop();
            }, 1000);
          } else if (message.type === "update" || message.type === "final") {
            this.confirmed.set(message.confirmed);
            this.partial.set(message.partial || "");
            if (message.type === "final") {
              this.seconds.set(message.durationMs / 1000);
              this.cleanup();
              this.state.set("idle");
              this.notice.set("Recording saved to your history.");
              ws.close();
              void this.refresh();
            }
          } else if (message.type === "error") this.fail(message.message);
        } catch {
          this.fail("The speech service returned an invalid response.");
        }
      };
      ws.onerror = () =>
        this.fail("Could not connect to the transcription service.");
      ws.onclose = () => {
        if (this.active)
          this.fail(
            "Connection closed. Confirmed text is available in history.",
          );
      };
    } catch (e) {
      this.fail(
        e instanceof DOMException && e.name === "NotAllowedError"
          ? "Microphone permission was denied. Allow microphone access in your browser and try again."
          : e instanceof Error
            ? e.message
            : "Unable to start recording.",
      );
    }
  }
  private beginAudio(ws: WebSocket) {
    const ctx = this.ctx!,
      resampler = new PcmResampler(ctx.sampleRate);
    this.source = ctx.createMediaStreamSource(this.media!);
    this.worklet = new AudioWorkletNode(ctx, "pcm-capture");
    this.mute = ctx.createGain();
    this.mute.gain.value = 0;
    this.worklet.port.onmessage = ({ data }) => {
      if (data === "flushed") {
        this.flushDone?.();
        return;
      }
      if (ws.readyState !== WebSocket.OPEN) return;
      if (ws.bufferedAmount > 16000 * 2 * 5) {
        this.fail("Connection is too slow to stream audio. Please try again.");
        return;
      }
      const audio = data as Float32Array;
      let energy = 0;
      for (const v of audio) energy += v * v;
      this.level.set(Math.min(1, Math.sqrt(energy / audio.length) * 8));
      const pcm = resampler.process(audio);
      if (pcm.byteLength) ws.send(pcm);
    };
    this.source.connect(this.worklet);
    this.worklet.connect(this.mute);
    this.mute.connect(ctx.destination);
  }
  async stop() {
    if (this.state() !== "recording") return;
    this.state.set("stopping");
    clearInterval(this.timer);
    this.source?.disconnect();
    this.media?.getTracks().forEach((t) => t.stop());
    await new Promise<void>((resolve) => {
      this.flushDone = resolve;
      this.worklet?.port.postMessage("flush");
      setTimeout(resolve, 300);
    });
    this.flushDone = undefined;
    this.cleanup();
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: "stop" }));
      this.timeout = setTimeout(
        () =>
          this.fail(
            "Final transcription timed out. Confirmed text remains in history.",
          ),
        90000,
      );
    } else
      this.fail(
        "Connection lost while saving. Check history for confirmed text.",
      );
  }
  private cleanup() {
    clearInterval(this.timer);
    clearTimeout(this.timeout);
    this.source?.disconnect();
    this.worklet?.disconnect();
    this.mute?.disconnect();
    this.media?.getTracks().forEach((t) => t.stop());
    void this.ctx?.close().catch(() => {});
    this.ctx = undefined;
    this.level.set(0);
  }
  private fail(message: string) {
    this.cleanup();
    this.state.set("idle");
    this.error.set(message);
    this.ws?.close();
    void this.refresh();
  }
  showError(e: unknown) {
    this.error.set(
      e instanceof Error
        ? e.message
        : "Something went wrong. Please try again.",
    );
  }
  async open(t: Transcript) {
    try {
      const fresh = await this.api("/transcriptions/" + t.id);
      this.editTitle = fresh.title;
      this.editText = fresh.text;
      this.selected.set(fresh);
    } catch (e) {
      this.showError(e);
    }
  }
  async save() {
    const t = this.selected();
    if (!t || this.saving()) return;
    this.saving.set(true);
    try {
      await this.api("/transcriptions/" + t.id, "PATCH", {
        title: this.editTitle.trim(),
        text: this.editText,
        version: t.version,
      });
      this.selected.set(null);
      this.notice.set("Transcript updated.");
      await this.refresh();
    } catch (e) {
      this.showError(e);
    } finally {
      this.saving.set(false);
    }
  }
  async remove() {
    const t = this.deleting();
    if (!t || this.saving()) return;
    this.saving.set(true);
    try {
      await this.api("/transcriptions/" + t.id, "DELETE");
      this.deleting.set(null);
      this.selected.set(null);
      if (this.items().length === 1 && this.page() > 0)
        this.page.update((p) => p - 1);
      await this.refresh();
      this.notice.set("Transcript deleted.");
    } catch (e) {
      this.showError(e);
    } finally {
      this.saving.set(false);
    }
  }
  async copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      this.notice.set("Copied to clipboard.");
    } catch {
      this.error.set(
        "Clipboard access is unavailable. Select and copy the text manually.",
      );
    }
  }
  download(text: string, title: string) {
    const url = URL.createObjectURL(
      new Blob([text], { type: "text/plain;charset=utf-8" }),
    );
    const a = document.createElement("a");
    a.href = url;
    a.download =
      (title.replace(/[^\p{L}\p{N} _-]/gu, "").slice(0, 80) ||
        "transcription") + ".txt";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
