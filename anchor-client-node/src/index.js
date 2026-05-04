// Node.js SDK for Anchor. Mirrors the Java anchor-client surface
// method-for-method and the Python anchor_client. Returns plain JS
// objects parsed from the JSON wire format (snake_case keys exactly as
// in SPEC.md). Uses built-in fetch — Node 18+.

import { extname } from "node:path";
import { readFile } from "node:fs/promises";
import { basename } from "node:path";

const TERMINAL_STATUSES = new Set(["COMPLETED", "FAILED", "CANCELLED"]);

export class AnchorClientError extends Error {
  constructor(message, { status, body } = {}) {
    super(message);
    this.name = "AnchorClientError";
    this.status = status;
    this.body = body;
  }
}

class Transport {
  constructor({ baseUrl, timeoutMs, apiToken }) {
    this.baseUrl = baseUrl.replace(/\/+$/, "");
    this.timeoutMs = timeoutMs;
    this.apiToken = apiToken || "";
  }

  _headers(extra) {
    const h = { Accept: "application/json" };
    if (this.apiToken) h.Authorization = `Bearer ${this.apiToken}`;
    return Object.assign(h, extra || {});
  }

  async _fetchWithTimeout(url, init) {
    if (!this.timeoutMs) return fetch(url, init);
    const controller = new AbortController();
    const id = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      return await fetch(url, { ...init, signal: controller.signal });
    } finally {
      clearTimeout(id);
    }
  }

  async _parse(res, label) {
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new AnchorClientError(`${label} → ${res.status}: ${text}`, {
        status: res.status,
        body: text,
      });
    }
    const ct = res.headers.get("content-type") || "";
    if (!ct.includes("application/json")) return null;
    return res.json();
  }

  async get(path) {
    const res = await this._fetchWithTimeout(this.baseUrl + path, {
      method: "GET",
      headers: this._headers(),
    });
    return this._parse(res, `GET ${path}`);
  }

  async postJson(path, body) {
    const res = await this._fetchWithTimeout(this.baseUrl + path, {
      method: "POST",
      headers: this._headers({ "Content-Type": "application/json" }),
      body: JSON.stringify(body),
    });
    return this._parse(res, `POST ${path}`);
  }

  async delete(path) {
    const res = await this._fetchWithTimeout(this.baseUrl + path, {
      method: "DELETE",
      headers: this._headers(),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new AnchorClientError(`DELETE ${path} → ${res.status}: ${text}`, {
        status: res.status,
        body: text,
      });
    }
  }

  async postFile(path, filePath, contentType) {
    const buf = await readFile(filePath);
    const blob = new Blob([buf], { type: contentType });
    const fd = new FormData();
    fd.append("file", blob, basename(filePath));
    const res = await this._fetchWithTimeout(this.baseUrl + path, {
      method: "POST",
      headers: this._headers(),  // fetch sets multipart Content-Type with boundary
      body: fd,
    });
    return this._parse(res, `POST ${path}`);
  }

  // SSE consumer — async generator yields each `data: ...` payload as it
  // arrives. Handles multi-line events and event boundaries (\n\n).
  async *streamLines(path) {
    const res = await fetch(this.baseUrl + path, {
      method: "GET",
      headers: this._headers({ Accept: "text/event-stream" }),
      // No timeout — SSE is long-lived.
    });
    if (!res.ok || !res.body) {
      const text = await res.text().catch(() => "");
      throw new AnchorClientError(`GET ${path} → ${res.status}: ${text}`, {
        status: res.status,
        body: text,
      });
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) return;
        buf += decoder.decode(value, { stream: true });
        let nl;
        while ((nl = buf.indexOf("\n")) >= 0) {
          const raw = buf.slice(0, nl).replace(/\r$/, "");
          buf = buf.slice(nl + 1);
          if (raw.startsWith("data:")) {
            yield raw.slice(5).trimStart();
          }
        }
      }
    } finally {
      reader.cancel().catch(() => {});
    }
  }
}

export class AnchorClient {
  /**
   * @param {object} opts
   * @param {string} [opts.baseUrl="http://localhost:8090"]
   * @param {number} [opts.timeoutMs=60000]
   * @param {string} [opts.apiToken]  Bearer token if the server has ANCHOR_API_TOKEN set.
   */
  constructor({ baseUrl = "http://localhost:8090", timeoutMs = 60_000, apiToken } = {}) {
    this._t = new Transport({ baseUrl, timeoutMs, apiToken });
  }

  // ---- Documents ------------------------------------------------------

  async listDocuments() {
    const body = (await this._t.get("/documents?limit=200&offset=0")) || {};
    return body.documents || [];
  }

  /**
   * Bind to a document. Pass exactly one of:
   *   { documentId: "<uuid>" }    — pure client-side, no network
   *   { titleSubstring: "..." }   — one resolution GET; throws on 0 or many matches
   */
  async use({ documentId, titleSubstring } = {}) {
    if (Boolean(documentId) === Boolean(titleSubstring)) {
      throw new AnchorClientError("Pass exactly one of documentId or titleSubstring.");
    }
    if (documentId) return new AnchorDocument(documentId, this._t);
    const body =
      (await this._t.get(
        `/documents?limit=10&offset=0&q=${encodeURIComponent(titleSubstring)}`
      )) || {};
    const docs = body.documents || [];
    if (docs.length === 0) {
      throw new AnchorClientError(`No document matched: ${titleSubstring}`);
    }
    if (docs.length > 1) {
      throw new AnchorClientError(
        `Ambiguous title substring "${titleSubstring}" matched ${docs.length} documents — disambiguate or pass documentId.`
      );
    }
    return new AnchorDocument(docs[0].document_id, this._t);
  }

  async searchDocuments(query, k = 5) {
    return this._t.get(
      `/documents/search?q=${encodeURIComponent(query)}&k=${Math.max(1, k)}`
    );
  }

  // ---- Ingest ---------------------------------------------------------

  async ingest(sourcePath) {
    return this._t.postJson("/ingest", { source_path: sourcePath });
  }

  async ingestUpload(localFile) {
    return this._t.postFile("/ingest/upload", localFile, guessContentType(localFile));
  }

  // ---- Health ---------------------------------------------------------

  async health() {
    return this._t.get("/actuator/health");
  }
}

export class AnchorDocument {
  constructor(documentId, transport) {
    this.documentId = documentId;
    this._t = transport;
  }

  describe() {
    return this._t.get(`/documents/${this.documentId}`);
  }

  retrieve(query, k = 8) {
    return this._t.postJson("/retrieve", {
      query,
      document_id: this.documentId,
      k,
    });
  }

  validate(chunkId, query) {
    return this._t.postJson("/validate", { chunk_id: chunkId, query });
  }

  /** Vector-only stance approximation — no LLM call. Pre-filter, not a substitute. */
  quickValidate(query) {
    return this._t.postJson("/validate/quick", {
      document_id: this.documentId,
      query,
    });
  }

  async ask(query) {
    const accepted = await this._t.postJson(
      `/documents/${this.documentId}/ask`,
      { query }
    );
    return new AskHandle(accepted.job_id, this.documentId, this._t);
  }
}

export class AskHandle {
  constructor(jobId, documentId, transport) {
    this.jobId = jobId;
    this.documentId = documentId;
    this._t = transport;
  }

  /** One network call — full job envelope. */
  snapshot() {
    return this._t.get(`/jobs/${this.jobId}`);
  }

  async status() {
    return (await this.snapshot()).status;
  }

  /** Poll GET /jobs/{id} until terminal or timeout (ms). */
  async awaitCompletion({ timeoutMs = 120_000, pollIntervalMs = 250 } = {}) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const snap = await this.snapshot();
      if (TERMINAL_STATUSES.has(snap.status)) return snap;
      await new Promise((r) => setTimeout(r, pollIntervalMs));
    }
    throw new AnchorClientError(`Deliberation did not complete within ${timeoutMs}ms`);
  }

  /** Async iterable — yields parsed JobEvent objects as they arrive over SSE. */
  async *streamEvents() {
    for await (const data of this._t.streamLines(`/jobs/${this.jobId}/stream`)) {
      try {
        yield JSON.parse(data);
      } catch {
        // malformed event — snapshot is the source of truth
      }
    }
  }

  /** Best-effort cancel. Server flips status; in-flight model call still finishes. */
  cancel() {
    return this._t.delete(`/jobs/${this.jobId}`);
  }
}

function guessContentType(filePath) {
  const ext = extname(filePath).toLowerCase();
  switch (ext) {
    case ".pdf":  return "application/pdf";
    case ".epub": return "application/epub+zip";
    case ".docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    case ".rtf":  return "application/rtf";
    case ".html":
    case ".htm":  return "text/html";
    case ".txt":
    case ".md":   return "text/plain";
    default:      return "application/octet-stream";
  }
}
