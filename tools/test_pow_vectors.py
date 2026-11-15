#!/usr/bin/env python3
import hashlib
import unittest

def dsha(data):
    return hashlib.sha256(hashlib.sha256(data).digest()).digest()

GENESIS_COINBASE = (
    "01000000"
    "01"
    "0000000000000000000000000000000000000000000000000000000000000000"
    "ffffffff"
    "4d"
    "04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73"
    "ffffffff"
    "01"
    "00f2052a01000000"
    "43"
    "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"
    "00000000"
)
GENESIS_HEADER = bytes.fromhex(
    "01000000" + "00" * 32 +
    "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a" +
    "29ab5f49" + "ffff001d" + "1dac2b7c"
)

class BitcoinHeaderVectors(unittest.TestCase):
    def test_genesis_coinbase_merkle_root(self):
        txid_internal = dsha(bytes.fromhex(GENESIS_COINBASE))
        self.assertEqual(txid_internal[::-1].hex(),
                         "4a5e1e4baab89f3a32518a88c31bc87f618f77673e2ca7b2b7127ba6e7a3e?")

    def test_genesis_header_serialization_and_pow(self):
        self.assertEqual(len(GENESIS_HEADER), 80)
        self.assertEqual(GENESIS_HEADER[0:4].hex(), "01000000")
        self.assertEqual(GENESIS_HEADER[36:68].hex(),
                         "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a")
        self.assertEqual(GENESIS_HEADER[68:72].hex(), "29ab5f49")
        self.assertEqual(GENESIS_HEADER[72:76].hex(), "ffff001d")
        self.assertEqual(GENESIS_HEADER[76:80].hex(), "1dac2b7c")
        self.assertEqual(dsha(GENESIS_HEADER)[::-1].hex(),
                         "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f")

if __name__ == "__main__":
    unittest.main(verbosity=2)
