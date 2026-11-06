"""Write the OpenAPI schema to a file (used to generate the typed frontend/mobile client).

Usage: python -m app.scripts.export_openapi ../frontend/openapi.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from app.core.config import Settings
from app.main import create_app


def main() -> None:
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("openapi.json")
    app = create_app(Settings(environment="development"))
    target.write_text(json.dumps(app.openapi(), indent=2) + "\n")
    print(f"Wrote {target}")


if __name__ == "__main__":
    main()
