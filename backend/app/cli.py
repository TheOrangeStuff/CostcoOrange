"""Admin commands, run on the server:

    uv run python -m app.cli adduser <username>     # prompts for a password
    uv run python -m app.cli users
    uv run python -m app.cli sync                    # run a sync now, in the foreground
    uv run python -m app.cli renormalize             # rebuild item rows from stored raw data
"""

import getpass
import json
import logging
import sys

from app import auth, db, sync


def main(argv: list[str]) -> int:
    db.init()
    if len(argv) >= 2 and argv[0] == "adduser":
        password = getpass.getpass(f"Password for {argv[1]}: ")
        if len(password) < 8:
            print("Use at least 8 characters.", file=sys.stderr)
            return 1
        if getpass.getpass("Again: ") != password:
            print("Passwords don't match.", file=sys.stderr)
            return 1
        auth.create_user(argv[1], password)
        print(f"Saved user {argv[1]}")
    elif argv[:1] == ["users"]:
        with db.session() as conn:
            for r in conn.execute("SELECT username, created_at FROM users ORDER BY id"):
                print(r["username"], r["created_at"])
    elif argv[:1] == ["sync"]:
        logging.basicConfig(level=logging.INFO)
        print(json.dumps(sync.sync(), indent=2))
    elif argv[:1] == ["renormalize"]:
        sync.renormalize_all()
        print("Done")
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
