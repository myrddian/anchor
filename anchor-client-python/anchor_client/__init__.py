"""Python SDK for Anchor.

Mirrors the Java client surface (anchor-client) — same endpoints, same
"select a document, then query it" UX. Returns plain dicts (parsed JSON);
keys match the wire format snake_case.

    from anchor_client import AnchorClient
    client = AnchorClient(base_url="http://localhost:8090", api_token="...")
    doc = client.use(title_substring="entropy")
    handle = doc.ask("Does the second law forbid local order?")
    result = handle.await_completion(timeout=120)
    print(result["final_response"])
"""

from .client import AnchorClient, AnchorDocument, AskHandle
from .exceptions import AnchorClientError

__all__ = ["AnchorClient", "AnchorDocument", "AskHandle", "AnchorClientError"]
__version__ = "0.2.0"
