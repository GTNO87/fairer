#!/usr/bin/env python3
"""
sign_blocklist.py — Sign the blocklist with an Ed25519 private key.

The signature lets the Android app verify the blocklist has not been tampered
with. The app holds the public key; only the holder of the private key can
produce a valid signature.

Usage:
    BLOCKLIST_SIGNING_KEY=<base64> python scripts/sign_blocklist.py

The BLOCKLIST_SIGNING_KEY environment variable must be a Base64-encoded
string representing the 32-byte Ed25519 private-key seed.

Generating a new keypair:
    python scripts/sign_blocklist.py --generate-key

Output:
    app/src/main/assets/blocklists/manipulation-blocklist.txt.sig

    The .sig file contains two lines:
        algorithm: ed25519
        signature: <base64-encoded 64-byte signature>

    Commit both the blocklist and its .sig file together.

Requires:
    pip install cryptography
"""

import argparse
import base64
import os
import pathlib
import secrets
import sys

# ── Paths ─────────────────────────────────────────────────────────────────────

SCRIPT_DIR     = pathlib.Path(__file__).parent.resolve()
REPO_ROOT      = SCRIPT_DIR.parent
BLOCKLIST_FILE = REPO_ROOT / "app/src/main/assets/blocklists/manipulation-blocklist.txt"
SIG_FILE       = BLOCKLIST_FILE.with_suffix(".txt.sig")

ENV_KEY_NAME   = "BLOCKLIST_SIGNING_KEY"

# ── Crypto helpers ────────────────────────────────────────────────────────────

def _require_cryptography() -> None:
    try:
        import cryptography  # noqa: F401
    except ImportError:
        sys.exit(
            "Error: the 'cryptography' package is required.\n"
            "Install with: pip install cryptography"
        )


def generate_keypair() -> tuple[bytes, bytes]:
    """Return (private_key_seed_bytes, public_key_bytes) for a new Ed25519 key."""
    _require_cryptography()
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, PrivateFormat, NoEncryption

    private_key = Ed25519PrivateKey.generate()
    seed = private_key.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    public = private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return seed, public


def sign(data: bytes, seed: bytes) -> bytes:
    """Return the 64-byte Ed25519 signature of `data`."""
    _require_cryptography()
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, NoEncryption

    private_key = Ed25519PrivateKey.from_private_bytes(seed)
    return private_key.sign(data)


def public_key_from_seed(seed: bytes) -> bytes:
    """Derive and return the 32-byte public key for a given seed."""
    _require_cryptography()
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

    private_key = Ed25519PrivateKey.from_private_bytes(seed)
    return private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def verify(data: bytes, signature: bytes, public_key_bytes: bytes) -> bool:
    """Return True if the signature is valid for data under public_key_bytes."""
    _require_cryptography()
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    from cryptography.exceptions import InvalidSignature

    try:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
        public_key = Ed25519PublicKey.from_public_bytes(public_key_bytes)
        public_key.verify(signature, data)
        return True
    except InvalidSignature:
        return False

# ── Key loading ───────────────────────────────────────────────────────────────

def load_seed_from_env() -> bytes:
    """
    Read and validate BLOCKLIST_SIGNING_KEY from the environment.
    Expected format: Base64-encoded 32-byte Ed25519 private-key seed.
    """
    raw = os.environ.get(ENV_KEY_NAME, "").strip()

    if not raw:
        sys.exit(
            f"Error: {ENV_KEY_NAME} environment variable is not set.\n\n"
            "To generate a new keypair:\n"
            "    python scripts/sign_blocklist.py --generate-key\n\n"
            "Then set the variable before signing:\n"
            f"    export {ENV_KEY_NAME}=<your-base64-encoded-seed>"
        )

    try:
        seed = base64.b64decode(raw, validate=True)
    except Exception:
        sys.exit(
            f"Error: {ENV_KEY_NAME} could not be Base64-decoded.\n"
            "Expected a Base64-encoded 32-byte Ed25519 private-key seed.\n"
            "Generate one with: python scripts/sign_blocklist.py --generate-key"
        )

    if len(seed) != 32:
        sys.exit(
            f"Error: {ENV_KEY_NAME} decoded to {len(seed)} bytes; expected 32.\n"
            "Ed25519 private key seeds are exactly 32 bytes."
        )

    return seed


# ── .sig file format ──────────────────────────────────────────────────────────

def write_sig_file(path: pathlib.Path, signature: bytes) -> None:
    sig_b64 = base64.b64encode(signature).decode("ascii")
    path.write_text(
        f"algorithm: ed25519\n"
        f"signature: {sig_b64}\n",
        encoding="utf-8",
    )


def read_sig_file(path: pathlib.Path) -> bytes:
    lines = {
        k.strip(): v.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if ":" in line
        for k, v in [line.split(":", 1)]
    }
    if lines.get("algorithm") != "ed25519":
        sys.exit(f"Error: unsupported algorithm in {path}")
    return base64.b64decode(lines["signature"])

# ── Main ──────────────────────────────────────────────────────────────────────

def cmd_generate_key() -> None:
    seed, public = generate_keypair()
    public_hex = public.hex()
    public_b64 = base64.b64encode(public).decode("ascii")

    print("=" * 72)
    print("NEW Ed25519 KEYPAIR GENERATED")
    print("=" * 72)
    print()
    print("PRIVATE KEY SEED (keep secret — set as environment variable):")
    print(f"  export {ENV_KEY_NAME}={base64.b64encode(seed).decode()}")
    print()
    print("PUBLIC KEY (hardcode in the Android app for verification):")
    print(f"  Hex:    {public_hex}")
    print(f"  Base64: {public_b64}")
    print()
    print("Store the private key seed in your CI secrets or a password manager.")
    print("Never commit it to the repository.")
    print("=" * 72)


def cmd_sign() -> None:
    if not BLOCKLIST_FILE.exists():
        sys.exit(f"Error: blocklist not found at {BLOCKLIST_FILE}")

    seed = load_seed_from_env()
    public_key = public_key_from_seed(seed)

    blocklist_bytes = BLOCKLIST_FILE.read_bytes()
    signature = sign(blocklist_bytes, seed)

    # Self-verify before writing — catch key/data mismatch immediately.
    if not verify(blocklist_bytes, signature, public_key):
        sys.exit("Error: self-verification failed. The signature is invalid.")

    write_sig_file(SIG_FILE, signature)

    print(f"Signed   : {BLOCKLIST_FILE.relative_to(REPO_ROOT)}")
    print(f"Signature: {SIG_FILE.relative_to(REPO_ROOT)}")
    print(f"Algorithm: Ed25519")
    print(f"Public key (hex):    {public_key.hex()}")
    print(f"Public key (base64): {base64.b64encode(public_key).decode()}")
    print()
    print("Commit both files together.")
    print("Hardcode the public key in the Android app to verify on startup.")


def cmd_verify() -> None:
    if not BLOCKLIST_FILE.exists():
        sys.exit(f"Error: blocklist not found at {BLOCKLIST_FILE}")
    if not SIG_FILE.exists():
        sys.exit(f"Error: signature file not found at {SIG_FILE}")

    seed = load_seed_from_env()
    public_key = public_key_from_seed(seed)

    blocklist_bytes = BLOCKLIST_FILE.read_bytes()
    signature = read_sig_file(SIG_FILE)

    if verify(blocklist_bytes, signature, public_key):
        print("✓ Signature is valid.")
    else:
        sys.exit("✗ Signature is INVALID. The blocklist may have been tampered with.")


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )

    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "--generate-key",
        action="store_true",
        help="Generate a new Ed25519 keypair and print the seed and public key.",
    )
    group.add_argument(
        "--verify",
        action="store_true",
        help=f"Verify the existing .sig file against {ENV_KEY_NAME}.",
    )

    args = parser.parse_args()

    _require_cryptography()

    if args.generate_key:
        cmd_generate_key()
    elif args.verify:
        cmd_verify()
    else:
        cmd_sign()


if __name__ == "__main__":
    main()
