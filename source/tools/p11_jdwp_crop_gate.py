"""Pause only the real APK crop worker at its file-commit boundary for a UI race test.

Uses JDWP event-thread breakpoints; never edits app code or substitutes a fake processor.
Protocol: https://docs.oracle.com/en/java/javase/20/docs/specs/jdwp/jdwp-protocol.html
"""
import socket
import struct
import subprocess


class Reader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def take(self, count):
        value = self.data[self.offset:self.offset + count]
        assert len(value) == count
        self.offset += count
        return value

    def integer(self):
        return int.from_bytes(self.take(4), 'big')

    def string(self):
        return self.take(self.integer()).decode()


class CropCommitGate:
    def __init__(self, package, delete_line):
        self.socket = None
        self.port = None
        self.request = None
        self.thread = None
        self.sequence = 0
        self.events = []
        pid = subprocess.check_output(['adb', 'shell', 'pidof', package], text=True).strip().split()[0]
        self.port = subprocess.check_output(['adb', 'forward', 'tcp:0', 'jdwp:' + pid], text=True).strip()
        try:
            self.socket = socket.create_connection(('127.0.0.1', int(self.port)), timeout=20)
            self.socket.sendall(b'JDWP-Handshake')
            assert self.read(14) == b'JDWP-Handshake'
            sizes = struct.unpack('>5I', self.command(1, 7))
            self.method_size, self.object_size, self.type_size = sizes[1], sizes[2], sizes[3]
            self.command(1, 9)  # Ensure an optional attach/start suspension is resumed.
            signature = b'Lcom/abfilepro/app/core/ScanProcessor;'
            classes = Reader(self.command(1, 2, struct.pack('>I', len(signature)) + signature))
            assert classes.integer() >= 1, 'ScanProcessor class was not loaded'
            tag = classes.take(1)
            type_id = classes.take(self.type_size)
            classes.take(4)
            methods = Reader(self.command(2, 5, type_id))
            selected = None
            for _ in range(methods.integer()):
                method_id = methods.take(self.method_size)
                name, method_signature = methods.string(), methods.string()
                methods.take(4)
                if name != 'commitPerspectiveCrop':
                    continue
                table = Reader(self.command(6, 1, type_id + method_id))
                table.take(16)
                locations = []
                for _ in range(table.integer()):
                    index = table.take(8)
                    line = table.integer()
                    if line == delete_line:
                        locations.append(index)
                if locations:
                    selected = tag + type_id + method_id + min(locations)
                    break
            assert selected is not None, 'Crop source-delete line missing from APK debug line table'
            # Breakpoint event (2), suspend event thread only (1), one LocationOnly modifier (7).
            request = bytes([2, 1]) + struct.pack('>I', 1) + bytes([7]) + selected
            self.request = self.command(15, 1, request)
            assert len(self.request) == 4
        except Exception:
            self.close()
            raise

    def read(self, length):
        result = b''
        while len(result) < length:
            chunk = self.socket.recv(length - len(result))
            if not chunk:
                raise EOFError('APK debugger connection closed')
            result += chunk
        return result

    def packet(self):
        header = self.read(11)
        length, sequence, flags = struct.unpack('>IIB', header[:9])
        payload = self.read(length - 11)
        return sequence, flags, header[9:11], payload

    def command(self, command_set, command, payload=b''):
        self.sequence += 1
        current = self.sequence
        self.socket.sendall(struct.pack('>IIBBB', 11 + len(payload), current, 0, command_set, command) + payload)
        while True:
            sequence, flags, extra, response = self.packet()
            if flags & 128:
                assert sequence == current, 'Unexpected JDWP reply sequence'
                error = int.from_bytes(extra, 'big')
                assert error == 0, 'JDWP command %s/%s failed: %s' % (command_set, command, error)
                return response
            if extra == bytes([64, 100]):
                self.events.append(response)

    def wait_hit(self):
        while True:
            if self.events:
                event = Reader(self.events.pop(0))
            else:
                _, flags, extra, payload = self.packet()
                if flags & 128 or extra != bytes([64, 100]):
                    continue
                event = Reader(payload)
            policy = event.take(1)[0]
            count = event.integer()
            for _ in range(count):
                kind = event.take(1)[0]
                request = event.take(4)
                if kind == 90:  # Optional VMStart emitted on debugger attachment.
                    event.take(self.object_size)
                elif kind == 2:
                    thread = event.take(self.object_size)
                    event.take(1 + self.type_size + self.method_size + 8)
                    if request == self.request:
                        assert policy == 1, 'UI thread must remain running during the race test'
                        self.thread = thread
                        return
                else:
                    raise RuntimeError('Unexpected debugger event: %s' % kind)

    def resume(self):
        if self.request is not None:
            self.command(15, 2, bytes([2]) + self.request)
            self.request = None
        if self.thread is not None:
            self.command(11, 3, self.thread)
            self.thread = None

    def close(self):
        if self.socket is not None:
            try:
                self.resume()
                self.command(1, 6)  # Dispose resumes any remaining debugger suspension.
            except Exception:
                pass
            self.socket.close()
            self.socket = None
        if self.port is not None:
            subprocess.run(['adb', 'forward', '--remove', 'tcp:' + self.port], capture_output=True)
            self.port = None
