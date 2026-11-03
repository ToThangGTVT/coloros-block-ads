"""Deterministic emulator fixtures; contains no production traffic or user data."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import socket
import threading
import struct
import os
BIND = os.getenv("BLOCKADS_FIXTURE_BIND", "127.0.0.1")
from collections import Counter
DNS_COUNTS = Counter()
DNS_LOCK = threading.Lock()
TLS_COUNT = 0


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/small":
            data = b"blockads-phase1"
        elif self.path.startswith("/dns-count/"):
            with DNS_LOCK: data = str(DNS_COUNTS[self.path.split("/dns-count/", 1)[1]]).encode()
        elif self.path == "/tls-count":
            with DNS_LOCK: data = str(TLS_COUNT).encode()
        elif self.path == "/bytes":
            data = bytes(range(256)) * 4096
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp_socket.bind((BIND, 18081))
    dns_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    dns_socket.bind((BIND, 18082))
    def answer_dns():
        while True:
            request, peer = dns_socket.recvfrom(65535)
            try:
                offset = 12; labels = []
                while request[offset]:
                    length = request[offset]; offset += 1
                    labels.append(request[offset:offset+length].decode("ascii")); offset += length
                name = ".".join(labels).lower(); end = offset + 5
                kind = struct.unpack("!H", request[offset+1:offset+3])[0]
                with DNS_LOCK: DNS_COUNTS[name] += 1
                record = b""
                if kind in (1, 28):
                    address = bytes([203,0,113,7]) if kind == 1 else socket.inet_pton(socket.AF_INET6, "2001:db8::7")
                    record = struct.pack("!HHHIH", 0xc00c, kind, 1, 2, len(address)) + address
                header = request[:2] + struct.pack("!HHHHH", 0x8180, 1, int(bool(record)), 0, 0)
                dns_socket.sendto(header + request[12:end] + record, peer)
            except (IndexError, ValueError, UnicodeError):
                continue
    threading.Thread(target=answer_dns, daemon=True).start()
    tls_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    tls_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    tls_socket.bind((BIND, 18083)); tls_socket.listen(16)
    def count_tls():
        global TLS_COUNT
        while True:
            client, _ = tls_socket.accept()
            with DNS_LOCK: TLS_COUNT += 1
            client.close()
    threading.Thread(target=count_tls, daemon=True).start()
    http_server = ThreadingHTTPServer((BIND, 18080), Handler)

    def echo_datagrams():
        while True:
            data, address = udp_socket.recvfrom(65535)
            udp_socket.sendto(data, address)

    threading.Thread(target=echo_datagrams, daemon=True).start()
    http_server.serve_forever()
