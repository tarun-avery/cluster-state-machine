#!/usr/bin/env python3
"""Start the complete local learning app; Ctrl+C stops apps and preserves data."""
import argparse
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Use an already verified local build")
    args = parser.parse_args()
    def stop_requested(_signal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop_requested)
    os.chdir(ROOT)
    env_file = ROOT / ".env"
    if not env_file.exists():
        with os.fdopen(os.open(env_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as file:
            file.write(f"POSTGRES_PASSWORD={secrets.token_hex(24)}\nPOSTGRES_PORT=5432\nAPP_DATA_ROOT=./data\n")
        env_file.chmod(0o600)
        print("Created private local database settings in .env", flush=True)
    local = dict(line.split("=", 1) for line in env_file.read_text().splitlines() if line and not line.startswith("#"))
    environment = {**local, **os.environ}
    environment["PATH"] = str(Path.home() / ".local/bin") + os.pathsep + environment.get("PATH", "")
    for port in (8081, 8082, 5173):
        with socket.socket() as probe:
            probe.settimeout(0.5)
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                raise RuntimeError(f"Port {port} is in use. Stop the previous app or IntelliJ run first.")
    for port in (9001, 9002, 9003, 9004, 9005, 9021):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                raise RuntimeError(f"Cluster/Archive port {port} is in use. Stop the previous run first.")
    if subprocess.run(["docker", "info"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        raise RuntimeError("Start Docker Desktop, wait until it is running, then launch again.")
    subprocess.run(["docker", "compose", "up", "-d", "--wait"], check=True, env=environment)
    if not args.skip_build:
        subprocess.run(["./gradlew", ":cluster:bootJar", ":trading-gateway:bootJar", ":archive-bridge:installDist", ":history-service:installDist"], check=True, env=environment)
    subprocess.run(["pnpm", "--dir", "frontend", "install", "--frozen-lockfile"], check=True, env=environment)
    logs = Path(environment.get("APP_DATA_ROOT", "./data")) / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    children = []

    def launch(name, command, marker):
        log = logs / f"{name}.log"
        file = log.open("w")
        process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=file, stderr=subprocess.STDOUT, start_new_session=True)
        file.close()
        children.append((name, process))
        deadline = time.monotonic() + 60
        while marker not in log.read_text(errors="replace"):
            if process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError(f"{name} did not become ready; see {log}")
            time.sleep(0.25)
        print(f"{name} ready — {log}", flush=True)

    try:
        launch("cluster", ["java", "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-jar", "backend/cluster/build/libs/cluster-0.1.0-SNAPSHOT.jar"], "Started ClusterApplicationKt")
        launch("archive-bridge", ["backend/archive-bridge/build/install/archive-bridge/bin/archive-bridge"], "Public Archive bridge ready")
        launch("history", ["backend/history-service/build/install/history-service/bin/history-service"], "History HTTP ready")
        launch("gateway", ["java", "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-jar", "backend/trading-gateway/build/libs/trading-gateway-0.1.0-SNAPSHOT.jar"], "WebSocket listening")
        launch("frontend", ["pnpm", "--dir", "frontend", "dev", "--host", "127.0.0.1", "--port", "5173", "--strictPort"], "http://127.0.0.1:5173/")
        print("Open http://127.0.0.1:5173 — Ctrl+C stops the apps; PostgreSQL stays running.", flush=True)
        reported = set()
        while True:
            for name, process in children:
                if process.poll() is not None:
                    if name in ("history", "archive-bridge"):
                        if name not in reported:
                            print(f"{name} stopped. The counter remains available; inspect its log and restart the launcher to resume history.", flush=True)
                            reported.add(name)
                    else:
                        raise RuntimeError(f"{name} stopped; inspect its log. Restart this launcher to resume saved data.")
            time.sleep(1)
    except KeyboardInterrupt:
        print("Stopping apps; preserving recordings and database…", flush=True)
    finally:
        for name, process in reversed(children):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=40)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                    print(f"{name} needed a forced stop; inspect logs before restarting.", file=sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, FileNotFoundError) as failure:
        print(f"Launch failed: {failure}", file=sys.stderr)
        sys.exit(1)
