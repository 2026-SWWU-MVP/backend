"""Send fixed maintenance operations through the existing deployment role.

The secret travels in an RSA-OAEP encrypted payload. It is never included in
SSM command history, command-line arguments, artifacts, or action output.
"""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

instance = os.environ["INSTANCE_ID"]
remote_source = Path(__file__).with_name("server_ai_remote.py").read_text()
secret = os.environ.get("TESTFIT_LLM_API_KEY", "")


def aws(*arguments):
    result = subprocess.run(["aws", *arguments, "--output", "json"], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("AWS command failed: " + arguments[0] + "/" + arguments[1])
    return json.loads(result.stdout)


def remote(request, timeout=120):
    encoded = base64.b64encode(json.dumps(request).encode()).decode()
    command = f"python3 - '{encoded}' <<'TESTFIT_PY'\n{remote_source}\nTESTFIT_PY"
    if secret and secret in command:
        raise RuntimeError("Refusing to send a plaintext secret")
    response = aws("ssm", "send-command", "--instance-ids", instance,
                   "--document-name", "AWS-RunShellScript", "--parameters",
                   json.dumps({"commands": [command], "executionTimeout": [str(timeout)]}))
    command_id = response["Command"]["CommandId"]
    deadline = time.monotonic() + timeout + 30
    while time.monotonic() < deadline:
        time.sleep(2)
        result = subprocess.run(["aws", "ssm", "get-command-invocation", "--command-id", command_id,
                                 "--instance-id", instance, "--output", "json"], capture_output=True, text=True)
        if result.returncode:
            if "InvocationDoesNotExist" in result.stderr:
                continue
            raise RuntimeError("Cannot read SSM invocation")
        result = json.loads(result.stdout)
        if result["Status"] in ("Pending", "InProgress", "Delayed"):
            continue
        if result["Status"] != "Success":
            # The fixed remote helper reports only safe failure messages.
            print(result.get("StandardOutputContent", ""))
            print(result.get("StandardErrorContent", ""))
            raise RuntimeError("Remote operation failed: " + result["Status"])
        return json.loads(result["StandardOutputContent"])
    raise RuntimeError("Timed out waiting for remote operation")


operation = sys.argv[1]
if operation == "inspect":
    print(json.dumps(remote({"operation": "inspect"}), indent=2))
elif operation == "apply":
    model = os.environ["TESTFIT_LLM_MODEL"]
    if not secret or not re.fullmatch(r"sk-[A-Za-z0-9_-]+", secret):
        raise RuntimeError("Approved project key is missing or invalid")
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", model):
        raise RuntimeError("Invalid model identifier")
    prepared = remote({"operation": "prepare"})
    payload = json.dumps({"api_key": secret, "model": model}, separators=(",", ":")).encode()
    if len(payload) > 446:
        raise RuntimeError("Encrypted configuration payload is too large")
    with tempfile.TemporaryDirectory() as directory:
        public_key = Path(directory) / "public.pem"
        public_key.write_bytes(base64.b64decode(prepared["public_key"]))
        encrypted = subprocess.run(["openssl", "pkeyutl", "-encrypt", "-pubin", "-inkey", str(public_key),
                                    "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256"],
                                   input=payload, capture_output=True, check=True).stdout
    result = remote({"operation": "apply", "key_directory": prepared["key_directory"],
                     "deploy_sha": prepared["deploy_sha"], "config_sha": prepared["config_sha"],
                     "ciphertext": base64.b64encode(encrypted).decode()}, timeout=900)
    print(json.dumps(result, indent=2))
else:
    raise RuntimeError("Unsupported operation")
