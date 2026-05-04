# anchor-client (Python)

Python SDK for [Anchor](../) — source-grounded chunk validation and
three-agent deliberation over your own document corpus.

Mirrors the Java `anchor-client` API: same endpoints, same "select a
document, then query it" UX. Returns plain `dict`s parsed from the JSON
wire format (snake_case keys exactly as documented in [`SPEC.md`](../SPEC.md)).

## Install

```bash
pip install -e .
```

(No PyPI release yet; install from this checkout.)

## Quick start

```python
from anchor_client import AnchorClient

client = AnchorClient(
    base_url="http://localhost:8090",
    api_token=None,                 # set when ANCHOR_API_TOKEN is enabled server-side
)

# Add a paper. Either way works:
client.ingest("/srv/papers/dawkins-extended-phenotype.epub")        # server-side path
client.ingest_upload("./papers/dawkins-extended-phenotype.epub")    # multipart upload

# Browse / search.
for d in client.list_documents():
    print(d["title"])

hits = client.search_documents("group selection in social insects", k=5)

# Bind a document and query it.
doc = client.use(title_substring="extended phenotype")
quick = doc.quick_validate("Group selection is the dominant force in social insect evolution")
print(quick["stance_score"])

handle = doc.ask("Does the extended phenotype concept require redefining the gene?")

# Either block:
result = handle.await_completion(timeout=120)
print(result["final_response"])

# Or stream:
for event in handle.stream_events():
    if event["type"] == "PROPOSER_THOUGHT":
        print(event.get("token", ""), end="", flush=True)
    elif event["type"] in ("COMPLETED", "FAILED"):
        break
```

## Auth

When the server is started with `ANCHOR_API_TOKEN` set, every request
needs `Authorization: Bearer <token>`. Pass it once when constructing
the client:

```python
client = AnchorClient(api_token=os.environ["ANCHOR_API_TOKEN"])
```

The token rides every HTTP call **and** the SSE subscription used by
`AskHandle.stream_events()`.

## API surface

| Method | What it hits |
|---|---|
| `client.list_documents()` | `GET /documents` |
| `client.use(document_id=...)` / `use(title_substring=...)` | client-side; `use(title_substring=...)` does one resolution `GET` |
| `client.search_documents(query, k)` | `GET /documents/search` |
| `client.ingest(path)` | `POST /ingest` |
| `client.ingest_upload(path)` | `POST /ingest/upload` |
| `doc.describe()` | `GET /documents/{id}` |
| `doc.retrieve(query, k)` | `POST /retrieve` |
| `doc.validate(chunk_id, query)` | `POST /validate` |
| `doc.quick_validate(query)` | `POST /validate/quick` |
| `doc.ask(query)` → `AskHandle` | `POST /documents/{id}/ask` |
| `handle.snapshot()` / `status()` | `GET /jobs/{id}` |
| `handle.await_completion(timeout)` | polls `GET /jobs/{id}` every 250ms |
| `handle.stream_events()` | `GET /jobs/{id}/stream` (SSE) |
| `handle.cancel()` | `DELETE /jobs/{id}` |

`AnchorClientError` wraps any non-2xx response and any title-resolution
miss (zero or many matches in `use(title_substring=...)`).

## License

Apache 2.0 — same as Anchor.
