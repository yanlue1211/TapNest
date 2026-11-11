#!/usr/bin/env python3
"""Derive a reproducible Android signing identity from ONE private, random secret.

The seed exists only in a GitHub Actions encrypted repository secret. Never
include it in source, tests, artifacts, logs or a public workflow input.
The same seed generates byte-for-byte identical public cert + private key
across all runs; PKCS12 container randomness is inconsequential.
"""
import argparse
import base64
import os
from datetime import datetime, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

# NIST P-256 group order (not the field prime).
P256_ORDER = int("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)


def expand(seed: bytes, purpose: bytes, nbytes: int) -> bytes:
    return HKDF(
        algorithm=hashes.SHA256(), length=nbytes,
        salt=b"TapNest persistent signing, v1 / 2026",
        info=b"tapnest/" + purpose,
    ).derive(seed)


def make_identity(seed: bytes):
    scalar = int.from_bytes(expand(seed, b"p256-private-key", 48), "big") % (P256_ORDER - 1) + 1
    private_key = ec.derive_private_key(scalar, ec.SECP256R1())
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "TapNest Android Update Key")])
    serial = (int.from_bytes(expand(seed, b"certificate-serial", 20), "big") >> 1) or 1
    cert = (
        x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
        .public_key(private_key.public_key()).serial_number(serial)
        .not_valid_before(datetime(2025, 1, 1, tzinfo=timezone.utc))
        .not_valid_after(datetime(2055, 1, 1, tzinfo=timezone.utc))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(private_key, hashes.SHA256(), ecdsa_deterministic=True)
    )
    passphrase = base64.urlsafe_b64encode(expand(seed, b"pkcs12-password", 32)).rstrip(b"=")
    return private_key, cert, passphrase


def self_test():
    # Public test seed is NOT an app signing key, never use it for production!
    seed = b"TEST-ONLY-NEVER-DEPLOY-" + (b"PUBLIC, reproducible test seed! " * 3)
    key_a, cert_a, pass_a = make_identity(seed)
    key_b, cert_b, pass_b = make_identity(seed)
    assert key_a.private_numbers() == key_b.private_numbers()
    der = cert_a.public_bytes(serialization.Encoding.DER)
    assert der == cert_b.public_bytes(serialization.Encoding.DER)
    cert_a.public_key().verify(
        cert_a.signature, cert_a.tbs_certificate_bytes,
        ec.ECDSA(cert_a.signature_hash_algorithm))
    assert pass_a == pass_b
    data = pkcs12.serialize_key_and_certificates(
        name=b"tapnest", key=key_a, cert=cert_a, cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(pass_a))
    restored_key, restored_cert, _ = pkcs12.load_key_and_certificates(data, pass_a)
    assert restored_key.private_numbers() == key_a.private_numbers()
    assert restored_cert.public_bytes(serialization.Encoding.DER) == der
    assert make_identity(seed + b"different")[1].public_bytes(serialization.Encoding.DER) != der
    print("OK: stable identity, stable certificate, PKCS12 roundtrip, unique seed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path)
    parser.add_argument("--password-file", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.out or not args.password_file:
        parser.error("--out and --password-file are required")
    raw = os.getenv("TAPNEST_SIGNING_SEED", "")
    if len(raw) < 48 or len(set(raw)) < 12:
        raise SystemExit(
            "Use a fresh 48+ character RANDOM password (12+ different characters), "
            "not a memorable human password.")
    key, cert, password = make_identity(raw.encode("utf-8"))
    data = pkcs12.serialize_key_and_certificates(
        name=b"tapnest", key=key, cert=cert, cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(password))
    for filename, payload in ((args.out, data), (args.password_file, password)):
        with os.fdopen(os.open(filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as handle:
            handle.write(payload)
    # Public fingerprint is safe to disclose and can be checked on later builds.
    print("Android signing certificate SHA256: " + cert.fingerprint(hashes.SHA256()).hex().upper())


if __name__ == "__main__":
    main()
