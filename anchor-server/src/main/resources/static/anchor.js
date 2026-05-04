// Anchor — minimal browser UI. Vanilla ES2020, no framework, no build step.
// Talks to the same JSON / SSE endpoints the SDK and shell use.

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
};

let activeStream = null;

async function loadDocuments() {
  try {
    const response = await fetch("/documents?limit=200&offset=0");
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
    const response = await fetch(`/documents/search?q=${encodeURIComponent(query)}&k=20`);
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
    activeStream.close();
    activeStream = null;
  }
  resetPanels();
  clearError();
  els.statusBar.classList.remove("hidden");
  setStatus("QUEUED");
  els.button.disabled = true;

  const response = await fetch(`/documents/${documentId}/ask`, {
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

function subscribe(jobId) {
  const source = new EventSource(`/jobs/${jobId}/stream`);
  activeStream = source;

  source.addEventListener("status", (e) => {
    const data = JSON.parse(e.data);
    if (data.status) setStatus(data.status);
  });

  source.addEventListener("proposer_thought", (e) => {
    const data = JSON.parse(e.data);
    if (data.token) appendToken(els.proposer, data.token);
  });
  source.addEventListener("proposer_complete", (e) => {
    const data = JSON.parse(e.data);
    finalizePanel(els.proposer, data.response);
  });

  source.addEventListener("critic_thought", () => {
    // Critic output is JSON, not prose — render it on critic_complete.
  });
  source.addEventListener("critic_complete", (e) => {
    const data = JSON.parse(e.data);
    renderCriticComplete(data.response);
  });

  source.addEventListener("synthesiser_thought", (e) => {
    const data = JSON.parse(e.data);
    if (data.token) appendToken(els.synthesiser, data.token);
  });
  source.addEventListener("synthesiser_complete", (e) => {
    const data = JSON.parse(e.data);
    // Server sends raw output here; the cleaner final response arrives on
    // the `completed` event below (parsed by SynthesiserOutputParser).
    finalizePanel(els.synthesiser, data.response);
  });

  source.addEventListener("completed", (e) => {
    const data = JSON.parse(e.data);
    setStatus("COMPLETED");
    if (data.response) finalizePanel(els.synthesiser, data.response);
    fetchAndRenderGrounding(jobId);
    cleanupStream();
  });

  source.addEventListener("failed", (e) => {
    const data = JSON.parse(e.data);
    setStatus("FAILED");
    showError(`Deliberation failed: ${data.error || "(no detail)"}`);
    cleanupStream();
  });
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
    const response = await fetch(`/jobs/${jobId}`);
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
    activeStream.close();
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
    const response = await fetch("/ingest/upload", { method: "POST", body });
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
    const response = await fetch(`/ingest/jobs/${jobId}`);
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

loadDocuments();
