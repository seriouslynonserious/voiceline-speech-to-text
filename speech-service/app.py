import asyncio
import contextlib
import hmac
import logging
import os
from contextlib import asynccontextmanager
from concurrent.futures import ThreadPoolExecutor
import numpy as np
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from faster_whisper import WhisperModel
from streaming import StreamBuffer, Segment, RATE

log = logging.getLogger("voiceline")
MODEL = os.getenv("WHISPER_MODEL", "small")
TOKEN = os.environ["SPEECH_TOKEN"]
MAX_STREAMS = int(os.getenv("MAX_STREAMS", "2"))
model = None
active = 0
# One inference at a time avoids CPU oversubscription and unsafe shared state.
executor = ThreadPoolExecutor(max_workers=1)

@asynccontextmanager
async def lifespan(app):
    global model
    model = await asyncio.to_thread(
        WhisperModel, MODEL,
        device=os.getenv("WHISPER_DEVICE", "cpu"),
        compute_type=os.getenv("WHISPER_COMPUTE_TYPE", "int8"),
        download_root=os.getenv("MODEL_CACHE", "/models"),
        cpu_threads=int(os.getenv("WHISPER_THREADS", "4")),
    )
    yield
    executor.shutdown(wait=False, cancel_futures=True)

app = FastAPI(lifespan=lifespan)

@app.get("/health")
def health():
    return JSONResponse({"ready": model is not None, "model": MODEL}, status_code=200 if model else 503)

def decode(audio):
    if not len(audio) or np.sqrt(np.mean(audio * audio)) < 0.002:
        return []
    segments, _ = model.transcribe(
        audio, task="transcribe", language=None, multilingual=True,
        beam_size=3, temperature=0, condition_on_previous_text=False,
        vad_filter=True, vad_parameters={"min_silence_duration_ms": 500},
        word_timestamps=True,
    )
    result = []
    for segment in segments:
        if segment.no_speech_prob > 0.8 or segment.avg_logprob < -1.2:
            continue
        # Word timestamps allow long speech to be trimmed at a word boundary.
        if segment.words:
            result.extend(Segment(w.start, w.end, w.word) for w in segment.words)
        else:
            result.append(Segment(segment.start, segment.end, segment.text))
    return result

@app.websocket("/stream")
async def stream(ws: WebSocket):
    global active
    provided = ws.headers.get("authorization", "")
    if not hmac.compare_digest(provided, "Bearer " + TOKEN):
        await ws.close(code=1008)
        return
    if model is None or active >= MAX_STREAMS:
        await ws.close(code=1013)
        return
    active += 1
    await ws.accept()
    queue = asyncio.Queue(maxsize=80)  # About 20 seconds at 4096 samples/frame.
    state = StreamBuffer()

    async def receive():
        while True:
            event = await asyncio.wait_for(ws.receive(), timeout=30)
            if event["type"] == "websocket.disconnect":
                raise WebSocketDisconnect()
            if event.get("bytes") is not None:
                queue.put_nowait(event["bytes"])
            elif event.get("text") == '{"type":"stop"}':
                queue.put_nowait(None)
                return
            else:
                raise ValueError("Unsupported audio command")

    reader = asyncio.create_task(receive())
    try:
        await ws.send_json({"type": "ready", "model": MODEL, "sampleRate": RATE})
        while True:
            if reader.done() and reader.exception():
                raise reader.exception()
            try:
                chunk = await asyncio.wait_for(queue.get(), timeout=1)
            except asyncio.TimeoutError:
                continue
            final = chunk is None
            if not final:
                state.append(chunk)
            if final or state.due():
                samples = state.audio.copy()
                segments = await asyncio.get_running_loop().run_in_executor(executor, decode, samples)
                event = state.finish_decode(segments, final)
                event["model"] = MODEL
                await ws.send_json(event)
            if final:
                break
    except WebSocketDisconnect:
        pass
    except Exception as exc:
        log.warning("Stream ended: %s", type(exc).__name__)
        message = str(exc) if isinstance(exc, ValueError) else "Speech processing interrupted or audio queue full. Please try again."
        with contextlib.suppress(Exception):
            await ws.send_json({"type": "error", "message": message})
    finally:
        reader.cancel()
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await reader
        active -= 1
        with contextlib.suppress(Exception):
            await ws.close()
