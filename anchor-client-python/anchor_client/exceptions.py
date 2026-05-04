class AnchorClientError(RuntimeError):
    """Raised on transport failures, non-2xx responses, and resolution errors
    (zero / many matches in :py:meth:`AnchorClient.use`).
    """
