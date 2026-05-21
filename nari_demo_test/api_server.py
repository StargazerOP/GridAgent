from __future__ import annotations

import argparse
import json
import mimetypes
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from config import BASE_DIR
from tools.asset_registry import asset_summary, build_asset_registry, project_asset_overview, search_assets
from tools.instant_planner import plan_instant_workflow, retrieve_planning_context
from tools.registry_manager import addition_guide, register_item, registry_overview


class DemoHandler(SimpleHTTPRequestHandler):
    server_version = "NARIDemoAPI/0.1"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(BASE_DIR), **kwargs)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/health":
            self._send_json({"ok": True, "service": "demo_api"})
            return
        if parsed.path == "/api/assets":
            query = parse_qs(parsed.query)
            self._send_json({
                "summary": asset_summary(),
                "assets": search_assets(query.get("q", [""])[0], top_k=int(query.get("top_k", ["80"])[0])),
            })
            return
        if parsed.path == "/api/assets/rebuild":
            self._send_json(build_asset_registry())
            return
        if parsed.path == "/api/admin/overview":
            self._send_json({
                "assets": project_asset_overview(),
                "registry": registry_overview(),
                "guide": addition_guide(),
            })
            return
        if parsed.path == "/api/plan/context":
            query = parse_qs(parsed.query).get("q", [""])[0]
            self._send_json(retrieve_planning_context(query))
            return
        return super().do_GET()

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        try:
            payload = self._read_json()
            if parsed.path == "/api/plan/instant":
                query = str(payload.get("query", "")).strip()
                if not query:
                    self._send_json({"ok": False, "error": "query 不能为空"}, status=400)
                    return
                use_llm = bool(payload.get("use_llm", True))
                self._send_json({"ok": True, **plan_instant_workflow(query, use_llm=use_llm)})
                return
            if parsed.path == "/api/admin/register":
                item = register_item(payload, copy_file=bool(payload.get("copy_file", False)))
                self._send_json({"ok": True, "item": item, "overview": registry_overview()})
                return
        except Exception as exc:  # noqa: BLE001 - API returns readable error for demo use.
            self._send_json({"ok": False, "error": str(exc)}, status=500)
            return
        self._send_json({"ok": False, "error": f"未知接口：{parsed.path}"}, status=404)

    def end_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        super().end_headers()

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.end_headers()

    def guess_type(self, path: str) -> str:
        if path.endswith(".js"):
            return "application/javascript; charset=utf-8"
        if path.endswith(".css"):
            return "text/css; charset=utf-8"
        if path.endswith(".json"):
            return "application/json; charset=utf-8"
        return mimetypes.guess_type(path)[0] or "application/octet-stream"

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8")
        return json.loads(raw)

    def _send_json(self, payload: dict, status: int = 200) -> None:
        data = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def run(host: str = "127.0.0.1", port: int = 8765) -> None:
    server = ThreadingHTTPServer((host, port), DemoHandler)
    print(f"NARI demo API serving at http://{host}:{port}/web_ui/index.html")
    print(f"Project root: {Path(BASE_DIR)}")
    server.serve_forever()


def main() -> None:
    parser = argparse.ArgumentParser(description="NARI demo web/API server")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    run(args.host, args.port)


if __name__ == "__main__":
    main()
