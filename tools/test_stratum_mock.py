#!/usr/bin/env python3
import json
import socket
import subprocess
import sys
import time
import unittest

SERVER = "tools/stratum_mock_pool.py"

class StratumMockSession(unittest.TestCase):
    def test_subscribe_notify_difficulty_suggest_and_submit(self):
        proc = subprocess.Popen([sys.executable, SERVER], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.time() + 8
            sock = None
            while time.time() < deadline:
                try:
                    sock = socket.create_connection(("127.0.0.1", 3333), timeout=1)
                    break
                except OSError:
                    time.sleep(0.1)
            self.assertIsNotNone(sock, "mock Stratum server did not start")
            with sock:
                stream = sock.makefile("rwb", buffering=0)
                def send(obj):
                    stream.write((json.dumps(obj) + "\n").encode())
                def read():
                    line = stream.readline()
                    self.assertTrue(line, "server closed the Stratum stream")
                    return json.loads(line)
                send({"id": 1, "method": "mining.subscribe", "params": ["test-client"]})
                sub = read()
                self.assertEqual(sub["id"], 1)
                self.assertEqual(sub["result"][1:], ["00000000", 4])
                send({"id": 2, "method": "mining.authorize", "params": ["test.worker", "x"]})
                auth = read()
                self.assertTrue(auth["result"])
                difficulty = read()
                notify = read()
                self.assertEqual(difficulty["method"], "mining.set_difficulty")
                self.assertEqual(difficulty["params"], [0.01])
                self.assertEqual(notify["method"], "mining.notify")
                self.assertTrue(notify["params"][8])
                send({"id": 3, "method": "mining.suggest_difficulty", "params": [0.01]})
                suggestion = read()
                self.assertEqual(suggestion["id"], 3)
                self.assertTrue(suggestion["result"])
                send({"id": 4, "method": "mining.submit",
                      "params": ["test.worker", "mock-job-1", "00000000",
                                 notify["params"][7], "00000001"]})
                submitted = read()
                self.assertEqual(submitted["id"], 4)
                self.assertTrue(submitted["result"])
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=3)

if __name__ == "__main__":
    unittest.main(verbosity=2)
