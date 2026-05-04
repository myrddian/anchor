# @aeyer/anchor-client (Node.js)

Node.js SDK for [Anchor](../) — source-grounded chunk validation and
three-agent deliberation. Mirrors the Java `anchor-client` and the
Python `anchor_client` method-for-method.

ESM-only, zero runtime dependencies (uses built-in `fetch` and the
Web Streams API). Requires **Node 18+**.

## Install

```bash
npm install @aeyer/anchor-client
```

(No npm release yet; until then, link from this checkout.)

## Quick start

```js
import { AnchorClient } from "@aeyer/anchor-client";

const client = new AnchorClient({
  baseUrl: "http://localhost:8090",
  apiToken: process.env.ANCHOR_API_TOKEN,   // omit if the server has no token set
});

// Add a paper. Both endpoints are async — return an IngestHandle.
const handle = await client.ingestUpload("./papers/dawkins-extended-phenotype.epub");
const result = await handle.awaitCompletion({
  timeoutMs: 30 * 60 * 1000,
  onProgress: (s) => console.log(`${s.percent_complete}% ${s.phase} — ${s.message ?? ""}`),
});
console.log(`Ingested ${result.result.title} as ${result.document_id}`);

// Or hand the server a path it can read directly:
await (await client.ingest("/srv/papers/...")).awaitCompletion();

// Browse / search.
for (const d of await client.listDocuments()) console.log(d.title);
const hits = await client.searchDocuments("group selection in social insects", 5);

// Bind a document and query it.
const doc = await client.use({ titleSubstring: "extended phenotype" });
const quick = await doc.quickValidate(
  "Group selection is the dominant force in social insect evolution"
);
console.log(quick.stance_score);

const handle = await doc.ask(
  "Does the extended phenotype concept require redefining the gene?"
);

// Either block:
const result = await handle.awaitCompletion({ timeoutMs: 120_000 });
console.log(result.final_response);

// Or stream:
for await (const event of handle.streamEvents()) {
  if (event.type === "PROPOSER_THOUGHT") process.stdout.write(event.token || "");
  else if (event.type === "COMPLETED" || event.type === "FAILED") break;
}
```

## Auth

When the server runs with `ANCHOR_API_TOKEN` set, every request needs
`Authorization: Bearer <token>`. Pass it once when constructing the
client:

```js
const client = new AnchorClient({ apiToken: process.env.ANCHOR_API_TOKEN });
```

The token rides every HTTP call **and** the SSE subscription used by
`AskHandle.streamEvents()`.

## API surface

| Method | What it hits |
|---|---|
| `client.listDocuments()` | `GET /documents` |
| `client.use({ documentId })` / `use({ titleSubstring })` | client-side; titleSubstring does one resolution `GET` |
| `client.searchDocuments(query, k)` | `GET /documents/search` |
| `client.ingest(path)` → `IngestHandle` | `POST /ingest` (202) |
| `client.ingestUpload(path)` → `IngestHandle` | `POST /ingest/upload` (202) |
| `ingestHandle.snapshot()` / `status()` | `GET /ingest/jobs/{id}` |
| `ingestHandle.awaitCompletion({ timeoutMs, onProgress })` | polls `GET /ingest/jobs/{id}` |
| `doc.describe()` | `GET /documents/{id}` |
| `doc.retrieve(query, k)` | `POST /retrieve` |
| `doc.validate(chunkId, query)` | `POST /validate` |
| `doc.quickValidate(query)` | `POST /validate/quick` |
| `doc.ask(query)` → `AskHandle` | `POST /documents/{id}/ask` |
| `handle.snapshot()` / `status()` | `GET /jobs/{id}` |
| `handle.awaitCompletion({ timeoutMs })` | polls `GET /jobs/{id}` |
| `handle.streamEvents()` | `GET /jobs/{id}/stream` (SSE async iterable) |
| `handle.cancel()` | `DELETE /jobs/{id}` |

`AnchorClientError` wraps any non-2xx response and any title-resolution
miss (zero or many matches in `use({ titleSubstring })`).

## License

Apache 2.0 — same as Anchor.
