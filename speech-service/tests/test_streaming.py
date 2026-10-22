import numpy as np
import pytest
from streaming import StreamBuffer, Segment, RATE

def feed(state, seconds, value=1000):
    left = int(seconds * RATE)
    while left:
        n = min(4096, left)
        state.append(np.full(n, value, dtype="<i2").tobytes())
        left -= n

def test_draft_replaced_and_finalized_without_duplicates():
    s = StreamBuffer()
    feed(s, 2)
    assert s.due()
    a = s.finish_decode([Segment(0, 1, "आज meeting")])
    assert a["confirmed"] == "" and a["partial"] == "आज meeting"
    feed(s, 2)
    b = s.finish_decode([Segment(0, 3, "आज meeting है")])
    assert b["partial"] == "आज meeting है"
    c = s.finish_decode([Segment(0, 3, "आज meeting है")], final=True)
    assert c["confirmed"] == "आज meeting है"
    assert c["partial"] == "" and c["durationMs"] == 4000

def test_pause_commits_and_clears_audio():
    s = StreamBuffer()
    feed(s, 1)
    feed(s, 1, 0)
    e = s.finish_decode([Segment(0, .9, "Hello")])
    assert e["confirmed"] == "Hello" and len(s.audio) == 0
    feed(s, 1)
    e = s.finish_decode([Segment(0, .8, "world")], final=True)
    assert e["confirmed"] == "Hello world"

def test_long_utterance_retains_unfinished_tail():
    s = StreamBuffer()
    feed(s, 13)
    e = s.finish_decode([Segment(0, 5, "first"), Segment(5, 11, "second"), Segment(11, 12.8, "tail")])
    assert e["confirmed"] == "first second" and e["partial"] == "tail"
    assert len(s.audio) == 2 * RATE
    e = s.finish_decode([Segment(0, 1.8, "tail")], final=True)
    assert e["confirmed"] == "first second tail"

def test_repeated_words_are_not_removed():
    s = StreamBuffer();feed(s,1)
    e = s.finish_decode([Segment(0,.5,"very"),Segment(.5,.9,"very")], final=True)
    assert e["confirmed"] == "very very"

def test_invalid_frames_and_bounded_memory():
    s=StreamBuffer()
    for bad in [b"", b"x", bytes(8194)]:
        with pytest.raises(ValueError): s.append(bad)
    with pytest.raises(ValueError, match="too slow"): feed(s,26)

def test_silence_does_not_invent_text():
    s=StreamBuffer();feed(s,1,0)
    assert s.finish_decode([],final=True)["confirmed"] == ""
