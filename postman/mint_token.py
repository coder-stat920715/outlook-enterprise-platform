#!/usr/bin/env python3
"""Mint a mock-IdP token for curl / websocat testing.
   python3 mint_token.py --mailbox <mailboxId> [--tenant 0000...0001] [--region AMER] [--sub alice]"""
import argparse, base64, json, time
ap = argparse.ArgumentParser()
ap.add_argument("--mailbox", required=True); ap.add_argument("--tenant", default="00000000-0000-0000-0000-000000000001")
ap.add_argument("--region", default="AMER"); ap.add_argument("--sub", default="tester"); ap.add_argument("--ttl", type=int, default=3600)
a = ap.parse_args()
b = lambda d: base64.urlsafe_b64encode(json.dumps(d).encode()).rstrip(b"=").decode()
print(f"{b({'alg':'none','typ':'JWT'})}.{b({'sub':a.sub,'tid':a.tenant,'mid':a.mailbox,'region':a.region,'exp':int(time.time())+a.ttl})}.sig")
