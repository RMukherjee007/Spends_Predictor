"""Generate a new Fernet key for FIELD_ENCRYPTION_KEYS.

Usage: python -m app.scripts.generate_key
"""

from app.core.crypto import generate_key

if __name__ == "__main__":
    print(generate_key())
