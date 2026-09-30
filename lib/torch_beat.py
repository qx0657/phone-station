#!/usr/bin/env python3
# 把系统声音的音量换成闪光灯亮度。由 torch.sh beat 调用，不要直接运行。
import argparse
import math
import os
import select
import signal
import subprocess
import sys
import threading
import time

FAST_RELEASE_SEC = 0.09
SLOW_ATTACK_SEC = 0.22
SLOW_RELEASE_SEC = 0.50
PEAK_RELEASE_SEC = 1.2
ABS_GATE = 1e-4


class Mapper:
    # 快包络跟瞬态，慢包络跟持续的响度。亮度主要看快的比慢的高出多少，
    # 所以一声鼓会闪一下，一直响着的声音不会把灯顶在最亮。
    def __init__(self):
        self.peak = ABS_GATE
        self.fast = 0.0
        self.slow = 0.0
        self.current = 0

    def level(self, rms, dt, max_level, gain):
        dt = max(0.001, min(dt, 0.2))
        sample = 0.0 if rms < ABS_GATE else rms
        if sample > self.peak:
            self.peak = sample
        else:
            self.peak *= math.exp(-dt / PEAK_RELEASE_SEC)
            if self.peak < ABS_GATE:
                self.peak = ABS_GATE
        if sample > self.fast:
            self.fast = sample
        else:
            keep = math.exp(-dt / FAST_RELEASE_SEC)
            self.fast = self.fast * keep + sample * (1.0 - keep)
        if sample > self.slow:
            approach = 1.0 - math.exp(-dt / SLOW_ATTACK_SEC)
            self.slow += (sample - self.slow) * approach
        else:
            self.slow *= math.exp(-dt / SLOW_RELEASE_SEC)
        transient = self.fast - self.slow * 0.85
        if transient < 0:
            transient = 0.0
        if self.peak <= ABS_GATE or self.fast < ABS_GATE:
            target = 0.0
        else:
            punch = transient / self.peak
            body = self.fast / self.peak
            norm = (0.16 * body + 0.84 * punch) * gain
            if norm < 0.12:
                target = 0.0
            else:
                if norm > 1:
                    norm = 1.0
                target = (norm ** 0.7) * max_level
        chosen = int(round(target))
        if chosen != self.current and abs(target - self.current) < 0.45:
            chosen = self.current
        if chosen < 0:
            chosen = 0
        if chosen > max_level:
            chosen = max_level
        self.current = chosen
        return chosen


def read_stderr(stream, bucket):
    try:
        for raw in stream:
            text = raw.decode("utf-8", "replace").rstrip()
            if text:
                bucket.append(text)
                del bucket[:-20]
    except Exception:
        return


def wait_ready(state, stop, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if stop["v"]:
            return None
        if state["ready"] is not None:
            return state["ready"]
        if state["err"]:
            raise SystemExit(state["err"])
        if state["dead"]:
            raise SystemExit("闪光灯进程退出了")
        time.sleep(0.05)
    raise SystemExit("闪光灯没有就绪")


def main():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--audio", required=True)
    parser.add_argument("--gain", type=float, default=2.0)
    args = parser.parse_args()
    if not (0.2 <= args.gain <= 8):
        raise SystemExit("增益用 0.2 到 8")

    stop = {"v": False}

    def request_stop(signum, frame):
        stop["v"] = True

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    dev = subprocess.Popen(
        [
            args.adb, "-s", args.serial, "shell",
            "CLASSPATH=/data/local/tmp/torch.dex",
            "app_process", "/data/local/tmp", "Torch", "listen",
        ],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )
    state = {"ready": None, "err": None, "dead": False}
    dev_err = []

    def pump_out():
        try:
            for raw in dev.stdout:
                line = raw.decode("utf-8", "replace").replace("\r", "").strip()
                if line.startswith("ready "):
                    state["ready"] = line
                elif line.startswith("err "):
                    state["err"] = line[4:]
                    state["dead"] = True
        except Exception as exc:
            state["err"] = str(exc)
        state["dead"] = True

    threading.Thread(target=pump_out, daemon=True).start()
    threading.Thread(target=read_stderr, args=(dev.stderr, dev_err), daemon=True).start()

    audio = None
    sent = None
    try:
        ready = wait_ready(state, stop, 8)
        if ready is None:
            return
        parts = ready.split()
        if len(parts) != 3:
            raise SystemExit("闪光灯返回了无法识别的信息")
        max_level = int(parts[2])
        if max_level < 1:
            max_level = 1

        audio_err = []
        audio = subprocess.Popen(
            [args.audio],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )
        threading.Thread(target=read_stderr, args=(audio.stderr, audio_err), daemon=True).start()

        mapper = Mapper()
        last = time.monotonic()
        warned = False
        started = last
        debug = os.environ.get("TORCH_DEBUG") == "1"

        def send(level):
            nonlocal sent
            if level == sent:
                return
            dev.stdin.write(("{}\n".format(level)).encode())
            dev.stdin.flush()
            sent = level
            if debug:
                print("level", level, file=sys.stderr)

        while not stop["v"]:
            if state["dead"]:
                if state["err"]:
                    raise SystemExit(state["err"])
                raise SystemExit("闪光灯进程退出了")
            if audio.poll() is not None:
                detail = "\n".join(audio_err).strip()
                if detail:
                    raise SystemExit(detail)
                raise SystemExit("系统声音采集退出了")
            readable, _, _ = select.select([audio.stdout], [], [], 0.2)
            now = time.monotonic()
            dt = now - last
            last = now
            if not readable:
                send(mapper.level(0.0, dt, max_level, args.gain))
                continue
            raw = audio.stdout.readline()
            if not raw:
                detail = "\n".join(audio_err).strip()
                if detail:
                    raise SystemExit(detail)
                raise SystemExit("系统声音采集退出了")
            text = raw.decode("utf-8", "replace").strip()
            try:
                rms = float(text)
            except ValueError:
                continue
            send(mapper.level(rms, dt, max_level, args.gain))
            if not warned and now - started > 3 and mapper.peak <= ABS_GATE * 2:
                warned = True
                print(
                    "还没有收到系统声音。若弹出权限窗口，请允许。也可以到「系统设置 → 隐私与安全性 → 系统录音」打开当前应用，允许后重新打开，并确认电脑正在出声。",
                    file=sys.stderr,
                )
    finally:
        try:
            if dev.poll() is None and dev.stdin is not None:
                dev.stdin.write(b"0\nq\n")
                dev.stdin.flush()
        except Exception:
            pass
        try:
            dev.wait(timeout=2)
        except Exception:
            dev.kill()
        if audio is not None and audio.poll() is None:
            audio.terminate()
            try:
                audio.wait(timeout=1)
            except Exception:
                audio.kill()
        if dev_err and os.environ.get("TORCH_DEBUG") == "1":
            print("\n".join(dev_err), file=sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
    except BrokenPipeError:
        print("手机上的闪光灯进程断了。可以再运行一次。", file=sys.stderr)
        sys.exit(1)
