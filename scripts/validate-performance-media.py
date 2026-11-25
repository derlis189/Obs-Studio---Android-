# SPDX-License-Identifier: GPL-2.0-or-later
"""Fully decode an existing local performance run using host FFmpeg/FFprobe."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time


def validate(run):
    ffmpeg, ffprobe = shutil.which('ffmpeg'), shutil.which('ffprobe')
    if not ffmpeg or not ffprobe:
        raise RuntimeError('Host ffmpeg and ffprobe are required')
    report = json.loads((run / 'report.json').read_text(encoding='utf-8'))
    assert report.get('completed') and not report.get('error'), 'Device run did not complete'
    samples = report['samples']
    assert all(s['recording_active'] and s['fps'] >= 27 for s in samples[2:])
    assert all(s['encoders']['active_cpu_video_codecs'] == 0 for s in samples)
    if (run / 'interruption.json').exists():
        assert any(s.get('__perf_b', {}).get('reconnecting') for s in samples)
        assert samples[-1]['__perf_b']['active'] and samples[-1]['__perf_a']['active']
    recording = next(run.glob('recording-*.mp4'))
    files = [recording] + sorted((run / 'received').glob('*.flv'))
    assert len(files) >= 3, 'Missing received media'
    results = []
    for media in files:
        info = json.loads(subprocess.check_output([ffprobe, '-v', 'error', '-show_streams',
            '-show_packets', '-show_entries',
            'stream=index,codec_name,width,height,sample_rate,channels:packet=stream_index,pts_time,duration_time,flags',
            '-of', 'json', str(media)]))
        video = next(s for s in info['streams'] if s['codec_name'] == 'h264')
        audio = next(s for s in info['streams'] if s['codec_name'] == 'aac')
        width, height = (720, 1280) if media.name.startswith(('perf_d-', 'perf_e-')) else (1920, 1080)
        assert (video['width'], video['height']) == (width, height)
        assert int(audio['sample_rate']) == 48000 and audio['channels'] == 2
        packets = {i: [p for p in info['packets'] if p['stream_index'] == i and 'pts_time' in p]
                   for i in (video['index'], audio['index'])}
        def duration(index):
            pts = [float(p['pts_time']) for p in packets[index]]
            return max(pts) - min(pts)
        vd, ad = duration(video['index']), duration(audio['index'])
        keys = [float(p['pts_time']) for p in packets[video['index']] if 'K' in p.get('flags', '')]
        gap = max(b - a for a, b in zip(keys, keys[1:]))
        assert vd >= 1 and ad >= 1 and abs(vd - ad) < .5 and gap < 2.2
        if media == recording:
            assert vd >= len(samples) - 5, 'Recording duration does not cover the run'
        started = time.monotonic()
        decoded = subprocess.run([ffmpeg, '-v', 'error', '-xerror', '-nostats', '-threads', '4',
            '-i', str(media), '-map', '0:v:0', '-map', '0:a:0', '-progress', 'pipe:1',
            '-f', 'null', os.devnull], capture_output=True)
        (run / (media.name + '-decode.log')).write_bytes(decoded.stderr)
        assert decoded.returncode == 0 and not decoded.stderr, 'Full decode failed: ' + media.name
        progress = dict(line.split('=', 1) for line in decoded.stdout.decode().splitlines() if '=' in line)
        frames = int(progress['frame'].strip())
        assert frames >= vd * 29, 'Missing decoded frames'
        result = {'pass': True, 'file': media.name, 'video_frames': frames, 'width': width, 'height': height,
            'video_duration': round(vd, 3), 'audio_duration': round(ad, 3), 'keyframes': len(keys),
            'max_keyframe_gap': round(gap, 3), 'decode_errors': 0, 'decode_seconds': round(time.monotonic() - started, 1)}
        results.append(result)
        (run / 'decoded-host.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
        print('HOST_MEDIA_PASS ' + json.dumps(result), flush=True)
    summary = {'pass': True, 'decoder': 'host FFmpeg, complete video and audio', 'seconds': len(samples),
        'fps_min': min(s['fps'] for s in samples[2:]), 'decoded_files': len(results),
        'thermal_max': max(s['health']['thermal'] for s in samples),
        'battery_temp_start': samples[0]['health']['temp_c'], 'battery_temp_end': samples[-1]['health']['temp_c'],
        'network_interrupted': (run / 'interruption.json').exists()}
    (run / 'summary-host.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print('HOST_PERFORMANCE_PASS ' + json.dumps(summary), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    validate(parser.parse_args().run)
