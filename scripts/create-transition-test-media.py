#!/usr/bin/env python3
"""Create small local Stinger fixtures. Host FFmpeg checks do not validate Android playback."""
import json
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parent.parent
output = root / "out" / "move-stinger-checks" / "media"
output.mkdir(parents=True, exist_ok=True)
ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
if not ffmpeg or not ffprobe:
    raise SystemExit("FFmpeg and ffprobe must be available on PATH")

# The left half is transparent, the right half opaque red.
pattern = "nullsrc=s=320x180:r=30:d=1,format=rgba,geq=r=255:g=0:b=0:a='if(gte(X,160),255,0)'"
fixtures = [
    ("alpha-animation.mov", ["-c:v", "qtrle", "-pix_fmt", "argb"], True),
    ("alpha-prores4444.mov", ["-c:v", "prores_ks", "-profile:v", "4", "-pix_fmt", "yuva444p10le"], True),
    ("opaque-h264.mp4", ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart"], False),
    ("opaque-vp9.webm", ["-c:v", "libvpx-vp9", "-pix_fmt", "yuv420p"], False),
    ("alpha-ffv1.mkv", ["-c:v", "ffv1", "-pix_fmt", "bgra"], True),
    ("alpha-png.avi", ["-c:v", "png", "-pix_fmt", "rgba"], True),
]
results = []
for name, encoding, alpha in fixtures:
    path = output / name
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
                    "-i", pattern, "-an", *encoding, str(path)], check=True)
    probe = json.loads(subprocess.check_output([ffprobe, "-v", "error", "-select_streams", "v:0",
                                               "-show_entries", "stream=codec_name,pix_fmt,width,height",
                                               "-of", "json", str(path)]))
    pixels = subprocess.check_output([ffmpeg, "-v", "error", "-i", str(path), "-frames:v", "1",
                                      "-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1"])
    left, right = pixels[(90 * 320 + 80) * 4 + 3], pixels[(90 * 320 + 240) * 4 + 3]
    if alpha and (left > 5 or right < 250):
        raise RuntimeError(f"{name}: alpha was lost ({left}, {right})")
    result = {"file": name, **probe["streams"][0], "alpha_left": left, "alpha_right": right,
              "host_fixture_verified": True, "android_playback_verified": False}
    results.append(result)
    print(json.dumps(result))
(output / "fixtures.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
