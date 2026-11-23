"""Generates Harbor's dock-navigation sounds into res/raw/ (all synthesised from scratch here,
no third-party audio). Short and understated: a tick, a pop, a tink, a thud, plus the coin ding."""
import math, random, struct, sys, wave

RATE = 44100
out = sys.argv[1]

def save(name, samples, gain=0.6):
    peak = max(abs(s) for s in samples) or 1
    data = b"".join(struct.pack("<h", int(s / peak * gain * 32767)) for s in samples)
    with wave.open(f"{out}/{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(data)
    print(name, round(len(samples) / RATE * 1000), "ms")

def attack(i, ms=1.5):
    return min(1.0, i / (RATE * ms / 1000))

def tick():
    rnd = random.Random(1)
    n = int(RATE * 0.035); s = []; lp = 0
    for i in range(n):
        t = i / RATE
        lp += 0.35 * (rnd.uniform(-1, 1) - lp)           # softened noise burst
        click = lp * math.exp(-t * 400)
        body = math.sin(2 * math.pi * 2400 * t) * math.exp(-t * 160) * 0.6
        s.append((click + body) * attack(i, 0.5))
    return s

def pop():
    n = int(RATE * 0.07); s = []; ph = 0
    for i in range(n):
        t = i / RATE
        f = 380 + 620 * math.exp(-t * 70)                # quick downward bubble sweep
        ph += 2 * math.pi * f / RATE
        s.append(math.sin(ph) * math.exp(-t * 55) * attack(i, 2))
    return s

def tink():
    n = int(RATE * 0.12); s = []
    for i in range(n):
        t = i / RATE
        v = (math.sin(2 * math.pi * 3150 * t) + 0.35 * math.sin(2 * math.pi * 6380 * t) * math.exp(-t * 60)
             + 0.15 * math.sin(2 * math.pi * 9420 * t) * math.exp(-t * 90))
        s.append(v * math.exp(-t * 38) * attack(i, 1))
    return s

def thud():
    n = int(RATE * 0.085); s = []; ph = 0
    for i in range(n):
        t = i / RATE
        f = 95 + 90 * math.exp(-t * 45)
        ph += 2 * math.pi * f / RATE
        v = math.sin(ph) * math.exp(-t * 42) + 0.25 * math.sin(2 * math.pi * 1200 * t) * math.exp(-t * 500)
        s.append(v * attack(i, 2))
    return s

def coin():
    def tone(freq, dur, amp):
        r = []
        for i in range(int(RATE * dur)):
            t = i / RATE
            v = (math.sin(2 * math.pi * freq * t) + 0.30 * math.sin(6 * math.pi * freq * t)
                 + 0.12 * math.sin(10 * math.pi * freq * t))
            r.append(v * math.exp(-t * 9.0) * attack(i, 2) * amp)
        return r
    return tone(1046.5, 0.07, 0.8) + tone(1568.0, 0.32, 1.0)

save("snd_tick", tick(), 0.5)
save("snd_pop", pop(), 0.55)
save("snd_tink", tink(), 0.45)
save("snd_thud", thud(), 0.7)
save("snd_coin", coin(), 0.6)
