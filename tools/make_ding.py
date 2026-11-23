"""Generates Harbor's original two-tone "ding" (res/raw/ding.wav): a short bright blip, C6 then G6.
Synthesised from scratch here, so there is no third-party audio in the app."""
import math, struct, sys, wave

RATE = 44100

def tone(freq, dur, start_amp=1.0):
    n = int(RATE * dur)
    out = []
    for i in range(n):
        t = i / RATE
        env = math.exp(-t * 9.0) * min(1.0, i / (RATE * 0.002))  # 2 ms attack, quick decay
        # bright, slightly hollow timbre: fundamental + odd harmonics (soft square-ish)
        s = (math.sin(2 * math.pi * freq * t)
             + 0.30 * math.sin(2 * math.pi * freq * 3 * t)
             + 0.12 * math.sin(2 * math.pi * freq * 5 * t))
        out.append(s * env * start_amp)
    return out

samples = tone(1046.5, 0.07, 0.8) + tone(1568.0, 0.32, 1.0)   # C6 (70 ms) then G6
peak = max(abs(s) for s in samples)
data = b"".join(struct.pack("<h", int(s / peak * 0.6 * 32767)) for s in samples)
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(data)
print("wrote", sys.argv[1], len(samples) / RATE, "s")
