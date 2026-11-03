"""Deterministic emulator fixtures; contains no production traffic or user data."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import socket
import threading


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/small":
            data = b"blockads-phase1"
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
    udp_socket.bind(("127.0.0.1", 18081))
    http_server = ThreadingHTTPServer(("127.0.0.1", 18080), Handler)

    def echo_datagrams():
        while True:
            data, address = udp_socket.recvfrom(65535)
            udp_socket.sendto(data, address)

    threading.Thread(target=echo_datagrams, daemon=True).start()
    http_server.serve_forever()
