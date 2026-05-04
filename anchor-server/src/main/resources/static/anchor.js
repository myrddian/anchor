// Anchor — minimal browser UI. Vanilla ES2020, no framework, no build step.
// Talks to the same JSON / SSE endpoints the SDK and shell use.

const $ = (id) => document.getElementById(id);
const els = {
  form: $("ask-form"),
  document: $("document"),
  query: $("query"),
  button: $("ask-button"),
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
    els.document.replaceChildren();
    if (!body.documents || body.documents.length === 0) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "No documents ingested yet — use the shell to ingest one";
      opt.disabled = true;
      els.document.appendChild(opt);
      els.button.disabled = true;
      return;
    }
    const placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = "Select a document…";
    placeholder.disabled = true;
    placeholder.selected = true;
    els.document.appendChild(placeholder);
    for (const d of body.documents) {
      const opt = document.createElement("option");
      opt.value = d.document_id;
      opt.textContent = `${d.title}  ·  ${d.chapter_count}ch ${d.section_count}sec ${d.chunk_count} chunks`;
      els.document.appendChild(opt);
    }
  } catch (err) {
    showError(`Could not load documents: ${err.message}. Is the server running?`);
    els.button.disabled = true;
  }
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

loadDocuments();
