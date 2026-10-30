"""Loopback-only local static server. No inference/upload API; no internet required."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
import argparse
import functools

if __name__ == '__main__':
    p=argparse.ArgumentParser(); p.add_argument('--port',type=int,default=8765)
    p.add_argument('--directory',default=str(Path(__file__).resolve().parent/'web'))
    a=p.parse_args()
    folder=Path(a.directory).resolve()
    if not (folder/'index.html').is_file():
        p.error('index.html not found; source checkout: --directory web/dist')
    SimpleHTTPRequestHandler.extensions_map.update({'.mjs':'text/javascript','.wasm':'application/wasm','.webmanifest':'application/manifest+json'})
    handler=functools.partial(SimpleHTTPRequestHandler,directory=str(folder))
    print(f'Offline app: http://127.0.0.1:{a.port}/',flush=True)
    ThreadingHTTPServer(('127.0.0.1',a.port),handler).serve_forever()
