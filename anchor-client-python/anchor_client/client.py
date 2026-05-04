from __future__ import annotations

import json
import mimetypes
import time
from pathlib import Path
from typing import Any, Iterator, Optional, Union
from urllib.parse import quote_plus

import requests

from .exceptions import AnchorClientError


_TERMINAL_STATUSES = {"COMPLETED", "FAILED", "CANCELLED"}
_INGEST_TERMINAL_STATUSES = {"COMPLETED", "FAILED", "CANCELLED"}


class _Transport:
    """Thin requests wrapper. Centralises base URL, timeout, bearer header,
    and JSON-or-raise."""

    def __init__(self, base_url: str, timeout: float, api_token: Optional[str]):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.api_token = api_token or ""

    def _headers(self, extra: Optional[dict] = None) -> dict:
        headers = {"Accept": "application/json"}
        if self.api_token:
            headers["Authorization"] = f"Bearer {self.api_token}"
        if extra:
            headers.update(extra)
        return headers

    def get(self, path: str) -> Any:
        r = requests.get(self.base_url + path, headers=self._headers(), timeout=self.timeout)
        return self._parse(r, "GET " + path)

    def post_json(self, path: str, body: Any) -> Any:
        r = requests.post(
            self.base_url + path,
            data=json.dumps(body),
            headers=self._headers({"Content-Type": "application/json"}),
            timeout=self.timeout,
        )
        return self._parse(r, "POST " + path)

    def delete(self, path: str) -> None:
        r = requests.delete(self.base_url + path, headers=self._headers(), timeout=self.timeout)
        if r.status_code >= 300:
            raise AnchorClientError(f"DELETE {path} → {r.status_code}: {r.text}")

    def post_file(self, path: str, file: Path, content_type: str) -> Any:
        with file.open("rb") as fh:
            files = {"file": (file.name, fh, content_type)}
            r = requests.post(
                self.base_url + path,
                files=files,
                headers=self._headers(),  # requests sets multipart Content-Type
                timeout=self.timeout,
            )
        return self._parse(r, "POST " + path)

    def stream_lines(self, path: str) -> Iterator[str]:
        """SSE consumer — yields each `data: ...` payload as it arrives."""
        r = requests.get(
            self.base_url + path,
            headers=self._headers({"Accept": "text/event-stream"}),
            stream=True,
            timeout=None,  # SSE is long-lived
        )
        if r.status_code >= 300:
            raise AnchorClientError(f"GET {path} → {r.status_code}: {r.text}")
        try:
            for raw in r.iter_lines(decode_unicode=True):
                if raw is None:
                    continue
                if raw.startswith("data:"):
                    yield raw[5:].lstrip()
        finally:
            r.close()

    @staticmethod
    def _parse(r: requests.Response, label: str) -> Any:
        if r.status_code >= 300:
            raise AnchorClientError(f"{label} → {r.status_code}: {r.text}")
        if not r.content:
            return None
        return r.json()


class AnchorClient:
    """Top-level entry point. Construct once per server."""

    def __init__(
        self,
        base_url: str = "http://localhost:8090",
        timeout: float = 60.0,
        api_token: Optional[str] = None,
    ):
        self._t = _Transport(base_url, timeout, api_token)

    # ---- Documents ------------------------------------------------------

    def list_documents(self) -> list[dict]:
        """All ingested documents — single page (limit=200) for now."""
        body = self._t.get("/documents?limit=200&offset=0") or {}
        return body.get("documents", [])

    def use(
        self,
        document_id: Optional[str] = None,
        title_substring: Optional[str] = None,
    ) -> "AnchorDocument":
        """Bind to a document. Provide exactly one of `document_id` (UUID
        string) or `title_substring`. Title resolution does one server hit."""
        if (document_id is None) == (title_substring is None):
            raise AnchorClientError("Pass exactly one of document_id or title_substring.")
        if document_id is not None:
            return AnchorDocument(document_id, self._t)
        body = self._t.get(
            f"/documents?limit=10&offset=0&q={quote_plus(title_substring)}"
        ) or {}
        docs = body.get("documents") or []
        if not docs:
            raise AnchorClientError(f"No document matched: {title_substring}")
        if len(docs) > 1:
            raise AnchorClientError(
                f'Ambiguous title substring "{title_substring}" matched '
                f"{len(docs)} documents — disambiguate or pass document_id."
            )
        return AnchorDocument(docs[0]["document_id"], self._t)

    def search_documents(self, query: str, k: int = 5) -> dict:
        """Semantic search across documents — ranks by cosine of the query
        embedding against each doc's stored summary embedding."""
        return self._t.get(f"/documents/search?q={quote_plus(query)}&k={max(1, k)}")

    # ---- Ingest ---------------------------------------------------------

    def ingest(self, source_path: str) -> "IngestHandle":
        """Server-side ingest — server reads the path from its own filesystem.
        Returns immediately with an IngestHandle; poll progress or block via
        ``handle.await_completion()``. Pipeline runs minutes on a real book."""
        accepted = self._t.post_json("/ingest", {"source_path": source_path})
        return IngestHandle(accepted["job_id"], self._t)

    def ingest_upload(self, local_file: Union[str, Path]) -> "IngestHandle":
        """Multipart upload then ingest. Returns an IngestHandle for progress
        polling — use when the server can't see the local path."""
        path = Path(local_file)
        accepted = self._t.post_file("/ingest/upload", path, _guess_content_type(path))
        return IngestHandle(accepted["job_id"], self._t)

    # ---- Health / introspection ----------------------------------------

    def health(self) -> dict:
        return self._t.get("/actuator/health")


class AnchorDocument:
    """Document-bound handle. Construct via `AnchorClient.use(...)`."""

    def __init__(self, document_id: str, transport: _Transport):
        self.document_id = document_id
        self._t = transport

    def describe(self) -> dict:
        return self._t.get(f"/documents/{self.document_id}")

    def retrieve(self, query: str, k: int = 8) -> dict:
        return self._t.post_json(
            "/retrieve",
            {"query": query, "document_id": self.document_id, "k": k},
        )

    def validate(self, chunk_id: str, query: str) -> dict:
        return self._t.post_json("/validate", {"chunk_id": chunk_id, "query": query})

    def quick_validate(self, query: str) -> dict:
        """Vector-only stance approximation — no LLM call. Use as a pre-filter
        before `ask()`."""
        return self._t.post_json(
            "/validate/quick",
            {"document_id": self.document_id, "query": query},
        )

    def ask(self, query: str) -> "AskHandle":
        """Submit a deliberation. Returns immediately with an AskHandle."""
        accepted = self._t.post_json(
            f"/documents/{self.document_id}/ask", {"query": query}
        )
        return AskHandle(accepted["job_id"], self.document_id, self._t)


class AskHandle:
    """Live handle for a deliberation. Mirrors the Java AskHandle: poll via
    `status()` / `snapshot()`, block via `await_completion()`, stream via
    `stream_events()`, give up via `cancel()`."""

    def __init__(self, job_id: str, document_id: str, transport: _Transport):
        self.job_id = job_id
        self.document_id = document_id
        self._t = transport

    def snapshot(self) -> dict:
        """One network call — returns the full job envelope."""
        return self._t.get(f"/jobs/{self.job_id}")

    def status(self) -> str:
        return self.snapshot().get("status", "")

    def await_completion(self, timeout: float = 120.0, poll_interval: float = 0.25) -> dict:
        """Block until the job reaches a terminal state or `timeout` elapses.
        Polls GET /jobs/{id}; for token-level streaming use `stream_events`."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            snap = self.snapshot()
            if snap.get("status") in _TERMINAL_STATUSES:
                return snap
            time.sleep(poll_interval)
        raise AnchorClientError(f"Deliberation did not complete within {timeout}s")

    def stream_events(self) -> Iterator[dict]:
        """Iterate JobEvent payloads as they arrive over SSE. Closes when the
        server emits a terminal event and disconnects, or when the iterator is
        garbage-collected."""
        for data in self._t.stream_lines(f"/jobs/{self.job_id}/stream"):
            try:
                yield json.loads(data)
            except json.JSONDecodeError:
                continue  # malformed event — snapshot is the source of truth

    def cancel(self) -> None:
        """Best-effort cancel. Server flips status; an in-flight model call
        still finishes."""
        self._t.delete(f"/jobs/{self.job_id}")


class IngestHandle:
    """Live handle for an async ingest job. Mirrors AskHandle: poll via
    ``snapshot()`` / ``status()``, block via ``await_completion()``."""

    def __init__(self, job_id: str, transport: _Transport):
        self.job_id = job_id
        self._t = transport

    def snapshot(self) -> dict:
        """Full progress envelope including phase / percent / message."""
        return self._t.get(f"/ingest/jobs/{self.job_id}")

    def status(self) -> str:
        return self.snapshot().get("status", "")

    def await_completion(
        self,
        timeout: float = 1800.0,
        poll_interval: float = 1.0,
        on_progress=None,
    ) -> dict:
        """Block until terminal or timeout. Calls ``on_progress(snap)`` after
        each poll if provided. Default 30-minute timeout — long books."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            snap = self.snapshot()
            if on_progress is not None:
                on_progress(snap)
            if snap.get("status") in _INGEST_TERMINAL_STATUSES:
                return snap
            time.sleep(poll_interval)
        raise AnchorClientError(f"Ingest did not complete within {timeout}s")


def _guess_content_type(path: Path) -> str:
    name = path.name.lower()
    if name.endswith(".pdf"):
        return "application/pdf"
    if name.endswith(".epub"):
        return "application/epub+zip"
    if name.endswith(".docx"):
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    if name.endswith(".rtf"):
        return "application/rtf"
    if name.endswith((".html", ".htm")):
        return "text/html"
    if name.endswith((".txt", ".md")):
        return "text/plain"
    guessed, _ = mimetypes.guess_type(name)
    return guessed or "application/octet-stream"
