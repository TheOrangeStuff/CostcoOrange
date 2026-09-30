"""Keep server warnings and errors in the database, next to the app's own error reports, so
they can be read over the API without shell access to the server."""

import logging

from app import db


class DbLogHandler(logging.Handler):
    def __init__(self) -> None:
        super().__init__(level=logging.WARNING)
        self.setFormatter(logging.Formatter("%(name)s: %(message)s"))

    def emit(self, record: logging.LogRecord) -> None:
        try:
            with db.session() as conn:
                db.add_log(conn, "server", record.levelname.lower(), self.format(record))
        except Exception:  # noqa: BLE001 - logging must never take the server down
            self.handleError(record)


def install() -> None:
    root = logging.getLogger()
    if not any(isinstance(h, DbLogHandler) for h in root.handlers):
        root.addHandler(DbLogHandler())
