"""
Vanilla RAG baseline for comparison against Anchor.

Deliberately the obvious-tutorial pipeline: PyPDF -> RecursiveCharacterTextSplitter
(LangChain defaults) -> Chroma -> top-k=4 -> stuff prompt -> LLM. ~80 lines of
glue, no clever ideas. This is the foil Anchor is competing against, not a
strawman: it is what a competent engineer ships in an afternoon following the
LangChain quickstart.

Same embedding model, same chat model, same paper as Anchor — point both at
the same LM Studio. The only thing that differs is the retrieval+grounding
logic.

Usage:
    pip install -r requirements.txt
    export LLM_BASE_URL=http://mac-studio.local:1234/v1
    python baseline.py ingest path/to/wagner.pdf
    python baseline.py ask "what is the maximum diversity of an intersecting set system?"
    python baseline.py compare queries.txt
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

from langchain_chroma import Chroma
from langchain_community.document_loaders import PyPDFLoader
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter

# --- config: matches Anchor's defaults so the comparison is apples-to-apples ---
LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "http://localhost:1234/v1")
LLM_API_KEY = os.environ.get("LM_STUDIO_API_KEY", "")  # required; current LM Studio rejects placeholder strings as malformed
CHAT_MODEL = os.environ.get("LLM_CHAT_MODEL", "gemma-3-4b-it")
EMBEDDING_MODEL = os.environ.get("LLM_EMBEDDING_MODEL", "nomic-embed-text-v1.5")
PERSIST_DIR = os.environ.get("BASELINE_DB_DIR", "./baseline-chroma")
COLLECTION = "baseline"

# LangChain quickstart defaults — explicitly the unsophisticated choice.
CHUNK_SIZE = 1000
CHUNK_OVERLAP = 200
TOP_K = 4

RAG_PROMPT = """You are a helpful assistant. Use only the following context to \
answer the question. If the context does not contain the answer, say so.

Context:
{context}

Question: {question}

Answer:"""


def _embeddings() -> OpenAIEmbeddings:
    return OpenAIEmbeddings(
        model=EMBEDDING_MODEL,
        base_url=LLM_BASE_URL,
        api_key=LLM_API_KEY,
        check_embedding_ctx_length=False,  # LM Studio doesn't expose the tokenizer
    )


def _llm() -> ChatOpenAI:
    return ChatOpenAI(
        model=CHAT_MODEL,
        base_url=LLM_BASE_URL,
        api_key=LLM_API_KEY,
        temperature=0.0,  # determinism for the comparison
    )


def _store() -> Chroma:
    return Chroma(
        collection_name=COLLECTION,
        embedding_function=_embeddings(),
        persist_directory=PERSIST_DIR,
    )


def cmd_ingest(pdf_path: str) -> None:
    path = Path(pdf_path).expanduser().resolve()
    if not path.exists():
        sys.exit(f"file not found: {path}")

    print(f"loading {path.name}")
    pages = PyPDFLoader(str(path)).load()
    print(f"  {len(pages)} pages")

    splitter = RecursiveCharacterTextSplitter(
        chunk_size=CHUNK_SIZE,
        chunk_overlap=CHUNK_OVERLAP,
    )
    chunks = splitter.split_documents(pages)
    print(f"  {len(chunks)} chunks (size={CHUNK_SIZE}, overlap={CHUNK_OVERLAP})")

    # Tag chunks with the source filename so multiple papers can coexist.
    for c in chunks:
        c.metadata["source_file"] = path.name

    print("embedding + writing to chroma")
    t0 = time.time()
    store = _store()
    store.add_documents(chunks)
    print(f"  done in {time.time() - t0:.1f}s -> {PERSIST_DIR}")


def cmd_ask(question: str, *, verbose: bool = True) -> dict:
    store = _store()
    t0 = time.time()
    docs = store.similarity_search(question, k=TOP_K)
    retrieval_ms = int((time.time() - t0) * 1000)

    context = "\n\n---\n\n".join(
        f"[chunk {i + 1} | p.{d.metadata.get('page', '?')}]\n{d.page_content}"
        for i, d in enumerate(docs)
    )
    prompt = RAG_PROMPT.format(context=context, question=question)

    t0 = time.time()
    answer = _llm().invoke(prompt).content
    llm_ms = int((time.time() - t0) * 1000)

    result = {
        "question": question,
        "answer": answer,
        "retrieved_chunks": [
            {
                "rank": i + 1,
                "page": d.metadata.get("page"),
                "source": d.metadata.get("source_file"),
                "preview": d.page_content[:240].replace("\n", " "),
            }
            for i, d in enumerate(docs)
        ],
        "timings_ms": {"retrieval": retrieval_ms, "llm": llm_ms},
    }

    if verbose:
        print(f"\nQ: {question}\n")
        print(f"A: {answer}\n")
        print(f"--- retrieved (top-{TOP_K}, {retrieval_ms}ms) ---")
        for c in result["retrieved_chunks"]:
            print(f"  #{c['rank']} p.{c['page']}: {c['preview']}...")
        print(f"\nllm: {llm_ms}ms")

    return result


def cmd_compare(queries_file: str) -> None:
    """Run a list of queries and dump JSONL — pair this with the same queries
    against Anchor's /validate endpoint to produce the side-by-side table."""
    queries = [
        line.strip()
        for line in Path(queries_file).read_text().splitlines()
        if line.strip() and not line.startswith("#")
    ]
    out_path = Path("baseline-results.jsonl")
    with out_path.open("w") as f:
        for q in queries:
            print(f"\n=== {q} ===")
            result = cmd_ask(q, verbose=False)
            f.write(json.dumps(result) + "\n")
            print(f"A: {result['answer'][:200]}...")
    print(f"\nwrote {len(queries)} results to {out_path}")


def main() -> None:
    p = argparse.ArgumentParser(description="Vanilla RAG baseline.")
    sub = p.add_subparsers(dest="cmd", required=True)

    p_ingest = sub.add_parser("ingest", help="Ingest a PDF.")
    p_ingest.add_argument("pdf")

    p_ask = sub.add_parser("ask", help="Ask one question.")
    p_ask.add_argument("question")

    p_compare = sub.add_parser("compare", help="Run a query file -> JSONL.")
    p_compare.add_argument("queries_file")

    args = p.parse_args()
    if args.cmd == "ingest":
        cmd_ingest(args.pdf)
    elif args.cmd == "ask":
        cmd_ask(args.question)
    elif args.cmd == "compare":
        cmd_compare(args.queries_file)


if __name__ == "__main__":
    main()
