// Anchor — minimal browser UI. Vanilla ES2020, no framework, no build step.
// Talks to the same JSON endpoints the SDK and shell use, plus the
// deliberation stream over fetch+ReadableStream (instead of EventSource so
// the Bearer header can ride the SSE handshake when ANCHOR_API_TOKEN is set).

const $ = (id) => document.getElementById(id);
const els = {
  form: $("ask-form"),
  documentSearch: $("document-search"),
  document: $("document"),
  query: $("query"),
  button: $("ask-button"),
  uploadForm: $("upload-form"),
  uploadFile: $("upload-file"),
  uploadButton: $("upload-button"),
  uploadStatus: $("upload-status"),
  statusBar: $("status-bar"),
  status: $("status"),
  jobId: $("job-id"),
  proposer: $("proposer-content"),
  criticChallenges: $("critic-challenges"),
  synthesiser: $("synthesiser-content"),
  grounding: $("grounding-panel"),
  groundingContent: $("grounding-content"),
  tokenOverlay: $("token-overlay"),
  tokenForm: $("token-form"),
  tokenInput: $("token-input"),
  tokenError: $("token-error"),
  tokenSubmit: $("token-submit"),
};

let activeStream = null;          // AbortController for the in-flight ask stream
let serverRequiresAuth = false;   // set during boot from /anchor/ui/config

// ---- Auth: sessionStorage-backed Bearer token --------------------------

const TOKEN_KEY = "anchor.api-token";

function getToken()      { return sessionStorage.getItem(TOKEN_KEY) || ""; }
function setToken(t)     { sessionStorage.setItem(TOKEN_KEY, t); }
function clearToken()    { sessionStorage.removeItem(TOKEN_KEY); }

/**
 * fetch wrapper that attaches `Authorization: Bearer <token>` when one is
 * stored, and bounces back to the token prompt on 401 (clearing the bad
 * token first so the user can paste a fresh one).
 */
async function authedFetch(url, opts = {}) {
  const token = getToken();
  const headers = new Headers(opts.headers || {});
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const res = await fetch(url, { ...opts, headers });
  if (res.status === 401 && serverRequiresAuth) {
    clearToken();
    showTokenPrompt("That token didn't work — paste a valid one.");
    throw new Error("unauthorized");
  }
  return res;
}

function showTokenPrompt(errorMessage) {
  if (errorMessage) {
    els.tokenError.textContent = errorMessage;
    els.tokenError.classList.remove("hidden");
  } else {
    els.tokenError.classList.add("hidden");
  }
  els.tokenOverlay.classList.remove("hidden");
  document.body.classList.add("token-locked");
  els.tokenInput.focus();
}

function hideTokenPrompt() {
  els.tokenOverlay.classList.add("hidden");
  document.body.classList.remove("token-locked");
  els.tokenInput.value = "";
  els.tokenError.classList.add("hidden");
}

els.tokenForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const candidate = els.tokenInput.value.trim();
  if (!candidate) return;
  els.tokenSubmit.disabled = true;
  els.tokenError.classList.add("hidden");
  // Probe by hitting a token-gated endpoint. /documents is cheap and the
  // server responds 200 even with zero documents, so a 200 means the
  // token's accepted; 401 means try again.
  try {
    const res = await fetch("/documents?limit=1&offset=0", {
      headers: { Authorization: `Bearer ${candidate}` },
    });
    if (res.status === 401) {
      els.tokenError.textContent = "Server rejected that token.";
      els.tokenError.classList.remove("hidden");
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    setToken(candidate);
    hideTokenPrompt();
    await loadDocuments();
  } catch (err) {
    els.tokenError.textContent = `Couldn't verify token: ${err.message}`;
    els.tokenError.classList.remove("hidden");
  } finally {
    els.tokenSubmit.disabled = false;
  }
});

/**
 * Boot: figure out whether the server requires auth, then either prompt for
 * the token or jump straight to loading documents. /anchor/ui/config is
 * exempt from the API-token filter so this works before the user has typed
 * anything.
 */
async function bootstrap() {
  try {
    const res = await fetch("/anchor/ui/config");
    if (res.ok) {
      const cfg = await res.json();
      serverRequiresAuth = !!cfg.auth_required;
    }
  } catch {
    // Older server without the endpoint, or transient — assume no auth and
    // let later requests reveal a 401 if we're wrong.
  }
  if (serverRequiresAuth && !getToken()) {
    showTokenPrompt(null);
    return;
  }
  await loadDocuments();
}

async function loadDocuments() {
  try {
    const response = await authedFetch("/documents?limit=200&offset=0");
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const body = await response.json();
    populateDocumentDropdown(body.documents || [], { mode: "list" });
  } catch (err) {
    showError(`Could not load documents: ${err.message}. Is the server running?`);
    els.button.disabled = true;
  }
}

async function searchDocuments(query) {
  try {
    const response = await authedFetch(`/documents/search?q=${encodeURIComponent(query)}&k=20`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const body = await response.json();
    populateDocumentDropdown(body.hits || [], { mode: "search" });
  } catch (err) {
    showError(`Search failed: ${err.message}`);
  }
}

/**
 * Render a list of {document_id, title, ingested_at, score?} into the
 * dropdown. Both `/documents` and `/documents/search` produce shapes that
 * fit this normaliser; the only visible difference is whether a relevance
 * score badge is appended in front of the title.
 */
function populateDocumentDropdown(items, { mode }) {
  els.document.replaceChildren();
  if (!items || items.length === 0) {
    const opt = document.createElement("option");
    opt.value = "";
    opt.textContent = mode === "search"
      ? "No matches — try a broader query or clear search"
      : "No documents ingested yet — upload one above or use the shell";
    opt.disabled = true;
    els.document.appendChild(opt);
    els.button.disabled = true;
    return;
  }
  const placeholder = document.createElement("option");
  placeholder.value = "";
  placeholder.textContent = mode === "search"
    ? `Select a match (${items.length})…`
    : "Select a document…";
  placeholder.disabled = true;
  placeholder.selected = true;
  els.document.appendChild(placeholder);

  for (const item of items) {
    const opt = document.createElement("option");
    opt.value = item.document_id;
    const date = item.ingested_at ? item.ingested_at.slice(0, 10) : "";
    const score = mode === "search" && typeof item.score === "number"
      ? `[${item.score.toFixed(2)}]  `
      : "";
    opt.textContent = date
      ? `${score}${item.title}  ·  ${date}`
      : `${score}${item.title}`;
    els.document.appendChild(opt);
  }
  els.button.disabled = false;
}

function showError(message) {
  const existing = document.querySelector(".error-banner");
  if (existing) existing.remove();
  const banner = document.createElement("div");
  banner.className = "error-banner";
  banner.textContent = message;
  els.form.after(banner);
}

function clearError() {
  const existing = document.querySelector(".error-banner");
  if (existing) existing.remove();
}

function resetPanels() {
  els.proposer.replaceChildren();
  els.criticChallenges.replaceChildren();
  els.synthesiser.replaceChildren();
  els.grounding.classList.add("hidden");
  els.groundingContent.textContent = "";
}

function setStatus(state) {
  els.status.textContent = state;
  els.status.dataset.state = state;
}

function appendToken(panel, token) {
  // Remove a trailing cursor placeholder if present, append token, re-add cursor.
  const cursor = panel.querySelector(".cursor");
  if (cursor) cursor.remove();
  panel.appendChild(document.createTextNode(token));
  const c = document.createElement("span");
  c.className = "cursor";
  panel.appendChild(c);
}

function finalizePanel(panel, fullText) {
  const cursor = panel.querySelector(".cursor");
  if (cursor) cursor.remove();
  if (fullText !== undefined && fullText !== null) {
    // Replace streamed text with the canonical complete-event payload.
    panel.textContent = fullText;
  }
}

async function startAsk(documentId, query) {
  if (activeStream) {
    if (typeof activeStream.abort === "function") activeStream.abort();
    else if (typeof activeStream.close === "function") activeStream.close();
    activeStream = null;
  }
  resetPanels();
  clearError();
  els.statusBar.classList.remove("hidden");
  setStatus("QUEUED");
  els.button.disabled = true;

  const response = await authedFetch(`/documents/${documentId}/ask`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query }),
  });
  if (!response.ok) {
    showError(`Ask failed: HTTP ${response.status}`);
    els.button.disabled = false;
    return;
  }
  const body = await response.json();
  els.jobId.textContent = body.job_id;
  subscribe(body.job_id);
}

/**
 * Stream the deliberation events from /jobs/{id}/stream. We use fetch +
 * ReadableStream instead of EventSource so the Authorization header can
 * ride the request — EventSource is GET-only with no header support, which
 * doesn't survive ANCHOR_API_TOKEN being set.
 *
 * Parses SSE manually: events are blocks separated by a blank line, with
 * `event: <name>` and `data: <json>` lines. We don't need `id:` or
 * reconnect support — the server emits a terminal `completed` / `failed`
 * event then closes, and the polling fallback in fetchAndRenderGrounding
 * covers the case where the stream drops mid-flight.
 */
async function subscribe(jobId) {
  const controller = new AbortController();
  activeStream = controller;

  let response;
  try {
    response = await authedFetch(`/jobs/${jobId}/stream`, {
      headers: { Accept: "text/event-stream" },
      signal: controller.signal,
    });
  } catch (err) {
    if (err.name !== "AbortError") {
      showError(`Could not open deliberation stream: ${err.message}`);
    }
    cleanupStream();
    return;
  }
  if (!response.ok || !response.body) {
    showError(`Stream open failed: HTTP ${response.status}`);
    cleanupStream();
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      // Split off complete events (blank-line-terminated blocks).
      let boundary;
      while ((boundary = buf.indexOf("\n\n")) >= 0) {
        const block = buf.slice(0, boundary);
        buf = buf.slice(boundary + 2);
        const evt = parseSseBlock(block);
        if (evt) handleStreamEvent(jobId, evt.event, evt.data);
      }
    }
  } catch (err) {
    if (err.name !== "AbortError") {
      showError(`Stream read failed: ${err.message}`);
    }
  } finally {
    reader.cancel().catch(() => {});
    cleanupStream();
  }
}

function parseSseBlock(block) {
  let event = "message";
  const dataLines = [];
  for (const line of block.split("\n")) {
    if (!line || line.startsWith(":")) continue; // comment / heartbeat
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
    if (field === "event") event = value;
    else if (field === "data") dataLines.push(value);
  }
  if (dataLines.length === 0) return null;
  try {
    return { event, data: JSON.parse(dataLines.join("\n")) };
  } catch {
    return null;  // malformed; snapshot endpoint is the source of truth
  }
}

function handleStreamEvent(jobId, type, data) {
  switch (type) {
    case "status":
      if (data.status) setStatus(data.status);
      break;
    case "proposer_thought":
      if (data.token) appendToken(els.proposer, data.token);
      break;
    case "proposer_complete":
      finalizePanel(els.proposer, data.response);
      break;
    case "critic_thought":
      // Critic output is JSON, not prose — render it on critic_complete.
      break;
    case "critic_complete":
      renderCriticComplete(data.response);
      break;
    case "synthesiser_thought":
      if (data.token) appendToken(els.synthesiser, data.token);
      break;
    case "synthesiser_complete":
      // Server sends raw output here; the cleaner final response arrives on
      // the `completed` event below (parsed by SynthesiserOutputParser).
      finalizePanel(els.synthesiser, data.response);
      break;
    case "completed":
      setStatus("COMPLETED");
      if (data.response) finalizePanel(els.synthesiser, data.response);
      fetchAndRenderGrounding(jobId);
      break;
    case "failed":
      setStatus("FAILED");
      showError(`Deliberation failed: ${data.error || "(no detail)"}`);
      break;
  }
}

function renderCriticComplete(rawJsonOrText) {
  els.criticChallenges.replaceChildren();
  if (!rawJsonOrText) return;
  let challenges = [];
  try {
    let text = rawJsonOrText.trim();
    if (text.startsWith("```")) {
      text = text.replace(/^```(json)?\s*/, "").replace(/```$/, "").trim();
    }
    const obj = JSON.parse(text);
    if (Array.isArray(obj.challenges)) challenges = obj.challenges;
  } catch (e) {
    // Fall back: show the raw text so the chemist can see it.
    challenges = [rawJsonOrText];
  }
  if (challenges.length === 0) {
    const li = document.createElement("li");
    li.style.color = "var(--fg-muted)";
    li.style.fontStyle = "italic";
    li.textContent = "Critic raised no challenges — the proposer's draft holds up against the macro view.";
    els.criticChallenges.appendChild(li);
    return;
  }
  for (const c of challenges) {
    const li = document.createElement("li");
    li.textContent = c;
    els.criticChallenges.appendChild(li);
  }
}

async function fetchAndRenderGrounding(jobId) {
  try {
    const response = await authedFetch(`/jobs/${jobId}`);
    if (!response.ok) return;
    const job = await response.json();
    if (job.final_response) finalizePanel(els.synthesiser, job.final_response);
    if (job.synthesiser && job.synthesiser.grounding) {
      els.grounding.classList.remove("hidden");
      els.groundingContent.textContent = JSON.stringify(job.synthesiser.grounding, null, 2);
    }
  } catch (e) { /* non-fatal */ }
}

function cleanupStream() {
  if (activeStream) {
    // AbortController in the new fetch-based path; .abort() unblocks the
    // pending read() and lets the consumer fall through the finally.
    if (typeof activeStream.abort === "function") activeStream.abort();
    else if (typeof activeStream.close === "function") activeStream.close();
    activeStream = null;
  }
  els.button.disabled = false;
}

els.form.addEventListener("submit", (event) => {
  event.preventDefault();
  const documentId = els.document.value;
  const query = els.query.value.trim();
  if (!documentId || !query) return;
  startAsk(documentId, query);
});

// ---- Upload form ----------------------------------------------------------

function setUploadStatus(message, kind) {
  els.uploadStatus.textContent = message || "";
  els.uploadStatus.classList.remove("ok", "err");
  if (kind) els.uploadStatus.classList.add(kind);
}

function renderProgress(percent, phase, message) {
  const pct = Math.max(0, Math.min(100, percent || 0));
  const phaseLabel = (phase || "").toLowerCase().replace(/_/g, " ");
  const msg = message ? ` — ${message}` : "";
  setUploadStatus(`${pct}%  ·  ${phaseLabel}${msg}`, null);
  // Use a simple linear-gradient bar inside the status line so we don't need
  // to reshape the upload-form grid for a separate <progress> element.
  els.uploadStatus.style.background =
    `linear-gradient(to right, var(--accent-subtle) 0%, var(--accent-subtle) ${pct}%, transparent ${pct}%, transparent 100%)`;
}

function clearProgressBar() {
  els.uploadStatus.style.background = "";
}

async function uploadAndIngest(file) {
  els.uploadButton.disabled = true;
  setUploadStatus(`Uploading ${file.name}…`, null);
  clearProgressBar();
  const body = new FormData();
  body.append("file", file);
  try {
    // Submit returns 202 with a job_id — server runs the multi-minute
    // pipeline on the ingest pool while we poll for progress here.
    const response = await authedFetch("/ingest/upload", { method: "POST", body });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`HTTP ${response.status}: ${text || response.statusText}`);
    }
    const accepted = await response.json();
    const jobId = accepted.job_id;
    if (!jobId) throw new Error("Server did not return a job_id");
    const job = await pollIngestJob(jobId);
    if (job.status === "FAILED") {
      throw new Error(job.error || "ingest failed (no error message)");
    }
    if (job.status === "CANCELLED") {
      throw new Error("ingest cancelled");
    }
    const result = job.result || {};
    setUploadStatus(
      `✓ Ingested "${result.title || job.title}" — ${result.chapter_count ?? "?"} chapters, ${result.chunk_count ?? "?"} chunks.`,
      "ok",
    );
    clearProgressBar();
    els.uploadFile.value = "";
    await loadDocuments();
    // Auto-select the freshly ingested document so the next ask targets it.
    const docId = result.document_id || job.document_id;
    if (docId) els.document.value = docId;
  } catch (err) {
    clearProgressBar();
    setUploadStatus(`✗ Upload failed: ${err.message}`, "err");
  } finally {
    els.uploadButton.disabled = false;
  }
}

/**
 * Poll GET /ingest/jobs/{id} every second until the job reaches a terminal
 * state. Renders progress on each tick. Resolves with the final job
 * envelope (caller checks status to differentiate completed/failed).
 */
async function pollIngestJob(jobId) {
  while (true) {
    const response = await authedFetch(`/ingest/jobs/${jobId}`);
    if (!response.ok) {
      throw new Error(`progress fetch HTTP ${response.status}`);
    }
    const job = await response.json();
    renderProgress(job.percent_complete, job.phase, job.message);
    if (job.status === "COMPLETED" || job.status === "FAILED" || job.status === "CANCELLED") {
      return job;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
}

els.uploadForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const file = els.uploadFile.files[0];
  if (!file) return;
  uploadAndIngest(file);
});

// ---- Document search (debounced) ------------------------------------------
let searchTimer = null;
els.documentSearch.addEventListener("input", () => {
  if (searchTimer) clearTimeout(searchTimer);
  const query = els.documentSearch.value.trim();
  searchTimer = setTimeout(() => {
    if (query === "") {
      loadDocuments();
    } else {
      searchDocuments(query);
    }
  }, 250);
});

bootstrap();
