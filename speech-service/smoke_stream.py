"""Stream a local audio fixture through Spring Boot at microphone speed.
Usage: python smoke_stream.py /path/to/audio.wav --base http://localhost:4200
This creates a test entry, validates read/edit/search, then deletes its own entry.
"""
import argparse
import asyncio
import json
import time
import httpx
import numpy as np
from faster_whisper.audio import decode_audio
from websockets.asyncio.client import connect

async def run(path, base):
    pcm = (np.clip(decode_audio(path, sampling_rate=16000), -1, 1) * 32767).astype('<i2').tobytes()
    # Finish the utterance naturally before the stop command.
    pcm += bytes(16000 * 2)
    async with httpx.AsyncClient(base_url=base) as client:
        r = await client.get('/api/session');r.raise_for_status()
        headers = {'X-CSRF-Token':r.json()['csrfToken'], 'Origin':base}
        r = await client.post('/api/transcriptions',headers=headers,json={'title':'Automated speech smoke test'});r.raise_for_status()
        record_id = r.json()['id']
        cookies = '; '.join(f'{k}={v}' for k,v in client.cookies.items())
        url = base.replace('https://','wss://').replace('http://','ws://') + f'/api/transcriptions/{record_id}/stream'
        started = time.monotonic()
        first_draft = None
        updates = 0
        async with connect(url,origin=base,additional_headers={'Cookie':cookies},max_size=1000000) as ws:
            ready = json.loads(await asyncio.wait_for(ws.recv(),20))
            assert ready['type']=='ready', ready
            async def send():
                for i in range(0,len(pcm),8192):
                    frame=pcm[i:i+8192]
                    await ws.send(frame)
                    await asyncio.sleep(len(frame)/32000)
                await ws.send('{"type":"stop"}')
            sender=asyncio.create_task(send())
            while True:
                event=json.loads(await asyncio.wait_for(ws.recv(),90))
                assert event['type']!='error',event
                updates+=1
                if first_draft is None and (event.get('partial') or event.get('confirmed')):
                    first_draft=round(time.monotonic()-started,2)
                if event['type']=='final':
                    text=event['confirmed'];break
            await sender
        assert text.strip(), 'Expected speech in the fixture'
        r=await client.get(f'/api/transcriptions/{record_id}');r.raise_for_status();record=r.json()
        assert record['status']=='COMPLETED' and record['originalText']==text
        r=await client.patch(f'/api/transcriptions/{record_id}',headers=headers,json={'title':'Verified smoke test','text':text+' [edited]','version':record['version']});r.raise_for_status()
        assert r.json()['originalText']==text
        r=await client.get('/api/transcriptions',params={'q':'Verified smoke test'});r.raise_for_status();assert r.json()['total']==1
        r=await client.delete(f'/api/transcriptions/{record_id}',headers=headers);r.raise_for_status()
        assert (await client.get(f'/api/transcriptions/{record_id}')).status_code==404
        print(json.dumps({'transcript':text,'firstTextSeconds':first_draft,'audioSeconds':round(len(pcm)/32000,2),'totalSeconds':round(time.monotonic()-started,2),'updates':updates,'crud':'passed'},ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('audio');parser.add_argument('--base',default='http://localhost:4200');args=parser.parse_args()
    asyncio.run(run(args.audio,args.base))
