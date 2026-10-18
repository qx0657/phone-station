"""Built phone-relay speaks link v6 on loopback. It does not start the public relay."""
import hashlib
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request

from websocket_deps import ROOT


class ControlTLS(unittest.TestCase):
    def test_built_command_serves_link_v6(self):
        with tempfile.TemporaryDirectory(prefix='station-control-') as directory:
            work = Path(directory)
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(work / 'key.pem'),
                '-out', str(work / 'cert.pem'), '-days', '1', '-subj', '/CN=127.0.0.1',
                '-addext', 'subjectAltName=IP:127.0.0.1'], check=True, capture_output=True)
            relay = work / 'relay'
            subprocess.run(['go', '-C', str(ROOT / 'server/relay'), 'build', '-o', str(relay), './cmd/phone-relay'],
                check=True, capture_output=True)
            version = subprocess.run([str(relay), '--version'], check=True, capture_output=True, text=True)
            self.assertEqual(version.stdout, 'phone-station-relay 6\n')
            self.assertNotIn('5', version.stdout)
            state = work / 'state'
            state.mkdir()
            env = {**os.environ, 'PHONE_RELAY_STATE_DIR': str(state)}
            env.pop('PHONE_RELAY_PHONE_TOKEN', None)
            env.pop('PHONE_RELAY_DESKTOP_TOKEN', None)
            enrolled = subprocess.run([str(relay), 'enroll'], env=env, capture_output=True, text=True)
            self.assertEqual(enrolled.returncode, 0, enrolled.stderr)
            code = enrolled.stdout.strip()
            self.assertEqual(len(code), 32)
            self.assertEqual(code, code.lower())
            int(code, 16)
            registry = (state / 'registry.json').read_text()
            if code in registry or code in enrolled.stderr:
                self.fail('enroll code stored')
            digest = hashlib.sha256(bytes.fromhex(code)).hexdigest()
            self.assertIn(digest, registry)
            port = free_port()
            env.update({
                'PHONE_RELAY_LISTEN': f'127.0.0.1:{port}',
                'PHONE_RELAY_CERT': str(work / 'cert.pem'),
                'PHONE_RELAY_KEY': str(work / 'key.pem'),
            })
            context = ssl.create_default_context(cafile=str(work / 'cert.pem'))
            with subprocess.Popen([str(relay)], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True) as server:
                    try:
                        self.assertNotEqual(self.status(context, port, '/v2/link'), 404)
                        self.assertEqual(self.status(context, port, '/v1/desktop/status'), 404)
                        self.assertEqual(self.status(context, port, '/mcp'), 404)
                    finally:
                        server.terminate()
                        stderr = server.stderr.read() if server.stderr else ''
                        server.wait(timeout=5)
                        if code in stderr:
                            self.fail('enroll code logged')

    def status(self, context, port, route):
        deadline = time.monotonic() + 5
        while True:
            try:
                with urllib.request.urlopen('https://127.0.0.1:%d%s' % (port, route), context=context, timeout=2) as response:
                    return response.status
            except urllib.error.HTTPError as error:
                error.close()
                return error.code
            except (OSError, urllib.error.URLError):
                if time.monotonic() > deadline:
                    raise
                time.sleep(0.025)


def free_port():
    import socket
    with socket.socket() as bound:
        bound.bind(('127.0.0.1', 0))
        return bound.getsockname()[1]


if __name__ == '__main__':
    unittest.main()
