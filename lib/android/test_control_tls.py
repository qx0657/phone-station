"""Real Java WSS client / Go durable relay interoperability; no phone or private configuration."""
import hashlib
import json
import os
from pathlib import Path
import selectors
import socket
import ssl
import subprocess
import tempfile
import time
import unittest
import urllib.request

from websocket_deps import ROOT, jars

class ControlTLS(unittest.TestCase):
    def test_java_control_and_pin_against_real_relay(self):
        with tempfile.TemporaryDirectory(prefix='station-control-') as directory:
            work = Path(directory)
            subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(work/'key.pem'),
                '-out',str(work/'cert.pem'),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1'],check=True,capture_output=True)
            pub = subprocess.check_output(['openssl','x509','-in',str(work/'cert.pem'),'-pubkey','-noout'])
            der = subprocess.check_output(['openssl','pkey','-pubin','-outform','DER'],input=pub)
            pin = hashlib.sha256(der).hexdigest()
            cp = os.pathsep.join(str(p) for p in jars())
            classes = work/'classes'
            subprocess.run(['javac','--release','17','-classpath',cp,'-sourcepath',str(ROOT/'lib/android/src'),'-d',str(classes),
                str(ROOT/'lib/android/testdata/ControlTlsFixture.java')],check=True,capture_output=True)
            cp = str(classes)+os.pathsep+cp
            relay = work/'relay'
            subprocess.run(['go','-C',str(ROOT/'server/relay'),'build','-o',str(relay),'./cmd/phone-relay'],check=True,capture_output=True)
            with socket.socket() as port_socket:
                port_socket.bind(('127.0.0.1',0)); port = port_socket.getsockname()[1]
            phone_token, desktop_token = 'a'*64, 'b'*64
            env = {**os.environ, 'PHONE_RELAY_LISTEN':f'127.0.0.1:{port}', 'PHONE_RELAY_PHONE_TOKEN':phone_token,
                'PHONE_RELAY_DESKTOP_TOKEN':desktop_token, 'PHONE_RELAY_CERT':str(work/'cert.pem'),
                'PHONE_RELAY_KEY':str(work/'key.pem'), 'PHONE_RELAY_STATE_DIR':str(work/'state')}
            endpoint = f'https://localhost:{port}'
            context = ssl.create_default_context(cafile=str(work/'cert.pem'))
            def call(route, body=None):
                request = urllib.request.Request(endpoint+route,data=None if body is None else json.dumps(body).encode(),
                    headers={'Authorization':'Bearer '+desktop_token,'Content-Type':'application/json'})
                with urllib.request.urlopen(request,context=context,timeout=8) as response: return json.load(response)
            with subprocess.Popen([str(relay)],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL) as server:
                phone = None
                try:
                    deadline = time.monotonic()+5
                    while True:
                        try: status=call('/v1/desktop/status'); break
                        except OSError:
                            if time.monotonic()>deadline: raise
                            time.sleep(.025)
                    command = ['java','-cp',cp,'dev.phonestation.adbkeep.ControlTlsFixture',endpoint,phone_token]
                    bad = subprocess.run(command+['0'*64,'reject'],capture_output=True,text=True,timeout=15)
                    self.assertEqual(bad.returncode,0,bad.stderr); self.assertIn('REJECTED',bad.stdout)
                    phone = subprocess.Popen(command+[pin,'run'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                    with selectors.DefaultSelector() as selector:
                        selector.register(phone.stdout,selectors.EVENT_READ)
                        self.assertTrue(selector.select(12),'Java client failed to connect')
                    self.assertEqual(phone.stdout.readline().strip(),'READY')
                    for count in (1,2):
                        operation = status['operationEpoch']+'.'+str(count)*32
                        payload = {'jsonrpc':'2.0','id':count,'method':'ping'}
                        result = call('/v1/desktop/call',{'operationId':operation,'payload':payload})
                        self.assertEqual(result['body']['result']['executed'],count)
                        saved = call('/v1/desktop/call',{'operationId':operation,'payload':payload})
                        self.assertEqual(saved,result)
                    stdout,stderr=phone.communicate(timeout=10)
                    self.assertEqual(phone.returncode,0,stderr); self.assertIn('DONE',stdout)
                finally:
                    if phone is not None and phone.poll() is None: phone.kill(); phone.communicate()
                    server.terminate(); server.wait(timeout=5)

if __name__ == '__main__': unittest.main()
