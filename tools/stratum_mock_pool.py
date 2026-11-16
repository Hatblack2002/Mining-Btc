#!/usr/bin/env python3
"""Local Stratum V1 test pool for the Mining BTC Android client.

Run in Termux on the same phone:
  pkg install python
  python tools/stratum_mock_pool.py
Then set the app pool host to 127.0.0.1, port 3333, TLS off.
The mock uses difficulty 0.01 and acknowledges submitted shares.
"""
import json
import socketserver
import time

class Handler(socketserver.StreamRequestHandler):
    def send(self, obj):
        self.wfile.write((json.dumps(obj, separators=(",", ":")) + "\n").encode())
        self.wfile.flush()

    def handle(self):
        print("client connected:", self.client_address, flush=True)
        while True:
            line = self.rfile.readline()
            if not line:
                break
            try:
                msg = json.loads(line)
            except Exception:
                continue
            method = msg.get("method")
            request_id = msg.get("id")
            if method == "mining.subscribe":
                self.send({"id": request_id, "result": [[], "00000000", 4], "error": None})
            elif method == "mining.authorize":
                self.send({"id": request_id, "result": True, "error": None})
                self.send({"id": None, "method": "mining.set_difficulty", "params": [0.01]})
                self.send({"id": None, "method": "mining.notify", "params": [
                    "mock-job-1",
                    "00" * 32,
                    "00",
                    "00",
                    [],
                    "20000000",
                    "1d00ffff",
                    format(int(time.time()), "08x"),
                    True
                ]})
                self.send({"id": None, "method": "mining.set_extranonce", "params": ["a1b2c3d4", 4]})
                print("authorized; sent mock job and extranonce update at difficulty 0.01", flush=True)
            elif method == "mining.suggest_difficulty":
                self.send({"id": request_id, "result": True, "error": None})
                print("accepted suggested difficulty:", msg.get("params"), flush=True)
            elif method == "mining.submit":
                self.send({"id": request_id, "result": True, "error": None})
                print("SHARE ACCEPTED:", msg.get("params"), flush=True)
            elif method == "mining.ping":
                self.send({"id": request_id, "result": True, "error": None})
        print("client disconnected", flush=True)

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

if __name__ == "__main__":
    with Server(("0.0.0.0", 3333), Handler) as server:
        print("Mock Stratum V1 listening on 0.0.0.0:3333", flush=True)
        server.serve_forever()
