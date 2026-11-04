"""MorpheSig1, the patch bundle signature Morphe checks before it loads a bundle.

morphe-patcher 2.1 (PatchBundleSignature) reads it from the zip comment of the .mpp as
`MorpheSig1 <public key> <signature>`, both Base64. The key is a raw Ed25519 key, and the signature
covers the bytes of `MorpheSig1` followed by the SHA-256 of the archive as it was before signing:
the whole file with an empty comment. Morphe Manager 2.3 pins the first key a source signs with and
refuses a later bundle signed with any other key, or with none.

The build signs in patches/build.gradle.kts with the JDK when MORPHE_SIGNING_KEY is set and
morphe-signing.pub exists. This module checks a built bundle and makes the key pair once, with
Ed25519 written out from RFC 8032 so nothing outside the standard library is needed.
"""

import argparse
import base64
import binascii
import hashlib
import os
import sys
from pathlib import Path

MAGIC = b"MorpheSig1"
EOCD_SIGNATURE = b"PK\x05\x06"
EOCD_SIZE = 22
MAX_COMMENT_SIZE = 0xFFFF

# Ed25519 as RFC 8032 section 6 writes it.
P = 2**255 - 19
Q = 2**252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, -1, P) % P
SQRT_M1 = pow(2, (P - 1) // 4, P)


def _point_add(a, b):
    first = (a[1] - a[0]) * (b[1] - b[0]) % P
    second = (a[1] + a[0]) * (b[1] + b[0]) % P
    third = 2 * a[3] * b[3] * D % P
    fourth = 2 * a[2] * b[2] % P
    e, f, g, h = second - first, fourth - third, fourth + third, second + first
    return (e * f, g * h, f * g, e * h)


def _point_mul(scalar, point):
    result = (0, 1, 1, 0)
    while scalar > 0:
        if scalar & 1:
            result = _point_add(result, point)
        point = _point_add(point, point)
        scalar >>= 1
    return result


def _point_equal(a, b):
    return (a[0] * b[2] - b[0] * a[2]) % P == 0 and (a[1] * b[2] - b[1] * a[2]) % P == 0


def _recover_x(y, sign):
    if y >= P:
        return None
    x2 = (y * y - 1) * pow(D * y * y + 1, -1, P)
    if x2 % P == 0:
        return None if sign else 0
    x = pow(x2, (P + 3) // 8, P)
    if (x * x - x2) % P:
        x = x * SQRT_M1 % P
    if (x * x - x2) % P:
        return None
    return P - x if (x & 1) != sign else x


_BASE_X = 15112221349535400772501151409588531511454012693041857206046113283949847762202
_BASE_Y = 46316835694926478169428394003475163141307993866256225615783033603165251855960
BASE = (_BASE_X, _BASE_Y, 1, _BASE_X * _BASE_Y % P)
IDENTITY = (0, 1, 1, 0)


def _compress(point):
    inverse = pow(point[2], -1, P)
    x, y = point[0] * inverse % P, point[1] * inverse % P
    return (y | ((x & 1) << 255)).to_bytes(32, "little")


def _decompress(data):
    y = int.from_bytes(data, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    x = _recover_x(y, sign)
    return None if x is None else (x, y, 1, x * y % P)


def _hash_mod_q(data):
    return int.from_bytes(hashlib.sha512(data).digest(), "little") % Q


def _expand(seed):
    digest = hashlib.sha512(seed).digest()
    scalar = int.from_bytes(digest[:32], "little")
    scalar &= (1 << 254) - 8
    scalar |= 1 << 254
    return scalar, digest[32:]


def public_key(seed):
    """The raw Ed25519 public key of a 32-byte private key seed."""
    if len(seed) != 32:
        raise ValueError("An Ed25519 private key is 32 bytes")
    return _compress(_point_mul(_expand(seed)[0], BASE))


def ed25519_sign(seed, message):
    scalar, prefix = _expand(seed)
    public = public_key(seed)
    nonce = _hash_mod_q(prefix + message)
    commitment = _compress(_point_mul(nonce, BASE))
    challenge = _hash_mod_q(commitment + public + message)
    return commitment + ((nonce + challenge * scalar) % Q).to_bytes(32, "little")


def ed25519_verify(public, message, signature):
    if len(public) != 32 or len(signature) != 64:
        return False
    key = _decompress(public)
    commitment = _decompress(signature[:32])
    proof = int.from_bytes(signature[32:], "little")
    if key is None or commitment is None or proof >= Q:
        return False
    # A key of small order verifies a zero signature on anything. BouncyCastle, which the patcher
    # checks with, refuses such a key as malformed, so this does too.
    if _point_equal(_point_mul(8, key), IDENTITY):
        return False
    challenge = _hash_mod_q(signature[:32] + public + message)
    return _point_equal(
        _point_mul(proof, BASE), _point_add(commitment, _point_mul(challenge, key))
    )


def comment_offset(data):
    """Where the zip comment of data starts, or None when data is no zip archive."""
    length = len(data)
    if length < EOCD_SIZE:
        return None
    lowest = max(0, length - EOCD_SIZE - MAX_COMMENT_SIZE)
    # The end of central directory record is the last one whose comment reaches the end.
    for start in range(length - EOCD_SIZE, lowest - 1, -1):
        if (
            data[start : start + 4] == EOCD_SIGNATURE
            and int.from_bytes(data[start + 20 : start + 22], "little")
            == length - start - EOCD_SIZE
        ):
            return start + EOCD_SIZE
    return None


def _message(data, offset):
    # The archive up to its comment, with the comment length zeroed as it was before signing.
    return MAGIC + hashlib.sha256(data[: offset - 2] + b"\0\0").digest()


def _decode(text):
    # Java's decoder, which the patcher reads the comment with, takes Base64 with its = padding left
    # off, but not with the wrong amount of it.
    if isinstance(text, str):
        text = text.encode("ascii", "replace")
    padding = b"=" * (-len(text) % 4)
    if text.endswith(b"="):
        padding = b""
    try:
        return base64.b64decode(text + padding, validate=True)
    except (binascii.Error, ValueError):
        return None


def read_key(text):
    """The 32-byte seed in a Base64 private key, as MORPHE_SIGNING_KEY and the key file hold it."""
    seed = _decode(text.strip())
    if seed is None or len(seed) != 32:
        raise ValueError("The bundle signing key isn't a Base64 32-byte Ed25519 key")
    return seed


def sign(data, seed):
    """data signed with seed. Refuses an archive that already has a comment, like the patcher."""
    if comment_offset(data) != len(data):
        raise ValueError("The bundle is already signed or isn't a zip archive")
    public = base64.b64encode(public_key(seed)).decode("ascii")
    signature = base64.b64encode(ed25519_sign(seed, _message(data, len(data)))).decode(
        "ascii"
    )
    comment = f"{MAGIC.decode('ascii')} {public} {signature}".encode("ascii")
    return data[:-2] + len(comment).to_bytes(2, "little") + comment


def verify(data):
    """The Base64 key data is signed with, or None when it isn't signed.

    Raises ValueError for a signature that is malformed or doesn't match the content, where the
    patcher throws SecurityException and Manager refuses the bundle.
    """
    offset = comment_offset(data)
    if offset is None:
        return None
    comment = data[offset:].decode("ascii", "replace")
    if not comment.startswith(MAGIC.decode("ascii") + " "):
        return None
    parts = comment.split(" ")
    public = _decode(parts[1]) if len(parts) == 3 else None
    signature = _decode(parts[2]) if len(parts) == 3 else None
    if public is None or len(public) != 32 or signature is None:
        raise ValueError("The bundle signature is malformed")
    if not ed25519_verify(public, _message(data, offset), signature):
        raise ValueError("The bundle signature doesn't match its content")
    # The key as morphe-signing.pub writes it, whatever padding the comment carried.
    return base64.b64encode(public).decode("ascii")


def fingerprint(public):
    """The short form Morphe shows for a key: the first 8 bytes of its SHA-256 in groups of four."""
    digest = hashlib.sha256(base64.b64decode(public)).hexdigest()[:16]
    return " ".join(digest[i : i + 4] for i in range(0, 16, 4))


def read_public(path):
    """The Base64 public key a morphe-signing.pub file holds."""
    text = path.read_text(encoding="ascii").strip()
    key = _decode(text)
    if key is None or len(key) != 32:
        raise ValueError(f"{path.name} isn't a Base64 32-byte Ed25519 public key")
    return text


def generate(key_path, public_path):
    if key_path.exists():
        raise ValueError(
            f"{key_path} already exists. A new key changes the pin every user's Manager holds"
        )
    seed = os.urandom(32)
    public = base64.b64encode(public_key(seed)).decode("ascii")
    key_path.parent.mkdir(parents=True, exist_ok=True)
    key_path.write_text(base64.b64encode(seed).decode("ascii") + "\n", encoding="ascii")
    public_path.write_text(public + "\n", encoding="ascii")
    return public


def main(argv=None):
    parser = argparse.ArgumentParser(description="Make or check a patch bundle's MorpheSig1 signature.")
    commands = parser.add_subparsers(dest="command", required=True)
    make = commands.add_parser("generate", help="make the key pair once, off the repository")
    make.add_argument("--key", type=Path, required=True)
    make.add_argument("--public", type=Path, default=Path("morphe-signing.pub"))
    check = commands.add_parser("verify", help="check a bundle's signature")
    check.add_argument("bundle", type=Path)
    check.add_argument("--public", type=Path, help="also require this key")
    args = parser.parse_args(argv)
    try:
        if args.command == "generate":
            public = generate(args.key, args.public)
            print(f"Wrote {args.public}, fingerprint {fingerprint(public)}")
            return 0
        signer = verify(args.bundle.read_bytes())
        if signer is None:
            raise ValueError(f"{args.bundle.name} isn't signed")
        if args.public is not None and signer != read_public(args.public):
            raise ValueError(f"{args.bundle.name} is signed with another key")
        print(f"{args.bundle.name} is signed by {fingerprint(signer)}")
        return 0
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
