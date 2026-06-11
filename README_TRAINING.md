# Ace's — Continuous AI Training on `latest-ai`

The `latest-ai` git branch is the **living AI branch** — it always holds the most recently trained neural network weights. Every time the TensorFlow training server completes 100 training steps, it automatically commits and pushes updated weights to this branch.

---

## Architecture

```
┌─────────────────────────┐        samples (POST /train)        ┌───────────────────────────────┐
│  Java Spring Boot Game  │ ──────────────────────────────────▶ │  Python TF Training Server    │
│  (BackgroundTrainer)    │                                      │  (training_server/server.py)  │
│                         │ ◀────────── weights (GET /weights) ─ │  • Keras model (GPU/CPU)      │
│  GlobalAi (local NN)    │       every 200 samples sent         │  • Replay buffer (50k max)    │
│  TrainingClient         │ ◀─────────────────────────────────── │  • Adam optimizer             │
└─────────────────────────┘        every 2 minutes (pull)        └──────────┬────────────────────┘
                                                                             │ auto-commit + push
                                                                             ▼ every 100 steps
                                                                   ┌─────────────────────┐
                                                                   │  git latest-ai      │
                                                                   │  brain_tf.json      │
                                                                   └─────────────────────┘
```

### What runs continuously
| Component | Where | What it does |
|---|---|---|
| `BackgroundTrainer` | Java JVM | Runs CPU-vs-CPU self-play games 24/7, generates training samples |
| `TrainingClient` | Java JVM | Sends samples to TF server, pulls weights back every 200 samples + every 2 min |
| `continuous_train_loop` | Python server | Trains the Keras model as fast as TF allows using a 50k replay buffer |
| `git_push_weights` | Python server | Commits `brain_tf.json` and pushes to `latest-ai` every 100 training steps |
| `GlobalAi.save()` | Java JVM | Saves `brain.json` + triggers immediate git push every 50 self-play games |

---

## Running locally

### 1. Start the training server
```bash
chmod +x training_server/start_training_server.sh
./training_server/start_training_server.sh
```
This will:
- Switch to the `latest-ai` branch
- `git pull` the latest weights
- Install Python deps in a `venv`
- Start the Flask server on `http://0.0.0.0:5001`

### 2. Start the Java game (with training linked)
```bash
export TRAINING_SERVER_URL=http://localhost:5001
mvn spring-boot:run
```

When the game starts, `TrainingClient` will:
1. Pull the latest weights from the server after 3 seconds
2. Begin streaming all self-play training samples to the server
3. Pull updated weights back every 200 samples (or every 2 minutes)

---

## Running on a remote machine (cloud VM, spare PC)
```bash
git clone <your-repo>
cd AIAces
git checkout latest-ai
./training_server/start_training_server.sh
```

Then point your local game at it:
```bash
export TRAINING_SERVER_URL=http://<server-ip>:5001
```

---

## Git auto-commit cadence

| Trigger | Frequency | What gets committed |
|---|---|---|
| Training steps | Every 100 steps | `brain_tf.json` (TF weights) |
| Java checkpoint | Every 50 self-play games | `brain.json` + forced TF push |
| Startup pull | On server start | Gets latest from `origin/latest-ai` |

---

## Useful endpoints

| Endpoint | Method | Description |
|---|---|---|
| `GET /status` | GET | Health + stats (samples, steps, last push) |
| `POST /train` | POST | Submit training samples from Java |
| `GET /weights` | GET | Download current weights (Java uses this) |
| `POST /load_weights` | POST | Upload weights from Java (bootstrap) |
| `POST /push` | POST | Immediately commit + push weights to git |

---

## Environment variables (server)

| Variable | Default | Description |
|---|---|---|
| `PORT` | `5001` | Server port |
| `HOST` | `0.0.0.0` | Bind address |
| `REPO_ROOT` | parent of `training_server/` | Path to the git repo root |
| `GIT_PUSH` | `true` | Set to `false` to disable auto git push |
| `TRAINING_SERVER_URL` | *(none)* | **Java-side** — URL of the TF server |

---

## Push safeguards (Colab + Eclipse)

Three problems can prevent Colab or Eclipse from pushing cleanly. All three are handled automatically by the server:

| # | Problem | Safeguard |
|---|---|---|
| 1 | **Lock file collisions** — Eclipse and the training server both try to run git at the same time, causing `index.lock` errors | `git_op_lock` serialises all git ops inside the server process; `run_git_command` retries with exponential backoff (up to 6 attempts, 2 → 4 → 8 … s) and only removes lock files older than 30 s so live Eclipse operations are never interrupted |
| 2 | **Diverged history** — Eclipse pushes a code commit while Colab/server has pending weight commits; the next server push is rejected as non-fast-forward | `pull --rebase -X ours` before every push; on rejection the server rebases and retries up to 3 more times. `brain_tf.json` and `brain.json` are marked `merge=ours` in `.gitattributes` so the server's weights always win in a conflict |
| 3 | **GitHub branch protection** — if `latest-ai` has rules that require PRs or status checks, even a valid token gets a 403 | See below ↓ |

### GitHub branch protection settings for `latest-ai`

Go to **Settings → Branches → latest-ai** on GitHub and ensure:

- ✅ "Require a pull request before merging" is **OFF** (or your token account is added to the bypass list)
- ✅ "Require status checks to pass" is **OFF** (or bypassed for the token account)
- ✅ "Allow force pushes" can stay **OFF** — the server uses rebase, not force push

If you want to keep protection on `main` but allow free pushes to `latest-ai`, simply leave `latest-ai` unprotected. The training loop never touches `main`.
