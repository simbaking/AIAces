"""
Ace's Card Game — TensorFlow Training Server
=============================================
Designed to run on a remote machine (cloud VM, spare PC, etc.).
Java game sends training samples via REST. This server trains continuously
with TF/Keras (GPU if available) and auto-pushes latest weights to the
'latest-ai' git branch so they're always version-controlled and accessible.

Architecture exactly mirrors NeuralNetwork.java:
  - Strategy Block:      9 × Dense(64, sigmoid),  input=38
  - Plan Pre Block:      5 × Dense(10, sigmoid),  input=64
  - Strategy Bottleneck: Dense(3, sigmoid),        input=68 (64 strategy + 4 aggro)
  - Plan Post Block:     5 × Dense(10, sigmoid),  input=10
    (layers 1-4 share weights with Plan Pre layers 1-4)
  - Execution Block:     9 × Dense(64, sigmoid),  input=109 (3+10+96)
  - Output:              Dense(58, sigmoid),       input=64

Endpoints:
  POST /train         — receives batches of (inputs, action, reward)
  GET  /weights       — returns all weights as JSON for Java to sync
  POST /load_weights  — accepts weights JSON from Java (bootstrap)
  GET  /status        — health check + training stats

Setup on remote machine:
  git clone <your-repo-url>
  git checkout latest-ai
  cd training_server
  pip install -r requirements.txt
  # Optional: set git identity for auto-commits
  git config user.email "ai-trainer@aces"
  git config user.name  "Aces Trainer"
  python server.py

Environment variables:
  PORT                 — server port (default 5001)
  HOST                 — bind address (default 0.0.0.0)
  REPO_ROOT            — absolute path to git repo root (default: parent of this file)
  GIT_PUSH             — set to "false" to disable auto git push (default: true)
  GITHUB_TOKEN         — GitHub Personal Access Token for push auth (required on Colab)
  GITHUB_USER          — GitHub username (default: simbaking)
"""

import os
import sys
import gzip
import json
import time
import glob
import threading
import subprocess
import numpy as np
import tensorflow as tf
from flask import Flask, request, jsonify

# Track file modification time to allow auto-reloading on git pull updates
INITIAL_MTIME = os.path.getmtime(os.path.abspath(__file__))
from tensorflow import keras
from collections import deque

# ── GPU Setup ─────────────────────────────────────────────────────────────────
gpus = tf.config.list_physical_devices("GPU")
if gpus:
    for gpu in gpus:
        tf.config.experimental.set_memory_growth(gpu, True)
    print(f"GPU(s) available: {[g.name for g in gpus]}")
else:
    print("No GPU found — training on CPU (still faster than Java Adam).")

# ── Constants ─────────────────────────────────────────────────────────────────
INPUT_SIZE   = 96
OUTPUT_SIZE  = 58
STANDARD_IN  = 38
AGGRO_IN     = 4
BATCH_SIZE   = 32
LR           = 0.001
SAVE_PATH     = "saved_model/aces_brain.weights.h5"
REPO_ROOT     = os.environ.get("REPO_ROOT", os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GIT_PUSH      = os.environ.get("GIT_PUSH", "true").lower() != "false"
GITHUB_TOKEN  = os.environ.get("GITHUB_TOKEN", "")   # PAT needed for Colab / headless push
GITHUB_USER   = os.environ.get("GITHUB_USER", "simbaking")
WEIGHTS_FILE  = os.path.join(REPO_ROOT, "brain_tf.json")  # committed to latest-ai branch

app         = Flask(__name__)
lock        = threading.Lock()
git_op_lock = threading.Lock()  # Serialises all git ops — prevents Eclipse/Colab index.lock collisions

# ── Stats & Buffer ────────────────────────────────────────────────────────────
stats = {
    "samples_received": 0,
    "train_steps":      0,
    "last_git_push":    "never",
    "gpu_available":    len(gpus) > 0,
}
# Ring-buffer: keep up to 50,000 samples for replay
sample_buffer = deque(maxlen=50_000)

# ── Build Model ───────────────────────────────────────────────────────────────
def build_model():
    inputs = keras.Input(shape=(INPUT_SIZE,), name="board_state")

    standard_in = inputs[:, :STANDARD_IN]
    aggro_in    = inputs[:, STANDARD_IN:STANDARD_IN + AGGRO_IN]

    # Strategy Block
    x = standard_in
    for i in range(9):
        x = keras.layers.Dense(64, activation="sigmoid", name=f"strategy_{i}")(x)
    strategy_out = x

    # Plan Pre Block (keep layer objects for weight sharing)
    plan_pre_layers = []
    plan = strategy_out
    for i in range(5):
        layer = keras.layers.Dense(10, activation="sigmoid", name=f"plan_pre_{i}")
        plan  = layer(plan)
        plan_pre_layers.append(layer)
    plan_pre_out = plan

    # Strategy Bottleneck
    bn_in      = keras.layers.Concatenate(name="bn_concat")([strategy_out, aggro_in])
    bottleneck = keras.layers.Dense(3, activation="sigmoid", name="bottleneck")(bn_in)

    # Modulate Plan neuron (index 2) with planPreOut average — mirrors Java NeuralNetwork
    plan_boost    = keras.layers.Lambda(lambda x: tf.reduce_mean(x, axis=1, keepdims=True), name="plan_boost")(plan_pre_out)
    original_plan = keras.layers.Lambda(lambda x: x[:, 2:3], name="plan_slice")(bottleneck)
    modified_plan = keras.layers.Lambda(lambda x: tf.sigmoid(x[0] + x[1] * 0.5 - 0.5), name="plan_mod")([original_plan, plan_boost])
    
    prefix = keras.layers.Lambda(lambda x: x[:, :2], name="bn_prefix")(bottleneck)
    bottleneck_mod = keras.layers.Concatenate(axis=1, name="bottleneck_mod")([prefix, modified_plan])

    # Plan Post Block (layer 0 independent, layers 1-4 share with plan_pre)
    plan_post = keras.layers.Dense(10, activation="sigmoid", name="plan_post_0")(plan_pre_out)
    for i in range(1, 5):
        plan_post = plan_pre_layers[i](plan_post)   # shared weights
    plan_post_out = plan_post

    # Execution Block
    exec_in = keras.layers.Concatenate(name="exec_concat")([bottleneck_mod, plan_post_out, inputs])
    x = exec_in
    for i in range(9):
        x = keras.layers.Dense(64, activation="sigmoid", name=f"exec_{i}")(x)

    output = keras.layers.Dense(OUTPUT_SIZE, activation="sigmoid", name="output")(x)

    model = keras.Model(inputs=inputs, outputs=output, name="aces_brain")
    model.compile(optimizer=keras.optimizers.Adam(learning_rate=LR), loss="mse")
    return model

print("Building TF model (exact NeuralNetwork.java architecture)...")
model = build_model()
model.summary()

# Try loading from saved_model first, then from committed brain_tf.json
if os.path.exists(SAVE_PATH):
    try:
        model.load_weights(SAVE_PATH)
        print(f"Loaded weights from {SAVE_PATH}")
    except Exception as e:
        print(f"Could not load saved_model weights: {e}")
elif os.path.exists(WEIGHTS_FILE):
    try:
        with open(WEIGHTS_FILE) as f:
            data = json.load(f)
        _name_map = {l.name: l for l in model.layers}
        for entry in data.get("layers", []):
            lyr = _name_map.get(entry["name"])
            if lyr:
                k = np.array(entry["kernel"], dtype=np.float32)
                b = np.array(entry["bias"],   dtype=np.float32)
                lyr.set_weights([k, b] if b.size else [k])
        print(f"Loaded weights from committed {WEIGHTS_FILE}")
    except Exception as e:
        print(f"Could not load brain_tf.json: {e}")
else:
    print("No saved weights — starting fresh.")

# ── Weight Serialisation ──────────────────────────────────────────────────────
def weights_to_list():
    out = []
    for layer in model.layers:
        w = layer.get_weights()
        if len(w) >= 2:
            out.append({"name": layer.name, "kernel": w[0].tolist(), "bias": w[1].tolist()})
        elif len(w) == 1:
            out.append({"name": layer.name, "kernel": w[0].tolist(), "bias": []})
    return out

# ── Git Auth Setup ────────────────────────────────────────────────────────────
_git_auth_configured = False

def setup_git_auth():
    """Injects GitHub PAT into the remote URL and configures git identity.
    Re-runs every time until auth succeeds — safe to call repeatedly."""
    global _git_auth_configured
    if _git_auth_configured:
        return

    # Always ensure git identity + sane merge/rebase defaults are set (needed on Colab & Eclipse)
    subprocess.run(["git", "-C", REPO_ROOT, "config", "user.email",          "trainer@aces.com"], check=False, capture_output=True)
    subprocess.run(["git", "-C", REPO_ROOT, "config", "user.name",            "Aces Trainer"],    check=False, capture_output=True)
    subprocess.run(["git", "-C", REPO_ROOT, "config", "pull.rebase",          "true"],            check=False, capture_output=True)  # rebase instead of merge on pull
    subprocess.run(["git", "-C", REPO_ROOT, "config", "merge.ours.driver",    "true"],            check=False, capture_output=True)  # activates merge=ours driver in .gitattributes

    token = os.environ.get("GITHUB_TOKEN", GITHUB_TOKEN)  # re-read env in case it was set after startup
    if not token:
        print("⚠️  GITHUB_TOKEN not set — git push will fail. Set os.environ['GITHUB_TOKEN'] and retry.")
        return  # do NOT set _git_auth_configured=True so next call retries

    try:
        # Embed token in remote URL so git never prompts for a password
        authed_url = f"https://{GITHUB_USER}:{token}@github.com/{GITHUB_USER}/AIAces.git"
        subprocess.run(["git", "-C", REPO_ROOT, "remote", "set-url", "origin", authed_url],
                       check=True, capture_output=True)
        # Shallow-fetch the latest remote tip so we don't diverge on first push
        # (--depth 1 avoids downloading the full weight history on every startup)
        subprocess.run(["git", "-C", REPO_ROOT, "fetch", "--depth", "1", "origin", "latest-ai"],
                       check=False, capture_output=True)
        subprocess.run(["git", "-C", REPO_ROOT, "rebase", "origin/latest-ai"],
                       check=False, capture_output=True)
        _git_auth_configured = True  # only mark done once it actually succeeded
        print(f"✅ Git auth configured for '{GITHUB_USER}' — auto-push to latest-ai enabled.")
    except Exception as e:
        print(f"⚠️  setup_git_auth failed: {e}")

# ── Git Auto-Push ─────────────────────────────────────────────────────────────
STALE_LOCK_AGE = 30  # seconds — locks older than this are considered abandoned

def remove_stale_git_locks():
    """Removes git lock files older than STALE_LOCK_AGE seconds.

    Locks held by a live Eclipse/git process are recent; genuinely stale
    ones (crashed process, Drive sync artefact) are old.  We only remove
    old ones so we never clobber a legitimately running operation.
    """
    git_dir = os.path.join(REPO_ROOT, ".git")
    if not os.path.exists(git_dir):
        return
    # Scan recursively so we catch refs/heads/*.lock as well
    lock_files = glob.glob(os.path.join(git_dir, "**", "*.lock"), recursive=True)
    for lf in lock_files:
        try:
            age = time.time() - os.path.getmtime(lf)
            if age >= STALE_LOCK_AGE:
                os.remove(lf)
                print(f"Removed stale git lock ({age:.0f}s old): {lf}")
            else:
                print(f"Lock file is recent ({age:.0f}s old) — leaving it: {lf}")
        except Exception as e:
            print(f"Could not inspect/remove lock file {lf}: {e}")

def run_git_command(args, check=True, retries=6, base_delay=2.0):
    """Runs a git command with exponential-backoff retries on lock collisions.

    Safeguard #2: Eclipse and the training server can both run git at the
    same time.  We retry with increasing delays and only remove locks that
    are genuinely stale (see remove_stale_git_locks).
    """
    LOCK_SIGNALS = ("index.lock", "Another git process seems to be running")
    for attempt in range(retries):
        try:
            result = subprocess.run(args, check=check, capture_output=True)
            if not check and result.returncode != 0:
                stderr_str = result.stderr.decode() if result.stderr else ""
                if any(sig in stderr_str for sig in LOCK_SIGNALS):
                    if attempt < retries - 1:
                        wait = base_delay * (2 ** attempt)
                        print(f"Git lock collision (attempt {attempt+1}/{retries}). Retrying in {wait:.1f}s…")
                        time.sleep(wait)
                        remove_stale_git_locks()
                        continue
            return result
        except subprocess.CalledProcessError as e:
            stderr_str = e.stderr.decode() if e.stderr else str(e)
            if any(sig in stderr_str for sig in LOCK_SIGNALS):
                if attempt < retries - 1:
                    wait = base_delay * (2 ** attempt)
                    print(f"Git lock collision (attempt {attempt+1}/{retries}). Retrying in {wait:.1f}s…")
                    time.sleep(wait)
                    remove_stale_git_locks()
                    continue
            if check:
                raise
            return e

def git_push_weights():
    """Saves weights to brain_tf.json and force-pushes to the latest-ai branch.

    History strategy: amend the last commit if it was a weight commit so that
    only ONE weight commit ever exists in history — no accumulation of old brains.
    Safeguard: git_op_lock serialises all git ops within this process.
    """
    if not GIT_PUSH:
        return
    setup_git_auth()  # no-op after first call; ensures Colab auth + identity is ready

    with git_op_lock:
        try:
            payload = {"layers": weights_to_list()}
            with open(WEIGHTS_FILE, "w") as f:
                json.dump(payload, f)

            run_git_command(["git", "-C", REPO_ROOT, "add", "brain_tf.json"], check=True)
            result = run_git_command(["git", "-C", REPO_ROOT, "diff", "--cached", "--quiet"], check=False)

            if getattr(result, "returncode", -1) != 0:
                run_git_command(["git", "-C", REPO_ROOT, "checkout", "latest-ai"], check=False)

                # Amend if HEAD is already a weight commit — keeps history to a single weight commit.
                # If HEAD is a code commit (e.g. server.py fix), create a new commit on top.
                last_msg = subprocess.run(
                    ["git", "-C", REPO_ROOT, "log", "-1", "--format=%s"],
                    capture_output=True, text=True
                ).stdout.strip()
                is_weight_commit = last_msg.startswith("[auto]") or last_msg.startswith("[force]")

                commit_args = ["git", "-C", REPO_ROOT, "commit"]
                if is_weight_commit:
                    commit_args.append("--amend")
                commit_args += ["-m", f"[auto] weights step {stats['train_steps']}"]
                run_git_command(commit_args, check=True)

                # Force push — replaces the single weight commit instead of stacking a new one
                push_result = run_git_command(
                    ["git", "-C", REPO_ROOT, "push", "--force", "origin", "latest-ai"],
                    check=False
                )
                push_stderr = ""
                if hasattr(push_result, "stderr") and push_result.stderr:
                    push_stderr = push_result.stderr.decode(errors="replace")
                if getattr(push_result, "returncode", 0) != 0:
                    print(f"Git force-push failed: {push_stderr}")
                    return

                stats["last_git_push"] = time.strftime("%Y-%m-%d %H:%M:%S")
                print(f"[Step {stats['train_steps']}] Weights force-pushed (history stays clean).")
        except subprocess.CalledProcessError as e:
            print(f"Git push failed: {e.stderr.decode() if e.stderr else e}")
        except Exception as e:
            print(f"git_push_weights error: {e}")

# ── Continuous Training Thread ────────────────────────────────────────────────
TRAIN_PUSH_EVERY = 100   # push weights to git every N training steps

def continuous_train_loop():
    """Runs forever — drains the sample buffer and trains as fast as TF allows."""
    print("Continuous training thread started.")
    while True:
        with lock:
            buf_size = len(sample_buffer)

        if buf_size < BATCH_SIZE:
            time.sleep(0.05)   # brief pause when buffer is empty
            continue

        # Sample a random mini-batch from the replay buffer
        with lock:
            batch = [sample_buffer[i] for i in
                     np.random.choice(len(sample_buffer), BATCH_SIZE, replace=False)]

        X       = np.array([s[0] for s in batch], dtype=np.float32)
        actions = [s[1] for s in batch]
        rewards = [s[2] for s in batch]

        # Build target array — only update the chosen action's output
        Y = model.predict(X, verbose=0)
        for i, (action, reward) in enumerate(zip(actions, rewards)):
            Y[i, action] = float(reward)

        model.train_on_batch(X, Y)

        with lock:
            stats["train_steps"] += 1
            step = stats["train_steps"]

        # Auto-save locally every 25 steps
        if step % 25 == 0:
            os.makedirs("saved_model", exist_ok=True)
            model.save_weights(SAVE_PATH)

        # Push to git every TRAIN_PUSH_EVERY steps
        if step % TRAIN_PUSH_EVERY == 0:
            git_push_weights()
            print(f"[Step {step}] Buffer: {len(sample_buffer)} | Total samples: {stats['samples_received']}")

train_thread = threading.Thread(target=continuous_train_loop, daemon=True, name="ContinuousTrainer")
train_thread.start()

# ── REST Endpoints ────────────────────────────────────────────────────────────
@app.route("/status", methods=["GET"])
def status():
    return jsonify({**stats, "buffer_size": len(sample_buffer)})


@app.route("/push", methods=["POST"])
def force_push():
    """Immediately save and push weights to git — runs synchronously.

    Amends the last weight commit (if HEAD is a weight commit) and force-pushes
    so git history stays lean: only one weight commit ever exists.
    """
    setup_git_auth()
    log = []
    try:
        token = os.environ.get("GITHUB_TOKEN", GITHUB_TOKEN)
        if not token:
            return jsonify({"ok": False, "error": "GITHUB_TOKEN is not set."}), 400

        with git_op_lock:
            payload = {"layers": weights_to_list()}
            with open(WEIGHTS_FILE, "w") as f:
                json.dump(payload, f)
            log.append("✅ brain_tf.json written")

            run_git_command(["git", "-C", REPO_ROOT, "add", "brain_tf.json"], check=True)
            log.append("✅ git add done")

            run_git_command(["git", "-C", REPO_ROOT, "checkout", "latest-ai"], check=False)

            # Amend if HEAD is already a weight commit — history stays at one weight commit
            last_msg = subprocess.run(
                ["git", "-C", REPO_ROOT, "log", "-1", "--format=%s"],
                capture_output=True, text=True
            ).stdout.strip()
            is_weight_commit = last_msg.startswith("[auto]") or last_msg.startswith("[force]")

            commit_args = ["git", "-C", REPO_ROOT, "commit", "--allow-empty"]
            if is_weight_commit:
                commit_args.append("--amend")
            commit_args += ["-m", f"[force] weights step {stats['train_steps']}"]

            commit = subprocess.run(commit_args, capture_output=True, text=True)
            log.append(f"commit: {commit.stdout.strip()} {commit.stderr.strip()}")
            if commit.returncode != 0:
                return jsonify({"ok": False, "log": log, "error": commit.stderr.strip()}), 500

            # Force push — replaces the weight commit in history, no stacking
            push = subprocess.run(
                ["git", "-C", REPO_ROOT, "push", "--force", "origin", "latest-ai"],
                capture_output=True, text=True
            )
            log.append(f"push stdout: {push.stdout.strip()}")
            log.append(f"push stderr: {push.stderr.strip()}")
            if push.returncode != 0:
                return jsonify({"ok": False, "log": log, "error": push.stderr.strip()}), 500

            log.append(f"push stdout: {push.stdout.strip()}")
            log.append(f"push stderr: {push.stderr.strip()}")
            stats["last_git_push"] = time.strftime("%Y-%m-%d %H:%M:%S")
            log.append(f"✅ Pushed to latest-ai at step {stats['train_steps']}")
            return jsonify({"ok": True, "log": log})

    except Exception as e:
        log.append(f"❌ Exception: {e}")
        return jsonify({"ok": False, "log": log, "error": str(e)}), 500


@app.route("/git_status", methods=["GET"])
def git_status():
    """Debug endpoint — shows git remote, branch, log, and token state."""
    token = os.environ.get("GITHUB_TOKEN", GITHUB_TOKEN)
    remote = subprocess.run(["git", "-C", REPO_ROOT, "remote", "get-url", "origin"], capture_output=True, text=True)
    branch = subprocess.run(["git", "-C", REPO_ROOT, "branch", "--show-current"], capture_output=True, text=True)
    log    = subprocess.run(["git", "-C", REPO_ROOT, "log", "--oneline", "-3"], capture_output=True, text=True)
    status = subprocess.run(["git", "-C", REPO_ROOT, "status", "--short"], capture_output=True, text=True)
    # Mask token in URL for safety
    remote_url = remote.stdout.strip()
    if token and token in remote_url:
        remote_url = remote_url.replace(token, "***TOKEN***")
    return jsonify({
        "token_set":    bool(token),
        "auth_done":    _git_auth_configured,
        "remote_url":   remote_url,
        "branch":       branch.stdout.strip(),
        "recent_log":   log.stdout.strip().splitlines(),
        "git_status":   status.stdout.strip(),
        "repo_root":    REPO_ROOT,
        "weights_file": WEIGHTS_FILE,
    })


@app.route("/train", methods=["POST"])
def train():
    if request.headers.get("Content-Encoding") == "gzip":
        try:
            decompressed = gzip.decompress(request.get_data())
            data = json.loads(decompressed)
        except Exception as e:
            return jsonify({"ok": False, "error": f"Failed to decompress Gzip: {e}"}), 400
    else:
        data = request.get_json(force=True)

    samples = data.get("samples", [])
    with lock:
        for s in samples:
            sample_buffer.append((s["inputs"], s["action"], float(s["reward"])))
            stats["samples_received"] += 1
    return jsonify({"ok": True, "queued": len(samples), "buffer": len(sample_buffer)})

@app.route("/weights", methods=["GET"])
def get_weights():
    weights_data = {"layers": weights_to_list()}
    response_data = json.dumps(weights_data).encode("utf-8")
    
    if "gzip" in request.headers.get("Accept-Encoding", "").lower():
        compressed = gzip.compress(response_data)
        response = app.make_response(compressed)
        response.headers["Content-Encoding"] = "gzip"
        response.headers["Content-Type"] = "application/json"
        return response
        
    return jsonify(weights_data)

# NOTE: /push is defined above as force_push() — duplicate removed to avoid Flask routing ambiguity.

@app.route("/load_weights", methods=["POST"])
def load_weights():
    data = request.get_json(force=True)
    try:
        name_map = {l.name: l for l in model.layers}
        for entry in data.get("layers", []):
            lyr = name_map.get(entry["name"])
            if not lyr:
                continue
            k = np.array(entry["kernel"], dtype=np.float32)
            b = np.array(entry["bias"],   dtype=np.float32)
            lyr.set_weights([k, b] if b.size else [k])
        return jsonify({"ok": True})
    except Exception as e:
        return jsonify({"ok": False, "error": str(e)}), 400

if __name__ == "__main__":
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", 5001))
    print(f"\nAce's TF Training Server → {host}:{port}")
    print(f"Git repo root : {REPO_ROOT}")
    print(f"Git auto-push : {GIT_PUSH} (branch: latest-ai)")
    print(f"Weights file  : {WEIGHTS_FILE}\n")
    app.run(host=host, port=port, threaded=True)
