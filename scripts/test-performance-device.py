# SPDX-License-Identifier: GPL-2.0-or-later
"""Debug APK only. Private fixtures, loopback RTMP and app-private MP4; no public stream keys."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--mode', choices=['smoke', 'resize', 'codecs', 'vtuber', 'vertical', 'real'], default='smoke')
    parser.add_argument('--seconds', type=int, default=20)
    parser.add_argument('--sdk-root', default=os.environ.get('ANDROID_SDK_ROOT', str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')))
    parser.add_argument('--interrupt-at', type=int, default=0, help='Close only the local test RTMP connections at this second')
    args = parser.parse_args()
    if not 12 <= args.seconds <= 3600:
        parser.error('--seconds must be between 12 and 3600')
    run = ROOT / 'out/performance-checks' / (args.mode + '-' + time.strftime('%Y%m%d-%H%M%S'))
    run.mkdir(parents=True)
    adb = Path(args.sdk_root) / 'platform-tools/adb.exe'
    base = [str(adb), '-s', args.serial]

    def command(*words, check=True):
        result = subprocess.run(base + list(words), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if check and result.returncode:
            raise RuntimeError('ADB operation failed: ' + result.stderr.decode(errors='replace'))
        return result

    def read_report(name):
        result = command('shell', 'run-as', 'org.obsandroid.port', 'cat', 'files/' + name, check=False)
        try:
            return json.loads(result.stdout) if result.returncode == 0 else None
        except (ValueError, UnicodeError):
            return None

    node = shutil.which('node')
    if not node:
        raise RuntimeError('Node.js is required for the loopback RTMP receiver')
    receiver_log = (run / 'receiver.log').open('wb')
    receiver_error = (run / 'receiver-errors.log').open('wb')
    receiver = subprocess.Popen([node, str(ROOT / 'scripts/rtmp-receiver/performance.cjs'), str(run / 'received')],
        cwd=ROOT, stdout=receiver_log, stderr=receiver_error,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    result_name = 'performance-' + args.mode + '.json'
    progress_name = 'performance-' + args.mode + '-progress.json'
    old = read_report(result_name) or {}
    old_id = old.get('run_id')
    interrupted = False
    started = time.monotonic()
    report = None
    try:
        time.sleep(.5)
        if receiver.poll() is not None:
            raise RuntimeError('Loopback receiver could not start; check ports 1935 and 18081 and receiver-errors.log')
        fixture = ROOT / 'android/test-assets/performance-browser.html'
        command('push', str(fixture), '/data/local/tmp/performance-browser.html')
        command('shell', 'run-as', 'org.obsandroid.port', 'cp', '/data/local/tmp/performance-browser.html', 'files/performance-browser.html')
        command('reverse', 'tcp:1935', 'tcp:1935')
        command('shell', 'am', 'start', '-W', '-n', 'org.obsandroid.port/.QtObsActivity')
        time.sleep(5)
        command('shell', 'am', 'start', '-W', '-n', 'org.obsandroid.port/.PerformanceProbeActivity',
                '--es', 'mode', args.mode, '--ei', 'seconds', str(args.seconds))
        print('LOCAL_PERFORMANCE_STARTED ' + str(run), flush=True)
        deadline = time.monotonic() + args.seconds + 90
        while time.monotonic() < deadline:
            time.sleep(3)
            progress = read_report(progress_name)
            if progress and progress.get('run_id') != old_id:
                (run / 'progress.json').write_text(json.dumps(progress, indent=2), encoding='utf-8')
                second = progress.get('count', 0)
                if args.interrupt_at and second >= args.interrupt_at and not interrupted:
                    request = urllib.request.Request('http://127.0.0.1:18081/drop', data=b'', method='POST')
                    with urllib.request.urlopen(request, timeout=3) as response:
                        response.read()
                    interrupted = True
                    (run / 'interruption.json').write_text(json.dumps({'after_second': second}), encoding='utf-8')
                    print('LOCAL_RTMP_INTERRUPTION ' + str(second), flush=True)
            candidate = read_report(result_name)
            if candidate and candidate.get('run_id') != old_id and ('completed' in candidate or 'error' in candidate):
                report = candidate
                break
            if receiver.poll() is not None:
                raise RuntimeError('Loopback receiver stopped unexpectedly')
        if report is None:
            raise RuntimeError('Probe timed out; unlock the phone and check the app/runtime logs')
        (run / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        if not report.get('completed') or report.get('error'):
            raise RuntimeError('Device probe failed: ' + str(report.get('error')))
        samples = report['samples']
        assert len(samples) == args.seconds
        assert all(s['recording_active'] for s in samples[2:]), 'Recording stopped during the test'
        assert samples[-1]['recording_frames'] > args.seconds * 20, 'Insufficient recorded frames'
        assert all(s['encoders']['active_cpu_video_codecs'] == 0 for s in samples), 'CPU video encoding was used'
        assert all(s['fps'] >= 27 for s in samples[2:]), 'Engine did not maintain 30 FPS'
        if args.mode == 'codecs':
            assert max(s['encoders']['active_surface_codecs'] for s in samples) == 2
            assert samples[-1]['encoders']['active_surface_codecs'] == 1
        elif args.mode == 'vertical':
            assert report['begin']['vertical_shared'] and report['begin']['incompatible_canvas_rejected']
            assert samples[-1]['__perf_d']['active'] and samples[-1]['__perf_e']['active']
            assert samples[-1]['__perf_d']['video_encoder'] == samples[-1]['__perf_e']['video_encoder']
            assert samples[-1]['encoders']['active_surface_codecs'] == 2
        else:
            assert all(s['encoders']['active_surface_codecs'] == 1 for s in samples[2:])
        if args.mode in ('smoke', 'resize', 'codecs'):
            pixels = samples[-1]['pixels']
            rgb = lambda p: ((p >> 16) & 255, (p >> 8) & 255, p & 255)
            red, purple, blue = map(rgb, pixels)
            assert red[0] > 230 and red[1] < 15 and red[2] < 15
            assert 160 < purple[0] < 205 and purple[1] < 15 and 160 < purple[2] < 205
            assert blue[0] < 15 and blue[1] < 15 and blue[2] > 230
            assert max(s['browser_audio_peak_db'] for s in samples) > -30, 'No browser tone reached its mixer channel'
        if args.mode == 'vtuber':
            assert any(s['pixels'][1] != s['pixels'][2] for s in samples[3:]), 'No avatar pixels reached Program'
        if interrupted:
            assert any(s.get('__perf_b', {}).get('reconnecting') for s in samples), 'Reconnect state was not observed'
            assert samples[-1]['__perf_b']['active'] and samples[-1]['__perf_b']['bytes'] > 100000
        recording = run / ('recording-' + args.mode + '.mp4')
        with recording.open('wb') as destination:
            result = subprocess.run(base + ['exec-out', 'run-as', 'org.obsandroid.port', 'cat',
                'files/performance-' + args.mode + '.mp4'], stdout=destination, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError('Could not retrieve the recorded test file')
        # Decode both the recorded file and the actual RTMP packets at the receiver.
        decoder = ROOT / 'out/libobs-arm64/android-native-tests/obs-android-media-probe'
        command('push', str(decoder), '/data/local/tmp/obs-android-media-probe')
        command('shell', 'chmod', '755', '/data/local/tmp/obs-android-media-probe')
        decoded = []
        files = [recording] + list((run / 'received').glob('*.flv'))
        for index, media in enumerate(files):
            if media.stat().st_size < 1000:
                raise RuntimeError('Empty receiver segment: ' + media.name)
            remote = '/data/local/tmp/obs-performance-decode'
            command('push', str(media), remote)
            portrait = media.name.startswith(('perf_d-', 'perf_e-'))
            width, height = (720, 1280) if portrait else (1920, 1080)
            result = command('shell', '/data/local/tmp/obs-android-media-probe', remote, '--generic', str(width), str(height), '1', check=False)
            data = json.loads(result.stdout)
            data['file'] = media.name
            decoded.append(data)
            if result.returncode or not data.get('pass'):
                raise RuntimeError('Media decoding failed: ' + json.dumps(data))
        (run / 'decoded.json').write_text(json.dumps(decoded, indent=2), encoding='utf-8')
        summary = {'pass': True, 'mode': args.mode, 'seconds': len(samples), 'network_interrupted': interrupted,
            'fps_min': min(s['fps'] for s in samples[2:]),
            'render_ms_max': max(s['render_ms'] for s in samples),
            'battery_temp_start': samples[0]['health']['temp_c'], 'battery_temp_end': samples[-1]['health']['temp_c'],
            'thermal_max': max(s['health']['thermal'] for s in samples),
            'native_heap_start_mb': samples[0]['health']['native_heap_mb'], 'native_heap_end_mb': samples[-1]['health']['native_heap_mb'],
            'decoded_files': len(decoded), 'wall_seconds': round(time.monotonic() - started, 1)}
        (run / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
        print('LOCAL_PERFORMANCE_PASS ' + json.dumps(summary), flush=True)
    finally:
        receiver.terminate()
        try:
            receiver.wait(timeout=5)
        except subprocess.TimeoutExpired:
            receiver.kill()
        receiver_log.close()
        receiver_error.close()


if __name__ == '__main__':
    main()
