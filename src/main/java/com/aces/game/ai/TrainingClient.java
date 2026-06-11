package com.aces.game.ai;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Sends training samples to the remote Python TensorFlow training server
 * and periodically pulls back optimised weights to apply to the local NeuralNetwork.
 *
 * Set the environment variable TRAINING_SERVER_URL to point at the server, e.g.:
 *   export TRAINING_SERVER_URL=http://my-cloud-vm:5001
 *
 * If the variable is not set or the server is unreachable, this class becomes a
 * no-op and local training continues as normal (graceful degradation).
 */
public class TrainingClient {

    private static final Logger log = Logger.getLogger(TrainingClient.class.getName());

    // ── Singleton ─────────────────────────────────────────────────────────────
    // ── Config ────────────────────────────────────────────────────────────────
    /** How many samples to queue before flushing to server (increased to 4096 to prevent rate limits). */
    private static final int  FLUSH_THRESHOLD   = 4096;
    /** Pull weights from server every N samples sent. */
    private static final int  WEIGHT_SYNC_EVERY = 20000;
    /** How often (seconds) to proactively pull latest weights from the server. (Increased to 15 mins to save bandwidth) */
    private static final long WEIGHT_PULL_INTERVAL_SECS = 900;
    /** How often (seconds) to git-pull brain_tf.json as a fallback sync channel. */
    private static final long GIT_SYNC_INTERVAL_SECS = 300; // 5 minutes
    /** HTTP timeout per request (increased to 30s to allow for network lag). */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final TrainingClient INSTANCE = new TrainingClient();
    public static TrainingClient getInstance() { return INSTANCE; }

    // ── State ─────────────────────────────────────────────────────────────────
    private final String serverUrl;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    // HttpClient removed to use HttpURLConnection instead

    private final List<Map<String, Object>> sampleQueue = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger totalSent = new AtomicInteger(0);
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    // Cached remote status — updated by background scheduler so HTTP threads never block
    private volatile Map<String, Object> cachedRemoteStatus = null;

    // Background flush thread
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TrainingClient-Flush");
                t.setDaemon(true);
                return t;
            });

    // ── Constructor ───────────────────────────────────────────────────────────
    private TrainingClient() {
        String url = System.getProperty("training.server.url");
        if (url == null || url.isBlank()) {
            url = System.getenv("TRAINING_SERVER_URL");
        }
        if (url == null) url = "";
        this.serverUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.enabled   = !this.serverUrl.isBlank();

        if (enabled) {
            log.info("TrainingClient: Remote TF training enabled → " + serverUrl);
            // Flush queue every 15 seconds regardless of threshold
            scheduler.scheduleAtFixedRate(this::flushIfNeeded, 15, 15, TimeUnit.SECONDS);
            // Proactively pull latest weights via HTTP every 2 minutes
            scheduler.scheduleAtFixedRate(this::pullAndApplyWeights,
                    WEIGHT_PULL_INTERVAL_SECS, WEIGHT_PULL_INTERVAL_SECS, TimeUnit.SECONDS);
            // Pull weights from server immediately on startup (non-blocking)
            Thread startupSync = new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    log.info("TrainingClient: Pulling latest weights from TF server on startup...");
                    pullAndApplyWeights();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "TrainingClient-StartupSync");
            startupSync.setDaemon(true);
            startupSync.start();
        } else {
            log.info("TrainingClient: TRAINING_SERVER_URL not set — remote training disabled.");
        }

        // Git sync runs always (HTTP or not) — Colab pushes brain_tf.json every 100 steps
        scheduler.scheduleAtFixedRate(this::gitPullAndReload,
                60, GIT_SYNC_INTERVAL_SECS, TimeUnit.SECONDS);

        // Refresh remote status cache in background every 10 seconds (non-blocking for request threads)
        scheduler.scheduleAtFixedRate(this::refreshRemoteStatusCache, 5, 10, TimeUnit.SECONDS);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Queues a training sample. Flushes to server when threshold is reached.
     * Non-blocking — never throws.
     */
    public void queueSample(List<Double> inputs, int actionIndex, double reward) {
        if (!enabled) return;
        // Filter out 90% of zero-reward actions to save significant bandwidth
        if (Math.abs(reward) < 1e-6) {
            if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= 0.1) {
                return;
            }
        }
        Map<String, Object> sample = new HashMap<>();
        sample.put("inputs", inputs);
        sample.put("action", actionIndex);
        sample.put("reward", reward);
        sampleQueue.add(sample);

        if (sampleQueue.size() >= FLUSH_THRESHOLD && flushScheduled.compareAndSet(false, true)) {
            scheduler.submit(this::flushIfNeeded);
        }
    }

    /** Returns true if the remote server is configured and reachable. */
    public boolean isEnabled() { return enabled; }

    /**
     * Immediately pulls and applies the latest weights from the TF server.
     * Non-blocking — call from any thread. No-op if server is not configured.
     */
    public void forceWeightSync() {
        if (!enabled) return;
        scheduler.submit(this::pullAndApplyWeights);
    }

    /**
     * Tells the TF server to immediately commit + push brain_tf.json to the
     * latest-ai branch. Called when Java saves a local checkpoint so both
     * brain.json and brain_tf.json are always in sync on git.
     * Non-blocking — never throws.
     */
    public void forcePush() {
        if (!enabled) return;
        scheduler.submit(() -> {
            try {
                java.net.URL url = new java.net.URL(serverUrl + "/push");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout((int) TIMEOUT.toMillis());
                conn.setReadTimeout((int) TIMEOUT.toMillis());
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("ngrok-skip-browser-warning", "true");
                conn.setDoOutput(true);
                conn.getOutputStream().close();

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    log.info("TrainingClient: Force-push triggered on TF server.");
                } else {
                    log.warning("TrainingClient: forcePush failed with HTTP " + code);
                }
            } catch (Exception e) {
                log.warning("TrainingClient: forcePush failed — " + e.getMessage());
            }
        });
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private synchronized void flushIfNeeded() {
        try {
            if (sampleQueue.isEmpty()) return;

            List<Map<String, Object>> batch;
            synchronized (sampleQueue) {
                batch = new ArrayList<>(sampleQueue);
                sampleQueue.clear();
            }

            try {
                Map<String, Object> body = Map.of("samples", batch);
                byte[] jsonBytes = mapper.writeValueAsBytes(body);

                java.net.URL url = new java.net.URL(serverUrl + "/train");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout((int) TIMEOUT.toMillis());
                conn.setReadTimeout((int) TIMEOUT.toMillis());
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Content-Encoding", "gzip");
                conn.setRequestProperty("ngrok-skip-browser-warning", "true");
                conn.setDoOutput(true);
                
                try (java.io.OutputStream os = conn.getOutputStream();
                     java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(os)) {
                    gzos.write(jsonBytes);
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code);
                }

                int sent = totalSent.addAndGet(batch.size());
                log.fine("TrainingClient: Sent " + batch.size() + " samples (total=" + sent + ")");

                // Sync weights back periodically
                if (sent % WEIGHT_SYNC_EVERY < batch.size()) {
                    pullAndApplyWeights();
                }

            } catch (Exception e) {
                log.warning("TrainingClient: Failed to send batch — " + e.getClass().getSimpleName() + ": " + e.getMessage());
                // Re-queue so we don't lose samples
                synchronized (sampleQueue) {
                    sampleQueue.addAll(0, batch);
                    if (sampleQueue.size() > FLUSH_THRESHOLD * 5) {
                        sampleQueue.subList(0, sampleQueue.size() - FLUSH_THRESHOLD * 2).clear();
                    }
                }
                // Back off on error to avoid hammering the server
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        } finally {
            flushScheduled.set(false);
        }
    }

    /**
     * Git-based weight sync — runs every 5 minutes regardless of HTTP connectivity.
     * Fetches ONLY brain_tf.json from origin/latest-ai (no branch merge) so we never
     * disturb the local working branch. After applying weights, persists them to
     * brain.json so they survive a restart.
     */
    @SuppressWarnings("unchecked")
    private void gitPullAndReload() {
        try {
            String repoRoot = System.getProperty("user.dir");
            File repoDir    = new File(repoRoot);

            // Step 1: fetch latest refs from origin (fast, no merge)
            ProcessBuilder fetchPb = new ProcessBuilder("git", "fetch", "origin", "latest-ai")
                    .directory(repoDir)
                    .redirectErrorStream(true);
            Process fetchProc = fetchPb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(fetchProc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) { log.fine("git fetch: " + line); }
            }
            int fetchExit = fetchProc.waitFor();
            if (fetchExit != 0) {
                log.warning("TrainingClient: git fetch exited with code " + fetchExit + " — skipping weight reload.");
                return;
            }

            // Step 2: checkout ONLY brain_tf.json from origin/latest-ai — no branch switch
            ProcessBuilder checkoutPb = new ProcessBuilder(
                    "git", "checkout", "origin/latest-ai", "--", "brain_tf.json")
                    .directory(repoDir)
                    .redirectErrorStream(true);
            Process checkoutProc = checkoutPb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(checkoutProc.getInputStream()))) {
                while (br.readLine() != null) { /* consume */ }
            }
            int checkoutExit = checkoutProc.waitFor();
            if (checkoutExit != 0) {
                log.warning("TrainingClient: git checkout brain_tf.json exited with code " + checkoutExit);
                return;
            }

            // Step 3: Load and apply the freshly-fetched brain_tf.json
            File tfBrain = new File(repoRoot, "brain_tf.json");
            if (!tfBrain.exists()) return;

            Map<String, Object> data = mapper.readValue(tfBrain, Map.class);
            List<Map<String, Object>> layers = (List<Map<String, Object>>) data.get("layers");
            if (layers == null || layers.isEmpty()) return;

            synchronized (GlobalAi.getInstance()) {
                applyWeightsToNetwork(GlobalAi.getInstance(), layers);
            }
            log.info("TrainingClient: Applied latest brain_tf.json from git (" + layers.size() + " layers). Saving to brain.json...");

            // Step 4: Persist to brain.json so weights survive a restart
            GlobalAi.save();

        } catch (Exception e) {
            log.warning("TrainingClient: git sync failed — " + e.getMessage());
        }
    }


    @SuppressWarnings("unchecked")
    private void pullAndApplyWeights() {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                java.net.URL url = new java.net.URL(serverUrl + "/weights");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(20000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept-Encoding", "gzip");
                conn.setRequestProperty("ngrok-skip-browser-warning", "true");

                int code = conn.getResponseCode();
                if (code != 200) {
                    if (attempt < maxRetries) {
                        Thread.sleep(2000L * attempt);
                        continue;
                    }
                    return;
                }

                Map<String, Object> body;
                String encoding = conn.getContentEncoding();
                java.io.InputStream rawIs = conn.getInputStream();
                java.io.InputStream is = (encoding != null && encoding.contains("gzip"))
                        ? new java.util.zip.GZIPInputStream(rawIs)
                        : rawIs;
                try {
                    body = mapper.readValue(is, Map.class);
                } finally {
                    is.close();
                }

                List<Map<String, Object>> layers = (List<Map<String, Object>>) body.get("layers");
                if (layers == null || layers.isEmpty()) return;

                synchronized (GlobalAi.getInstance()) {
                    applyWeightsToNetwork(GlobalAi.getInstance(), layers);
                }

                log.info("TrainingClient: Synced weights from remote TF server (" + layers.size() + " layers). Saving to brain.json...");
                // Persist to disk so weights survive a restart
                GlobalAi.save();
                return; // Success, break out of retry loop

            } catch (Exception e) {
                if (attempt == maxRetries) {
                    log.warning("TrainingClient: Weight sync failed after " + maxRetries + " attempts — " + e.getMessage());
                } else {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /**
     * Maps Python Keras layer weights back onto the Java NeuralNetwork.
     *
     * Layer order from server.py (same order Keras stores them):
     *   strategy_0..8   → strategyLayers[0..8]
     *   plan_pre_0..4   → planPreLayers[0..4]
     *   bottleneck      → strategyBottleneck
     *   plan_post_0     → planPostLayers[0]   (plan_pre 1-4 are shared, auto-synced)
     *   exec_0..8       → executionLayers[0..8]
     *   output          → outputLayer
     */
    @SuppressWarnings("unchecked")
    private static void applyWeightsToNetwork(NeuralNetwork nn, List<Map<String, Object>> layers) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> l : layers) byName.put((String) l.get("name"), l);

        // Strategy layers
        if (nn.getStrategyLayers() != null) {
            for (int i = 0; i < nn.getStrategyLayers().size(); i++) {
                applyToLayer(nn.getStrategyLayers().get(i), byName.get("strategy_" + i));
            }
        }
        // Plan pre layers
        if (nn.getPlanPreLayers() != null) {
            for (int i = 0; i < nn.getPlanPreLayers().size(); i++) {
                applyToLayer(nn.getPlanPreLayers().get(i), byName.get("plan_pre_" + i));
            }
        }
        // Bottleneck
        applyToLayer(nn.getStrategyBottleneck(), byName.get("bottleneck"));

        // Plan post layer 0
        if (nn.getPlanPostLayers() != null && !nn.getPlanPostLayers().isEmpty()) {
            applyToLayer(nn.getPlanPostLayers().get(0), byName.get("plan_post_0"));
            // plan_post layers 1-4 share weights with plan_pre 1-4 — sync them
            nn.syncPlanWeights();
        }
        // Execution layers
        if (nn.getExecutionLayers() != null) {
            for (int i = 0; i < nn.getExecutionLayers().size(); i++) {
                applyToLayer(nn.getExecutionLayers().get(i), byName.get("exec_" + i));
            }
        }
        // Output layer
        applyToLayer(nn.getOutputLayer(), byName.get("output"));
    }

    @SuppressWarnings("unchecked")
    private static void applyToLayer(Layer layer, Map<String, Object> data) {
        if (layer == null || data == null) return;
        List<List<Double>> kernel = (List<List<Double>>) data.get("kernel"); // [in, out]
        List<Double> bias         = (List<Double>)       data.get("bias");

        List<Neuron> neurons = layer.getNeurons();
        int outSize = neurons.size();
        if (kernel == null || kernel.isEmpty()) return;
        int inSize = kernel.size();

        for (int outIdx = 0; outIdx < outSize; outIdx++) {
            Neuron n = neurons.get(outIdx);
            List<Double> w = n.getWeights();
            // Keras kernel is [in, out] → neuron outIdx gets kernel[0..inSize-1][outIdx]
            for (int inIdx = 0; inIdx < Math.min(inSize, w.size()); inIdx++) {
                List<Double> col = (List<Double>) (Object) kernel.get(inIdx);
                if (col != null && outIdx < col.size()) {
                    w.set(inIdx, col.get(outIdx));
                }
            }
            if (bias != null && outIdx < bias.size()) {
                n.setBias(bias.get(outIdx));
            }
        }
    }

    /**
     * Background task: fetches /status from the remote server and caches it.
     * Runs on the scheduler thread every 10 s — never blocks Tomcat request threads.
     */
    @SuppressWarnings("unchecked")
    private void refreshRemoteStatusCache() {
        if (!enabled) {
            Map<String, Object> map = new HashMap<>();
            map.put("enabled", false);
            cachedRemoteStatus = map;
            return;
        }
        try {
            java.net.URL url = new java.net.URL(serverUrl + "/status");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("ngrok-skip-browser-warning", "true");

            int code = conn.getResponseCode();
            if (code == 200) {
                try (java.io.InputStream is = conn.getInputStream()) {
                    Map<String, Object> res = mapper.readValue(is, Map.class);
                    Map<String, Object> out = new HashMap<>(res);
                    out.put("enabled", true);
                    out.put("connected", true);
                    out.put("url", serverUrl);
                    cachedRemoteStatus = out;
                    return;
                }
            }
        } catch (Exception e) {
            // Fall through — mark as disconnected
        }
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", true);
        map.put("connected", false);
        map.put("url", serverUrl);
        cachedRemoteStatus = map;
    }

    /**
     * Returns the last cached remote server status.
     * Non-blocking — always returns immediately from cache.
     * Cache is refreshed every 10 s by the background scheduler.
     */
    public Map<String, Object> getRemoteStatus() {
        if (cachedRemoteStatus != null) {
            return new HashMap<>(cachedRemoteStatus);
        }
        // Cache not yet populated — return a loading placeholder
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", enabled);
        map.put("connected", false);
        map.put("loading", true);
        if (enabled) map.put("url", serverUrl);
        return map;
    }
}

