"""Fixed, narrowly scoped EC2 operations; executed as root through SSM."""
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request

DEPLOY = Path("/usr/local/bin/deploy-testfit.sh")
CONFIG = Path("/etc/testfit-ai/application.properties")
DESTINATION = "/app/config/application.properties"
request = json.loads(base64.b64decode(sys.argv[1]))


def run(arguments, **options):
    return subprocess.run(arguments, capture_output=True, check=True, **options).stdout


def container():
    rows = run(["docker", "ps", "--format", "{{.ID}} {{.Image}}"], text=True).splitlines()
    matches = [row.split()[0] for row in rows if re.match(r"^\S+ ghcr\.io/2026-swwu-mvp/backend(?::|@)", row)]
    if len(matches) != 1:
        raise RuntimeError("Expected exactly one running backend container")
    return json.loads(run(["docker", "inspect", matches[0]], text=True))[0]


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest() if file.exists() else None


def preflight():
    if not DEPLOY.is_file() or DEPLOY.is_symlink():
        raise RuntimeError("Expected a regular deployment script")
    current = container()
    source = DEPLOY.read_text()
    if len(re.findall(r"\bdocker\s+run\b", source)) != 1:
        raise RuntimeError("Deployment script requires review: expected one docker run command")
    for mount in current.get("Mounts", []):
        destination = mount["Destination"].rstrip("/")
        if (destination == "/app/config" or destination == DESTINATION) and mount.get("Source") != str(CONFIG):
            raise RuntimeError("Existing external application configuration must be reviewed first")
    return current, source


def safe_configuration(current):
    environment = dict(item.split("=", 1) for item in current["Config"]["Env"] if "=" in item)
    result = {"environment_provider": environment.get("LLM_PROVIDER", "unset (default mock)"),
              "environment_model": environment.get("LLM_MODEL", "unset"),
              "environment_key_present": bool(environment.get("LLM_API_KEY")),
              "managed_ai_config_present": CONFIG.is_file()}
    if CONFIG.is_file():
        properties = dict(line.split("=", 1) for line in CONFIG.read_text().splitlines() if "=" in line and not line.startswith("#"))
        result.update({"managed_provider": properties.get("app.llm.provider"),
                       "managed_model": properties.get("app.llm.model"),
                       "managed_key_present": bool(properties.get("app.llm.api-key"))})
    return result


def atomic_write(file, content, mode, uid=0, gid=0):
    descriptor, temporary = tempfile.mkstemp(prefix=".testfit-ai-", dir=str(file.parent))
    try:
        os.fchmod(descriptor, mode)
        os.fchown(descriptor, uid, gid)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
        os.replace(temporary, file)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


operation = request["operation"]
if operation == "inspect":
    current, source = preflight()
    result = safe_configuration(current)
    result.update({"single_docker_run": True, "deploy_script_sha": digest(DEPLOY),
                   "ai_mount_installed": str(CONFIG) in source})
    print(json.dumps(result))
elif operation == "prepare":
    current, source = preflight()
    directory = Path(tempfile.mkdtemp(prefix="testfit-ai-key-", dir="/run"))
    os.chmod(directory, 0o700)
    run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:4096", "-out", str(directory / "private.pem")])
    os.chmod(directory / "private.pem", 0o600)
    public = run(["openssl", "pkey", "-in", str(directory / "private.pem"), "-pubout"])
    print(json.dumps({"key_directory": str(directory), "public_key": base64.b64encode(public).decode(),
                      "deploy_sha": digest(DEPLOY), "config_sha": digest(CONFIG)}))
elif operation == "apply":
    directory = Path(request["key_directory"])
    if directory.parent != Path("/run") or not re.fullmatch(r"testfit-ai-key-[A-Za-z0-9_-]+", directory.name) or directory.is_symlink():
        raise RuntimeError("Invalid temporary encryption directory")
    try:
        private = directory / "private.pem"
        payload = run(["openssl", "pkeyutl", "-decrypt", "-inkey", str(private),
                       "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256"],
                      input=base64.b64decode(request["ciphertext"]))
        settings = json.loads(payload)
        if not re.fullmatch(r"sk-[A-Za-z0-9_-]+", settings["api_key"]) or not re.fullmatch(r"[A-Za-z0-9._:-]+", settings["model"]):
            raise RuntimeError("Invalid approved AI configuration")
        current, source = preflight()
        if digest(DEPLOY) != request["deploy_sha"] or digest(CONFIG) != request["config_sha"]:
            raise RuntimeError("Server configuration changed during preparation; no updates made")
        original_deploy = DEPLOY.read_bytes()
        original_stat = DEPLOY.stat()
        original_config = CONFIG.read_bytes() if CONFIG.exists() else None
        original_config_stat = CONFIG.stat() if CONFIG.exists() else None
        group_id = int(run(["docker", "exec", current["Id"], "id", "-g"], text=True).strip())
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        backup = Path("/var/backups/testfit-ai") / stamp
        backup.mkdir(parents=True, mode=0o700)
        os.chmod(backup.parent, 0o700)
        os.chmod(backup, 0o700)
        atomic_write(backup / "deploy-testfit.sh", original_deploy, 0o600)
        if original_config is not None:
            atomic_write(backup / "application.properties", original_config, 0o600)
        CONFIG.parent.mkdir(mode=0o700, exist_ok=True)
        os.chmod(CONFIG.parent, 0o700)
        content = ("app.llm.provider=openai\napp.llm.model=" + settings["model"] +
                   "\napp.llm.api-key=" + settings["api_key"] + "\napp.llm.base-url=https://api.openai.com/v1\n").encode()
        if str(CONFIG) not in source:
            option = f'docker run --mount "type=bind,src={CONFIG},dst={DESTINATION},readonly"'
            updated = re.sub(r"\bdocker\s+run\b", lambda _: option, source, count=1).encode()
        else:
            updated = original_deploy
        try:
            atomic_write(CONFIG, content, 0o640, 0, group_id)
            atomic_write(DEPLOY, updated, stat.S_IMODE(original_stat.st_mode), original_stat.st_uid, original_stat.st_gid)
            deployment = subprocess.run([str(DEPLOY)], capture_output=True, timeout=300)
            if deployment.returncode:
                raise RuntimeError("Backend restart failed; captured output was not printed to protect credentials")
            healthy = False
            for attempt in range(18):
                try:
                    with urllib.request.urlopen("https://testfit.duckdns.org/v3/api-docs", timeout=8) as response:
                        healthy = response.status == 200
                    if healthy:
                        break
                except Exception:
                    pass
                time.sleep(3)
            if not healthy:
                raise RuntimeError("Backend health check failed")
            after = container()
            if not any(mount.get("Source") == str(CONFIG) and mount["Destination"] == DESTINATION for mount in after["Mounts"]):
                raise RuntimeError("AI configuration mount was not applied")
            print(json.dumps({"status": "applied", "backup_directory": str(backup), **safe_configuration(after)}))
        except Exception:
            atomic_write(DEPLOY, original_deploy, stat.S_IMODE(original_stat.st_mode), original_stat.st_uid, original_stat.st_gid)
            if original_config is None:
                CONFIG.unlink(missing_ok=True)
            else:
                atomic_write(CONFIG, original_config, stat.S_IMODE(original_config_stat.st_mode), original_config_stat.st_uid, original_config_stat.st_gid)
            rollback = subprocess.run([str(DEPLOY)], capture_output=True, timeout=300)
            print(json.dumps({"status": "rolled_back", "restart_succeeded": rollback.returncode == 0}))
            raise RuntimeError("AI configuration was not applied; previous configuration restored") from None
    finally:
        # Only remove the one private key created for this approved operation.
        (directory / "private.pem").unlink(missing_ok=True)
        directory.rmdir()
else:
    raise RuntimeError("Unsupported operation")
