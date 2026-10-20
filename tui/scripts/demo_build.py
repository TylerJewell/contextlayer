"""Turns the recorded frames and beats into a narrated video.

    uv run python scripts/demo_build.py --out demo [--voice "Microsoft Zira Desktop"] [--rate 0]

Narration is spoken with the voices installed on Windows (System.Speech), one clip per beat; each
beat lasts as long as its narration, with its frames sharing the time. Produces
`demo/contextlayer-demo.mp4`, `demo/contextlayer-demo.srt` and the narration in `demo/audio/`.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

LEAD_IN = 0.35
TAIL = 0.55
MIN_FRAME = 1.6


def run(*cmd: str) -> str:
    done = subprocess.run(cmd, capture_output=True, text=True)
    if done.returncode != 0:
        raise SystemExit(f"{cmd[0]} failed:\n{done.stderr[-1500:]}")
    return done.stdout


def speak(items: list[dict], voice: str, rate: int) -> None:
    """Says each text to its file. The texts go through a JSON file so no quoting can go wrong."""
    with tempfile.TemporaryDirectory() as tmp:
        jobs = Path(tmp) / "jobs.json"
        jobs.write_text(json.dumps(items), encoding="utf-8")
        script = Path(tmp) / "speak.ps1"
        script.write_text(
            "Add-Type -AssemblyName System.Speech\n"
            "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer\n"
            f"$s.SelectVoice('{voice}')\n$s.Rate = {rate}\n"
            f"$jobs = Get-Content -Raw -Encoding UTF8 '{jobs}' | ConvertFrom-Json\n"
            "foreach ($j in $jobs) { $s.SetOutputToWaveFile($j.path); $s.Speak($j.text); $s.SetOutputToNull() }\n",
            encoding="utf-8")
        run("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script))


def seconds(path: Path) -> float:
    return float(run("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)).strip())


def stamp(t: float) -> str:
    h, rem = divmod(t, 3600)
    m, s = divmod(rem, 60)
    return f"{int(h):02d}:{int(m):02d}:{s:06.3f}".replace(".", ",")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="demo")
    parser.add_argument("--voice", default="Microsoft Zira Desktop")
    parser.add_argument("--rate", type=int, default=0)
    parser.add_argument("--name", default="contextlayer-demo")
    parser.add_argument("--crf", default="18")
    args = parser.parse_args()
    out = Path(args.out).resolve()
    beats = json.loads((out / "beats.json").read_text(encoding="utf-8"))
    audio, work = out / "audio", out / "work"
    audio.mkdir(exist_ok=True)
    work.mkdir(exist_ok=True)

    speak([{"text": b["narration"], "path": str(audio / f"{i:02d}-{b['name']}.wav")} for i, b in enumerate(beats)],
          args.voice, args.rate)

    clips, srt, clock = [], [], 0.0
    for i, beat in enumerate(beats):
        wav = audio / f"{i:02d}-{beat['name']}.wav"
        length = max(LEAD_IN + seconds(wav) + TAIL, MIN_FRAME * len(beat["frames"]))
        each = length / len(beat["frames"])
        segments = []
        for j, frame in enumerate(beat["frames"]):
            seg = work / f"{i:02d}-{j:02d}.mp4"
            run("ffmpeg", "-y", "-loop", "1", "-framerate", "30", "-t", f"{each:.3f}", "-i", str(out / frame["path"]),
                "-vf", "scale=1920:1080:flags=lanczos,format=yuv420p", "-c:v", "libx264", "-preset", "veryfast",
                "-crf", args.crf, "-r", "30", str(seg))
            segments.append(seg)
        listing = work / f"{i:02d}.txt"
        listing.write_text("".join(f"file '{s.as_posix()}'\n" for s in segments), encoding="utf-8")
        silent = work / f"{i:02d}-video.mp4"
        run("ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(silent))
        clip = work / f"{i:02d}.mp4"
        run("ffmpeg", "-y", "-i", str(silent), "-i", str(wav), "-filter_complex",
            f"[1:a]adelay={int(LEAD_IN * 1000)}|{int(LEAD_IN * 1000)},apad[a]", "-map", "0:v", "-map", "[a]",
            "-c:v", "copy", "-c:a", "aac", "-ar", "44100", "-ac", "2", "-shortest", str(clip))
        clips.append(clip)
        srt.append(f"{i + 1}\n{stamp(clock + LEAD_IN)} --> {stamp(clock + length - TAIL / 2)}\n{beat['narration']}\n")
        clock += length

    final_list = work / "all.txt"
    final_list.write_text("".join(f"file '{c.as_posix()}'\n" for c in clips), encoding="utf-8")
    video = out / f"{args.name}.mp4"
    # faststart puts the index first, so a phone can start playing before the whole file has arrived
    run("ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(final_list), "-c", "copy", "-movflags", "+faststart", str(video))
    (out / f"{args.name}.srt").write_text("\n".join(srt), encoding="utf-8")
    print(f"{video}  {seconds(video):.1f} seconds, {len(beats)} beats")


if __name__ == "__main__":
    main()
