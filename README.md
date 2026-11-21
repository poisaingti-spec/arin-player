# Arin player

Android video player that plays (almost) any format and writes **English subtitles for whatever language is being spoken**, synced to the audio. It handles videos that switch between several languages.

## How it works

| Step | What happens |
|---|---|
| Play | **libVLC** handles MKV, MP4, AVI, WMV, FLV, MOV, TS, WebM and codecs like HEVC, AC3, DTS and DivX |
| Audio | Audio is decoded into **30 s chunks** at 16 kHz mono with Android MediaCodec. If MediaCodec can't decode the format, libVLC transcodes it instead |
| Detect + translate | **Whisper** detects the spoken language in each chunk and translates it straight to English with timestamps |
| Multi-language | Each chunk is detected on its own, so Japanese → Hindi → Korean in one video works. Lines are tagged `[JA]`, `[HI]`… |
| Sync | Segment timestamps are offset by the chunk start and drawn over the video at the matching playback time |
| Priority | The chunk you're watching and the next 3 minutes are done first. Seeking re-prioritises |
| Cache | Results are saved per video, so a second viewing is instant. Export to `.srt` from the ⋮ menu |

### Engines (Settings)
- **Auto**: uses the cloud when online and the offline model when not. If the network drops mid-chunk, it falls back to offline.
- **Cloud**: Groq `whisper-large-v3` (fast and cheap) or OpenAI `whisper-1`. Needs an API key.
- **Offline**: whisper.cpp v1.9.5 runs on the phone. Download a Tiny, Base or Small model in Settings.

## Build
GitHub Actions (`.github/workflows/build.yml`) builds a release APK signed with the debug key on every push to `main`. The APK is in the run's **ArinPlayer-apk** artifact. Only arm64-v8a is built.
