# Original raid sounds for KD Pager — synthesised from scratch (numpy), no samples, no borrowed melodies.
import numpy as np, wave

SR = 44100

def t(sec): return np.arange(int(sec * SR)) / SR

def env(n, a=0.005, r=0.1, total=None):
    e = np.ones(n)
    na, nr = int(a * SR), int(r * SR)
    if na: e[:na] = np.linspace(0, 1, na)
    if nr: e[-nr:] *= np.linspace(1, 0, nr)
    return e

def saw(f, tt):
    ph = np.cumsum(np.broadcast_to(f, tt.shape) / SR) if np.ndim(f) else f * tt
    return 2 * (ph % 1) - 1

def sqr(f, tt, duty=0.5): return np.where((f * tt) % 1 < duty, 1.0, -1.0)

def lowpass(x, cutoff):
    # one-pole, cutoff may be an array (sweeps)
    c = np.broadcast_to(cutoff, x.shape)
    a = 1 - np.exp(-2 * np.pi * c / SR)
    y = np.zeros_like(x); s = 0.0
    for i in range(len(x)):
        s += a[i] * (x[i] - s); y[i] = s
    return y

def lp4(x, c):
    for _ in range(3): x = lowpass(x, c)
    return x

def kick(dur=0.35):
    tt = t(dur); f = 45 + 110 * np.exp(-tt * 30)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt * 9)

def snare(dur=0.25):
    tt = t(dur); n = np.random.default_rng(1).uniform(-1, 1, len(tt))
    return (0.6 * n * np.exp(-tt * 18) + 0.4 * np.sin(2 * np.pi * 190 * tt) * np.exp(-tt * 25))

def hat(dur=0.05):
    tt = t(dur); n = np.random.default_rng(2).uniform(-1, 1, len(tt))
    n = n - lowpass(n, 6000)
    return n * np.exp(-tt * 80)

def place(buf, x, at, gain=1.0, pan=0.0):
    i = int(at * SR); j = min(len(buf), i + len(x))
    l, r = np.sqrt((1 - pan) / 2), np.sqrt((1 + pan) / 2)
    buf[i:j, 0] += x[:j - i] * gain * l * 1.414
    buf[i:j, 1] += x[:j - i] * gain * r * 1.414

def midi(n): return 440 * 2 ** ((n - 69) / 12)

def finish(buf, name, fade=0.4):
    n = int(fade * SR); buf[-n:] *= np.linspace(1, 0, n)[:, None]
    buf /= np.max(np.abs(buf)) / 0.89
    pcm = (buf * 32767).astype(np.int16)
    with wave.open(name, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())

# ---------- Night Rider: driving synth bass + scanner sweep + brass stabs (Juxtapo's slot) ----------
def night_rider():
    bpm = 128; s16 = 60 / bpm / 4; bars = 5
    total = bars * 16 * s16 + 1.2
    buf = np.zeros((int(total * SR), 2))
    # our own bass figure in D minor: root pumps, octave jumps, a turnaround on the last beat
    fig = [38, 38, 50, 38, 38, 50, 38, 41, 38, 38, 50, 38, 43, 41, 38, 36]
    for b in range(bars):
        for k, n in enumerate(fig):
            at = (b * 16 + k) * s16
            tt = t(s16 * 0.95)
            x = 0.5 * saw(midi(n), tt) + 0.5 * saw(midi(n) * 1.006, tt)
            x = lp4(x, 300 + 1800 * np.exp(-tt * 22)) * env(len(tt), 0.002, 0.03)
            place(buf, x, at, 0.55)
        for k in range(16):
            at = (b * 16 + k) * s16
            if k % 4 == 0: place(buf, kick(), at, 0.8)
            if k % 8 == 4: place(buf, snare(), at, 0.45)
            place(buf, hat(), at, 0.12 if k % 2 else 0.2, pan=0.3)
    # scanner: a narrow resonant whoosh that sweeps left<->right, two passes per bar
    tt = t(total); lfo = np.sin(2 * np.pi * tt / (16 * s16) * 2)
    noise = np.random.default_rng(3).uniform(-1, 1, len(tt))
    band = lp4(noise, 900 + 700 * (lfo + 1)) - lp4(noise, 500 + 400 * (lfo + 1))
    sweep = band * 3.5 * np.clip(tt / 1.0, 0, 1)
    buf[:, 0] += sweep * np.sqrt((1 - lfo) / 2) * 0.5
    buf[:, 1] += sweep * np.sqrt((1 + lfo) / 2) * 0.5
    # brass stabs from bar 2: Dm -> Bb -> C (our own progression)
    chords = [[62, 65, 69], [58, 62, 65], [60, 64, 67], [62, 65, 69]]
    for b in range(1, bars):
        ch = chords[(b - 1) % 4]
        for at in (b * 16 * s16, (b * 16 + 6) * s16, (b * 16 + 10) * s16):
            tt = t(s16 * 3)
            x = sum(saw(midi(n), tt) + saw(midi(n) * 1.004, tt) for n in ch) / 6
            x = lp4(x, 900 + 3500 * np.exp(-tt * 9)) * env(len(tt), 0.01, 0.12)
            place(buf, x, at, 0.45)
    finish(buf, "raid_night_rider.wav", 0.9)

# ---------- Moonlight Raid: moonlight arpeggio rises, then the war drums drop (Celeste's slot) ----------
def moonlight_raid():
    total = 6.4
    buf = np.zeros((int(total * SR), 2))
    # Am(add9) bell arpeggio climbing over ~2.6 s, panning across
    arp = [57, 60, 64, 71, 69, 72, 76, 83, 81, 84, 88]
    for i, n in enumerate(arp):
        at = i * 0.24; tt = t(1.6)
        bell = np.sin(2 * np.pi * midi(n) * tt + 1.8 * np.sin(2 * np.pi * midi(n) * 3.5 * tt) * np.exp(-tt * 4))
        place(buf, bell * np.exp(-tt * 3.2), at, 0.28, pan=-0.7 + 1.4 * i / len(arp))
    # riser into the drop
    tt = t(2.6); noise = np.random.default_rng(4).uniform(-1, 1, len(tt))
    rise = lp4(noise, 200 + 5000 * (tt / 2.6) ** 2) * (tt / 2.6) ** 2
    place(buf, rise, 0.2, 0.5)
    drop = 2.85
    # the drop: sub boom + power chord + war drums
    tt = t(2.2); sub = np.sin(2 * np.pi * np.cumsum(55 * (1 + 1.5 * np.exp(-tt * 6))) / SR) * np.exp(-tt * 1.6)
    place(buf, sub, drop, 0.9)
    for n, g in ((45, 1), (52, 0.8), (57, 0.7)):
        tt = t(2.8)
        x = lp4(saw(midi(n), tt) + saw(midi(n) * 1.007, tt), 400 + 3000 * np.exp(-tt * 2.5)) * np.exp(-tt * 0.9)
        place(buf, x, drop, 0.22 * g)
    beat = 60 / 140
    pattern = [0, 1, 1.5, 2, 3, 3.5, 4, 5, 5.5, 6, 6.25, 6.5, 6.75]
    for p in pattern:
        place(buf, kick(), drop + p * beat * 0.5 + 0.0, 0.7)
    for p in (1, 3, 5, 6.5, 6.75):
        place(buf, snare(), drop + p * beat * 0.5, 0.5)
    # last hit: moon chord rings out
    end = drop + 7.25 * beat * 0.5
    for n in (69, 72, 76, 81):
        tt = t(total - end)
        bell = np.sin(2 * np.pi * midi(n) * tt + 1.2 * np.sin(2 * np.pi * midi(n) * 2 * tt) * np.exp(-tt * 2))
        place(buf, bell * np.exp(-tt * 1.1), end, 0.32)
    place(buf, kick(0.6), end, 0.9)
    finish(buf, "raid_moonlight.wav", 0.6)

night_rider(); moonlight_raid()
