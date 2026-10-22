# Validation record

Date: 22 October 2026.

## Automated checks

- Angular production build: passed locally and in GitHub Actions.
- Frontend tests: 4 passing tests for 44.1/48 kHz resampling, PCM clipping/endianness, and exact packet/tail preservation.
- Spring Boot: 7 passing API integration tests covering CRUD, Hindi text, pagination, CSRF/origin checks, visitor isolation, stale edits, active-recording protection, and interrupted-recording preservation.
- MySQL 8.4: production Flyway migrations V1 and V2 applied successfully in GitHub Actions. The MySQL-specific test passed Unicode storage (including an emoji), search, deletion, and session-table availability.
- Speech worker: 10 passing buffer/protocol tests, including auth rejection, capacity rejection, invalid audio, final flush, pause commits, long utterance trimming, silence, and repeated words.
- Backend restart: the same visitor cookie could still retrieve a previously created record after a local backend restart. The probe removed its own record afterward.

CI: https://github.com/seriouslynonserious/voiceline-speech-to-text/actions

## Actual inference

These checks use downloaded model weights; no speech results are mocked in the application or the end-to-end smoke client. Protocol unit tests deliberately stub inference so they can run without model downloads.

### English fixture

The [whisper.cpp JFK sample](https://github.com/ggerganov/whisper.cpp/blob/master/samples/jfk.wav) was streamed through Spring Boot to the Python worker at real-time audio speed.

Model: multilingual `small`, CPU int8, 4 inference threads. Local macOS ARM machine. One test stream.

| Measurement | Observed |
|---|---|
| Audio duration including trailing silence | 12.0 seconds |
| Time to first nonempty text | 4.8 seconds |
| Total stream + finalization + CRUD check | 16.59 seconds |
| Update/final events | 7 |
| Save / read / edit / search / delete | Passed |

The recognized sentence matched the spoken English fixture in this single check. These are observations, not statistical accuracy or service-level guarantees. Microphone hardware and permissions were not exercised by this fixture client.

### Hindi–English stress check

A short excerpt (13–23 seconds) of `A_001_001.mp3` from the public [liva-ai Hindi–English dataset](https://huggingface.co/datasets/liva-ai/hindi-english-asr) was used as a difficult sample. It contains multiple speakers, laughter, and overlapping speech.

The WebSocket/persistence path passed, but the small model misrecognized/omitted the short Hindi passage. A comparison with `large-v3-turbo`, using both automatic detection and a Hindi hint on this excerpt and a second excerpt, remained inaccurate and was substantially slower on this CPU (roughly 10–32 seconds for 8–10 seconds of audio).

**Mixed-language quality is not signed off.** Before submission as a reliable code-switching service, test clear recordings from the intended speakers and evaluate a suitable larger or Hindi-tuned model on adequate hardware. The implementation supports multilingual inference, but model capability must not be confused with demonstrated accuracy. No test recordings are redistributed in this repository.

## Browser checks

- Studio and history navigation verified in the in-app browser.
- Readiness changes enable recording only when the speech worker is available.
- Desktop and 390 px mobile layouts inspected.
- Actual microphone recording and browser permission dialogs need a user-operated acceptance test.

## Local environment and deployment

- Local preview uses a file-backed H2 test database because Docker Desktop is incomplete and native MySQL binaries crash on this host. This does **not** change the production configuration.
- Production and container configuration use MySQL 8.4. H2 is test-scoped only and is not included in the production artifact.
- Hosting has intentionally not been selected yet, per the user's instruction.
- **Full Docker smoke test: passed.** All services built and started; the real small model downloaded successfully as a non-root user. The English fixture streamed through Nginx/Spring Boot/Whisper and all CRUD operations passed against MySQL. [Successful run](https://github.com/seriouslynonserious/voiceline-speech-to-text/actions/runs/39728371013). On the GitHub runner, first text took 6.59 seconds; the 12-second fixture plus finalization/CRUD took 31.18 seconds. This reinforces the need for hardware/model tuning before claiming low-latency performance.
