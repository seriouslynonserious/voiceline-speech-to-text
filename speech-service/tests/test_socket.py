import os
os.environ.setdefault('SPEECH_TOKEN', 'unit-test-token')
import numpy as np
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
import app as worker
from streaming import Segment

@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(worker, 'model', object())
    monkeypatch.setattr(worker, 'active', 0)
    monkeypatch.setattr(worker, 'decode', lambda audio: [Segment(0, 1, 'आज meeting है')] if len(audio) else [])
    # Do not enter lifespan: these protocol tests deliberately stub inference.
    return TestClient(worker.app)

def test_rejects_unauthenticated_stream(client):
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect('/stream'):
            pass

def test_stream_flushes_final_text_and_releases_slot(client):
    with client.websocket_connect('/stream', headers={'Authorization':'Bearer '+worker.TOKEN}) as ws:
        assert ws.receive_json()['type'] == 'ready'
        ws.send_bytes(np.full(4096, 1000, dtype='<i2').tobytes())
        ws.send_text('{"type":"stop"}')
        result=ws.receive_json()
        assert result['type']=='final' and result['confirmed']=='आज meeting है'
        assert result['durationMs']==256
    assert worker.active==0

def test_invalid_audio_produces_explicit_error(client):
    with client.websocket_connect('/stream', headers={'Authorization':'Bearer '+worker.TOKEN}) as ws:
        ws.receive_json();ws.send_bytes(b'x')
        result=ws.receive_json()
        assert result['type']=='error' and 'PCM' in result['message']
    assert worker.active==0

def test_capacity_rejection(client, monkeypatch):
    monkeypatch.setattr(worker,'active',worker.MAX_STREAMS)
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect('/stream', headers={'Authorization':'Bearer '+worker.TOKEN}):
            pass
