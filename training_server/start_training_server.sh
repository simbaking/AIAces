#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# start_training_server.sh
# Starts the Ace's TF training server locally and ensures the latest-ai branch
# is always up-to-date with the best trained weights.
#
# Usage:
#   chmod +x training_server/start_training_server.sh
#   ./training_server/start_training_server.sh
#
# To point the Java game at this local server, run Spring Boot with:
#   export TRAINING_SERVER_URL=http://localhost:5001
#   mvn spring-boot:run
# ─────────────────────────────────────────────────────────────────────────────

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "═══════════════════════════════════════════════════════"
echo "  Ace's TF Training Server — latest-ai branch"
echo "═══════════════════════════════════════════════════════"

# ── Git identity for auto-commits ─────────────────────────────────────────────
git -C "$REPO_ROOT" config user.email "ai-trainer@aces" 2>/dev/null || true
git -C "$REPO_ROOT" config user.name  "Aces Trainer"    2>/dev/null || true

# ── Ensure we're on the latest-ai branch ─────────────────────────────────────
CURRENT_BRANCH=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)
if [ "$CURRENT_BRANCH" != "latest-ai" ]; then
    echo "⚠  Switching to latest-ai branch (was: $CURRENT_BRANCH)..."
    git -C "$REPO_ROOT" checkout latest-ai
fi

# ── Pull latest weights before starting ──────────────────────────────────────
echo "↓  Pulling latest weights from origin/latest-ai..."
git -C "$REPO_ROOT" pull origin latest-ai --rebase 2>/dev/null || echo "   (no remote or already up-to-date)"

# ── Python env setup ──────────────────────────────────────────────────────────
cd "$SCRIPT_DIR"

if [ ! -d "venv" ]; then
    echo "📦  Creating Python virtual environment..."
    python3 -m venv venv
fi

source venv/bin/activate
echo "📦  Installing / upgrading Python dependencies..."
pip install -q --upgrade pip
pip install -q -r requirements.txt

# ── Environment ───────────────────────────────────────────────────────────────
export REPO_ROOT="$REPO_ROOT"
export PORT="${PORT:-5001}"
export HOST="${HOST:-0.0.0.0}"
export GIT_PUSH="${GIT_PUSH:-true}"

echo ""
echo "🚀  Starting server → http://$HOST:$PORT"
echo "   Repo root  : $REPO_ROOT"
echo "   Git push   : $GIT_PUSH (branch: latest-ai)"
echo "   GPU?       : $(python3 -c 'import tensorflow as tf; print(tf.config.list_physical_devices(\"GPU\"))' 2>/dev/null || echo 'unknown')"
echo ""
echo "   To connect from the Java game:"
echo "   export TRAINING_SERVER_URL=http://localhost:$PORT"
echo "   mvn spring-boot:run"
echo ""

python3 server.py
