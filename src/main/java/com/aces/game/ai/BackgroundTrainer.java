package com.aces.game.ai;

import com.aces.game.domain.Card;
import com.aces.game.domain.GameState;
import com.aces.game.domain.Player;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs CPU-vs-CPU self-play games in the background to train the neural network.
 * Completely isolated from the main game state — uses temporary GameState instances.
 * Starts when a game ends and stops when a new game begins.
 *
 * Training approach:
 *   1) Per-move intermediate rewards: after each action, measures how much closer
 *      the player has moved toward Ace compared to a 10-move rolling average from previous turns, and trains immediately.
 *   2) End-of-game trajectory replay: after the game finishes, replays ALL recorded
 *      moves and trains with decaying win/loss rewards (recent moves weighted more).
 *
 * Training modes (see {@link TrainingMode}):
 *   STANDARD    – original balanced multi-player + solo batch training.
 *   STACK_FOCUS – exclusively rewards stack cards that approach Ace (very heavy signal).
 */
@Service
public class BackgroundTrainer {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bg-trainer");
        t.setDaemon(true); // Won't block JVM shutdown
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger gamesPlayed = new AtomicInteger(0);
    private final AtomicInteger gameCounter = new AtomicInteger(0); // Tracks 0,1,2 cycle for solo games
    private static final int MAX_TURNS_PER_GAME = 500; // Safety limit

    /** Current active training mode — can be switched at runtime. */
    private volatile TrainingMode currentMode = TrainingMode.STACK_FOCUS;

    // --- Solo batch tracking ---
    private static final int SOLO_BATCH_SIZE = 20; // Games per solo batch
    private int soloBatchCount = 0;                 // Solo games played in current batch
    private int soloBatchTotalMoves = 0;             // Accumulated moves-to-ace across the batch
    private int soloBatchAceCount = 0;               // How many games in the batch actually reached Ace
    private double bestAverageMovesToAce = Double.MAX_VALUE; // All-time record
    private NeuralNetwork batchSnapshot = null;      // Weights snapshot taken at batch start

    /** A snapshot of one move: the NN inputs, chosen action, and distance to Ace after acting. */
    private static class MoveRecord {
        final List<Double> inputs;
        final int actionIndex;
        final double distanceToAce;

        MoveRecord(List<Double> inputs, int actionIndex, double distanceToAce) {
            this.inputs = inputs;
            this.actionIndex = actionIndex;
            this.distanceToAce = distanceToAce;
        }
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            System.out.println("BackgroundTrainer: Starting self-play training (mode=" + currentMode + ")...");
            gamesPlayed.set(0);
            executor.submit(this::trainingLoop);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            System.out.println("BackgroundTrainer: Stopping. Total games played: " + gamesPlayed.get());
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getGamesPlayed() {
        return gamesPlayed.get();
    }

    public TrainingMode getTrainingMode() {
        return currentMode;
    }

    /**
     * Switch training mode at runtime. Takes effect on the next game iteration.
     * Switching to STACK_FOCUS resets the solo-batch counters so the new mode
     * starts with a clean slate.
     */
    public void setTrainingMode(TrainingMode mode) {
        if (this.currentMode != mode) {
            this.currentMode = mode;
            // Reset batch state so we don't carry stale counters across modes
            soloBatchCount = 0;
            soloBatchTotalMoves = 0;
            soloBatchAceCount = 0;
            batchSnapshot = null;
            bestAverageMovesToAce = Double.MAX_VALUE;
            System.out.println("BackgroundTrainer: Training mode switched to " + mode);
        }
    }

    private void trainingLoop() {
        while (running.get()) {
            try {
                if (currentMode == TrainingMode.STACK_FOCUS) {
                    // In STACK_FOCUS mode every single game is a stack-focused solo game.
                    // No multiplayer games, no solo-batch amplification — pure stack reward signal.
                    playStackFocusedGame();
                } else {
                    // STANDARD mode: every 3rd game (index 2 in 0,1,2 cycle) is a solo batch game
                    int cycle = gameCounter.getAndIncrement() % 3;
                    if (cycle == 2) {
                        runSoloBatchGame();
                    } else {
                        playOneGame();
                    }
                }
                gamesPlayed.incrementAndGet();

                if (gamesPlayed.get() % 50 == 0) {
                    System.out.println("BackgroundTrainer [" + currentMode + "]: " + gamesPlayed.get() + " games completed.");
                    // Brief pause every 50 games to reduce CPU pressure
                    Thread.sleep(100);
                    // Periodically persist the brain to disk
                    GlobalAi.save();
                }
            } catch (Exception e) {
                System.err.println("BackgroundTrainer: Error in game simulation: " + e.getMessage());
                try {
                    Thread.sleep(1000); // Back off on error
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Manages the solo game batch lifecycle.
     * - On the first call of a new batch, snapshots the current network weights.
     * - Plays one solo game and tracks moves-to-ace.
     * - On the 20th game, evaluates average performance vs the record and
     *   amplifies learned weight deltas by 1.25x per 1% improvement.
     */
    private void runSoloBatchGame() {
        // Start of a new batch?
        if (soloBatchCount == 0) {
            // Snapshot the network weights BEFORE training begins
            synchronized (GlobalAi.getInstance()) {
                batchSnapshot = GlobalAi.getInstance().deepCopy();
            }
            soloBatchTotalMoves = 0;
            soloBatchAceCount = 0;
        }

        // Play one solo game and capture how many moves it took
        int movesToAce = playOneSoloGame();
        soloBatchCount++;

        if (movesToAce > 0) {
            // Reached Ace — record the move count
            soloBatchTotalMoves += movesToAce;
            soloBatchAceCount++;
        }

        // End of batch?
        if (soloBatchCount >= SOLO_BATCH_SIZE) {
            soloBatchCount = 0;

            if (soloBatchAceCount > 0 && batchSnapshot != null) {
                double avgMoves = (double) soloBatchTotalMoves / soloBatchAceCount;

                if (bestAverageMovesToAce == Double.MAX_VALUE) {
                    // First successful batch — set the baseline
                    bestAverageMovesToAce = avgMoves;
                    System.out.println("BackgroundTrainer [SOLO]: Initial record set — avg moves to Ace: "
                            + String.format("%.1f", avgMoves) + " (" + soloBatchAceCount + "/" + SOLO_BATCH_SIZE + " reached Ace)");
                } else if (avgMoves < bestAverageMovesToAce) {
                    // Improvement! Calculate % reduction vs the record
                    double percentImprovement = ((bestAverageMovesToAce - avgMoves) / bestAverageMovesToAce) * 100.0;
                    // For every 1% reduction, multiply deltas by 1.25
                    // So total multiplier = 1.25 ^ percentImprovement
                    double multiplier = Math.pow(1.25, percentImprovement);

                    // Cap multiplier to prevent extreme weight explosions
                    multiplier = Math.min(multiplier, 10.0);

                    synchronized (GlobalAi.getInstance()) {
                        GlobalAi.getInstance().applyAmplifiedDeltas(batchSnapshot, multiplier);
                    }

                    System.out.println("BackgroundTrainer [SOLO]: NEW RECORD! avg moves " 
                            + String.format("%.1f", bestAverageMovesToAce) + " → " + String.format("%.1f", avgMoves)
                            + " (" + String.format("%.1f", percentImprovement) + "% faster, "
                            + String.format("%.2f", multiplier) + "x delta amplification, "
                            + soloBatchAceCount + "/" + SOLO_BATCH_SIZE + " reached Ace)");

                    bestAverageMovesToAce = avgMoves;
                }
                // If avgMoves >= record, do nothing — only reward improvements
            } else {
                System.out.println("BackgroundTrainer [SOLO]: Batch complete — no games reached Ace.");
            }

            batchSnapshot = null; // Release snapshot memory
        }
    }

    /**
     * Plays one complete CPU-vs-CPU game on a temporary GameState.
     * Uses the shared GlobalAi brain (synchronized) for decisions.
     * Trains per-move (intermediate rewards) and post-game (trajectory replay).
     */
    @SuppressWarnings("unchecked")
    private void playOneGame() {
        // --- Setup ---
        GameState sim = new GameState();
        sim.setPhase(GameState.Phase.PLAYING);
        initDeck(sim);

        // Randomly choose 2-6 CPU players per game for diversity
        java.util.Random rng = new java.util.Random();
        int cpuCount = 2 + rng.nextInt(5); // 2, 3, 4, 5, or 6

        for (int i = 0; i < cpuCount; i++) {
            sim.getPlayers().add(new Player("bg" + (i + 1), "BG-" + (i + 1), false));
        }

        // Deal starting stack cards
        for (Player p : sim.getPlayers()) {
            if (!sim.getDrawPile().isEmpty()) {
                Card startCard = sim.getDrawPile().pop();
                while ((startCard.getRank() == Card.Rank.ACE || startCard.getRank() == Card.Rank.JOKER)
                        && !sim.getDrawPile().isEmpty()) {
                    sim.getDrawPile().add(0, startCard);
                    startCard = sim.getDrawPile().pop();
                }
                p.getStack().add(startCard);
            }
        }

        // --- Fork per-player networks with unique noise ---
        // Each CPU gets its own mutated copy of the main brain for diverse play styles.
        // Training still targets the main GlobalAi, so learnings collapse back.
        NeuralNetwork[] playerBrains = new NeuralNetwork[cpuCount];
        synchronized (GlobalAi.getInstance()) {
            for (int i = 0; i < cpuCount; i++) {
                playerBrains[i] = GlobalAi.getInstance().deepCopy();
                // Linearly space mutation strength: 0.03 to 0.15 across players
                double strength = 0.03 + (0.12 * i / Math.max(1, cpuCount - 1));
                playerBrains[i].mutate(0.15, strength);
            }
        }

        // --- Per-player tracking ---
        // Full move history for end-of-game replay
        List<MoveRecord>[] moveHistory = new List[cpuCount];
        // Rolling window of the last 5 distance-to-Ace values for intermediate rewards
        LinkedList<Double>[] distanceWindow = new LinkedList[cpuCount];
        for (int i = 0; i < cpuCount; i++) {
            moveHistory[i] = new ArrayList<>();
            distanceWindow[i] = new LinkedList<>();
            // Seed the window with the player's starting distance
            distanceWindow[i].add(AiInputMapper.getDistanceToAce(sim.getPlayers().get(i)));
        }

        // --- Game Loop ---
        int turnCount = 0;
        while (!sim.isGameOver() && turnCount < MAX_TURNS_PER_GAME && running.get()) {
            turnCount++;
            Player current = sim.getCurrentPlayer();
            int playerIdx = sim.getPlayers().indexOf(current);

            // 1. Draw
            if (sim.getDrawPile().isEmpty()) {
                reshuffleDeck(sim);
            }
            if (sim.getDrawPile().isEmpty()) {
                break; // No cards left at all
            }
            Card drawn = sim.getDrawPile().pop();
            current.getHand().add(drawn);
            sim.setHasDrawn(true);

            // 2. Brain decision — use this player's FORKED network (not GlobalAi)
            List<Double> inputs = AiInputMapper.extractInputs(sim, current);
            List<Double> outputs = playerBrains[playerIdx].feedForward(inputs);

            // Capture opponent distances BEFORE action to check if we accidentally help them
            double[] oppDistBefore = new double[sim.getPlayers().size()];
            for (int i = 0; i < sim.getPlayers().size(); i++) {
                if (i != playerIdx) {
                    oppDistBefore[i] = AiInputMapper.getDistanceToAce(sim.getPlayers().get(i));
                }
            }

            // 3. Execute action
            /* ACTIONS: 0=PASS, 1=D-SKIP, 2=D-ATK, 3=D-NRM, 4-57=STACK Specific Card */
            boolean acted = false;
            int chosenActionIndex = 0; // The actual action we ended up taking

            // --- Multi-card stack loop ---
            // Keep stacking cards as long as the brain wants to and valid plays exist.
            boolean keepStacking = true;
            while (keepStacking && !sim.isGameOver()) {
                keepStacking = false; // Will be set to true again if we successfully stack

                // Re-run brain with fresh inputs after each card placed
                final List<Double> freshInputs = AiInputMapper.extractInputs(sim, current);
                final List<Double> freshOutputs = playerBrains[playerIdx].feedForward(freshInputs);
                inputs = freshInputs;
                outputs = freshOutputs;
                List<Integer> freshActions = new ArrayList<>();
                for (int i = 0; i < freshOutputs.size(); i++) freshActions.add(i);
                freshActions.sort((a, b) -> Double.compare(freshOutputs.get(b), freshOutputs.get(a)));

                for (int action : freshActions) {
                    if (action >= 4) { // STACK Specific Card
                        int targetCardIndex = action - 4;
                        int handIdx = -1;
                        for (int i = 0; i < current.getHand().size(); i++) {
                            if (AiInputMapper.getCardIndex(current.getHand().get(i)) == targetCardIndex) {
                                handIdx = i;
                                break;
                            }
                        }

                        if (handIdx != -1) {
                            Card card = current.getHand().get(handIdx);
                            Card top = current.getTopStack();
                            boolean valid = (top == null)
                                    ? (card.getRank() != Card.Rank.ACE && card.getRank() != Card.Rank.JOKER)
                                    : isSequenceValid(current, top, card);

                            if (valid) {
                                double distBefore = AiInputMapper.getDistanceToAce(current);

                                current.getHand().remove(handIdx);
                                current.getStack().add(card);
                                sim.setHasPlayedToStack(true);
                                acted = true;
                                chosenActionIndex = action;

                                // Joker auto-pick
                                if (card.getRank() == Card.Rank.JOKER) {
                                    Card below = current.getStack().size() >= 2
                                            ? current.getStack().get(current.getStack().size() - 2) : null;
                                    if (below != null) {
                                        int ord = below.getRank().ordinal();
                                        current.setJokerStackValue(ord < Card.Rank.values().length - 1
                                                ? Card.Rank.values()[ord + 1] : Card.Rank.SEVEN);
                                    }
                                }

                                // Immediate stack-progress reward
                                double distAfterStack = AiInputMapper.getDistanceToAce(current);
                                double stackImprovement = distBefore - distAfterStack;
                                if (stackImprovement > 0) {
                                    double stackReward = 0.5 * stackImprovement;
                                    if (distAfterStack <= 2) stackReward *= 2.0;
                                    GlobalAi.trainSafe(inputs, chosenActionIndex, stackReward);
                                }

                                // Win check
                                if (card.getRank() == Card.Rank.ACE) {
                                    sim.setGameOver(true);
                                    sim.setWinner(current);
                                    double distNow = AiInputMapper.getDistanceToAce(current);
                                    moveHistory[playerIdx].add(new MoveRecord(inputs, chosenActionIndex, distNow));
                                }

                                keepStacking = !sim.isGameOver(); // Continue stacking if game not over
                                break; // re-enter while loop
                            }
                        }
                    } else if (action >= 1 && action <= 3) { // DISCARD
                        if (!sim.isHasPlayedToStack()) {
                            int idx = findBestDiscard(current);
                            if (idx != -1) {
                                Card card = current.getHand().remove(idx);
                                current.getDiscardPile().add(card);
                                acted = true;
                                chosenActionIndex = action;
                                applySimpleEffect(sim, current, card);
                            }
                        }
                        break; // Discard ends the action phase
                    } else if (action == 0) { // PASS
                        if (sim.isHasPlayedToStack()) {
                            acted = true;
                            chosenActionIndex = action;
                        }
                        break; // Pass ends the action phase
                    }
                }
            }

            // Fallback: discard something or pass if network completely failed to pick a valid move
            if (!acted && !sim.isGameOver()) {
                if (!sim.isHasPlayedToStack() && !current.getHand().isEmpty()) {
                    int idx = findBestDiscard(current);
                    if (idx != -1) {
                        Card card = current.getHand().remove(idx);
                        current.getDiscardPile().add(card);
                        applySimpleEffect(sim, current, card);
                        chosenActionIndex = 3; // NRM discard fallback
                    }
                } else {
                    chosenActionIndex = 0; // PASS fallback
                }
            }

            if (sim.isGameOver()) break; // Inner break left loop early

            // 4. Measure distance to Ace AFTER this move
            double distNow = AiInputMapper.getDistanceToAce(current);

            // Calculate if we helped any opponent
            double oppAdvancementPenalty = 0.0;
            for (int i = 0; i < sim.getPlayers().size(); i++) {
                if (i != playerIdx) {
                    double distAfter = AiInputMapper.getDistanceToAce(sim.getPlayers().get(i));
                    double oppProgress = oppDistBefore[i] - distAfter; // Positive means they got closer
                    if (oppProgress > 0) {
                        oppAdvancementPenalty += oppProgress;
                    }
                }
            }

            // Record the move
            moveHistory[playerIdx].add(new MoveRecord(inputs, chosenActionIndex, distNow));

            // Update rolling distance window
            LinkedList<Double> window = distanceWindow[playerIdx];
            window.addLast(distNow);
            if (window.size() > 11) { // Keep 11 entries: current move + up to 10 previous moves
                window.removeFirst();
            }

            // 5. Per-move intermediate training — pure distance-to-Ace progress only
            if (window.size() >= 2) {
                double avgDist = window.stream().mapToDouble(Double::doubleValue).average().orElse(distNow);
                double progress = avgDist - distNow; // Positive = got closer to Ace

                if (progress > 0) {
                    double reward = Math.min(0.3 * progress, 3.0);
                    if (distNow <= 2) reward *= 2.0; // Proximity bonus near Ace
                    GlobalAi.trainSafe(inputs, chosenActionIndex, reward);
                }
                // No reward for no change, no penalty for moving away — zero signal keeps it clean
            }

            // Next turn
            sim.nextTurn();
        }

        // --- End-of-game: train on full trajectory ---
        if (sim.isGameOver() && sim.getWinner() != null) {
            int winnerIdx = sim.getPlayers().indexOf(sim.getWinner());

            synchronized (GlobalAi.getInstance()) {
                // Replay ALL moves for each player
                for (int pIdx = 0; pIdx < cpuCount; pIdx++) {
                    List<MoveRecord> history = moveHistory[pIdx];
                    if (history.isEmpty()) continue;

                    boolean isWinner = (pIdx == winnerIdx);
                    int totalMoves = history.size();

                    for (int m = 0; m < totalMoves; m++) {
                        MoveRecord rec = history.get(m);

                        // Decay factor: most recent move = 1.0, oldest = 0.3
                        // Linear decay from 0.3 to 1.0 based on position in history
                        double recency = (totalMoves == 1) ? 1.0
                                : 0.3 + 0.7 * ((double) m / (totalMoves - 1));

                        double reward;
                        if (isWinner) {
                            // Winner: reward scales from 3.0 (oldest) to 10.0 (newest)
                            reward = 3.0 + 7.0 * recency;
                        } else {
                            // Loser: NEGATIVE reward — recent losing moves punished harder
                            // Scales from -0.5 (oldest, mild) to -3.0 (newest, harsh)
                            reward = -(0.5 + 2.5 * recency);
                        }

                        GlobalAi.trainSafe(rec.inputs, rec.actionIndex, reward);
                    }
                }

                // Small mutation for exploration
                GlobalAi.mutateSafe(0.05, 0.02);
            }
        }
    }

    /**
     * Plays one solo (one-player) training game with no opponents.
     * The AI draws cards and tries to build its stack to Ace as efficiently
     * as possible. Uses the same per-move rewards as normal games but with
     * no opponent-awareness. End-of-game trajectory replay uses standard
     * win/loss rewards. Efficiency bonuses are handled at the batch level
     * by {@link #runSoloBatchGame()}.
     *
     * @return number of moves to reach Ace (positive), or -1 if Ace was not reached
     */
    @SuppressWarnings("unchecked")
    private int playOneSoloGame() {
        // --- Setup ---
        GameState sim = new GameState();
        sim.setPhase(GameState.Phase.PLAYING);
        initDeck(sim);

        // Single player
        sim.getPlayers().add(new Player("solo", "SOLO", false));
        Player solo = sim.getPlayers().get(0);

        // Deal starting stack card
        if (!sim.getDrawPile().isEmpty()) {
            Card startCard = sim.getDrawPile().pop();
            while ((startCard.getRank() == Card.Rank.ACE || startCard.getRank() == Card.Rank.JOKER)
                    && !sim.getDrawPile().isEmpty()) {
                sim.getDrawPile().add(0, startCard);
                startCard = sim.getDrawPile().pop();
            }
            solo.getStack().add(startCard);
        }

        // Fork a mutated brain for exploration
        NeuralNetwork soloBrain;
        synchronized (GlobalAi.getInstance()) {
            soloBrain = GlobalAi.getInstance().deepCopy();
            soloBrain.mutate(0.15, 0.08);
        }

        // Per-move tracking
        List<MoveRecord> moveHistory = new ArrayList<>();
        LinkedList<Double> distanceWindow = new LinkedList<>();
        distanceWindow.add(AiInputMapper.getDistanceToAce(solo));

        // --- Game Loop ---
        int turnCount = 0;
        boolean reachedAce = false;

        while (turnCount < MAX_TURNS_PER_GAME && running.get()) {
            turnCount++;

            // 1. Draw
            if (sim.getDrawPile().isEmpty()) {
                reshuffleDeck(sim);
            }
            if (sim.getDrawPile().isEmpty()) {
                break; // No cards left
            }
            Card drawn = sim.getDrawPile().pop();
            solo.getHand().add(drawn);
            sim.setHasDrawn(true);

            // 2. Brain decision
            List<Double> inputs = AiInputMapper.extractInputs(sim, solo);
            List<Double> outputs = soloBrain.feedForward(inputs);

            // 3. Execute action — multi-card stack loop, then discard/pass
            boolean acted = false;
            int chosenActionIndex = 0;

            // Keep stacking cards as long as the brain chooses to and valid plays remain
            boolean keepStacking = true;
            while (keepStacking && !reachedAce) {
                keepStacking = false;

                final List<Double> freshInputs = AiInputMapper.extractInputs(sim, solo);
                final List<Double> freshOutputs = soloBrain.feedForward(freshInputs);
                inputs = freshInputs;
                outputs = freshOutputs;
                List<Integer> freshActions = new ArrayList<>();
                for (int i = 0; i < freshOutputs.size(); i++) freshActions.add(i);
                freshActions.sort((a, b) -> Double.compare(freshOutputs.get(b), freshOutputs.get(a)));

                for (int action : freshActions) {
                    if (action >= 4) { // STACK Specific Card
                        int targetCardIndex = action - 4;
                        int handIdx = -1;
                        for (int i = 0; i < solo.getHand().size(); i++) {
                            if (AiInputMapper.getCardIndex(solo.getHand().get(i)) == targetCardIndex) {
                                handIdx = i;
                                break;
                            }
                        }

                        if (handIdx != -1) {
                            Card card = solo.getHand().get(handIdx);
                            Card top = solo.getTopStack();
                            boolean valid = (top == null)
                                    ? (card.getRank() != Card.Rank.ACE && card.getRank() != Card.Rank.JOKER)
                                    : isSequenceValid(solo, top, card);

                            if (valid) {
                                double distBefore = AiInputMapper.getDistanceToAce(solo);

                                solo.getHand().remove(handIdx);
                                solo.getStack().add(card);
                                sim.setHasPlayedToStack(true);
                                acted = true;
                                chosenActionIndex = action;

                                // Joker auto-pick
                                if (card.getRank() == Card.Rank.JOKER) {
                                    Card below = solo.getStack().size() >= 2
                                            ? solo.getStack().get(solo.getStack().size() - 2) : null;
                                    if (below != null) {
                                        int ord = below.getRank().ordinal();
                                        solo.setJokerStackValue(ord < Card.Rank.values().length - 1
                                                ? Card.Rank.values()[ord + 1] : Card.Rank.SEVEN);
                                    }
                                }

                                double distAfterStack = AiInputMapper.getDistanceToAce(solo);
                                double stackImprovement = distBefore - distAfterStack;
                                if (stackImprovement > 0) {
                                    double stackReward = 0.5 * stackImprovement;
                                    if (distAfterStack <= 2) stackReward *= 2.0;
                                    GlobalAi.trainSafe(inputs, chosenActionIndex, stackReward);
                                }

                                if (card.getRank() == Card.Rank.ACE) {
                                    reachedAce = true;
                                    double distNow = AiInputMapper.getDistanceToAce(solo);
                                    moveHistory.add(new MoveRecord(inputs, chosenActionIndex, distNow));
                                } else {
                                    keepStacking = true; // Try to stack another card
                                }
                                break;
                            }
                        }
                    } else if (action >= 1 && action <= 3) { // DISCARD
                        if (!sim.isHasPlayedToStack()) {
                            int idx = findBestDiscard(solo);
                            if (idx != -1) {
                                Card card = solo.getHand().remove(idx);
                                solo.getDiscardPile().add(card);
                                acted = true;
                                chosenActionIndex = action;
                                applySoloEffect(sim, solo, card);
                            }
                        }
                        break;
                    } else if (action == 0) { // PASS
                        if (sim.isHasPlayedToStack()) {
                            acted = true;
                            chosenActionIndex = action;
                        }
                        break;
                    }
                }
            }

            // Fallback
            if (!acted && !reachedAce) {
                if (!sim.isHasPlayedToStack() && !solo.getHand().isEmpty()) {
                    int idx = findBestDiscard(solo);
                    if (idx != -1) {
                        Card card = solo.getHand().remove(idx);
                        solo.getDiscardPile().add(card);
                        applySoloEffect(sim, solo, card);
                        chosenActionIndex = 3;
                    }
                } else {
                    chosenActionIndex = 0;
                }
            }

            if (reachedAce) break;

            // 4. Measure distance to Ace AFTER this move
            double distNow = AiInputMapper.getDistanceToAce(solo);

            // Record the move
            moveHistory.add(new MoveRecord(inputs, chosenActionIndex, distNow));

            // Update rolling distance window
            distanceWindow.addLast(distNow);
            if (distanceWindow.size() > 11) {
                distanceWindow.removeFirst();
            }

            // 5. Per-move intermediate training (no opponent awareness needed)
            if (distanceWindow.size() >= 2) {
                double sum = 0;
                for (int i = 0; i < distanceWindow.size(); i++) {
                    sum += distanceWindow.get(i);
                }
                double avgDist = sum / distanceWindow.size();
                double progress = avgDist - distNow; // Positive = got closer

                double reward;
                if (progress > 0) {
                    reward = Math.min(0.3 * progress, 3.0);
                } else if (progress == 0) {
                    reward = 0.0;
                } else {
                    // Got further away — mild penalty
                    reward = -0.1;
                }

                GlobalAi.trainSafe(inputs, chosenActionIndex, reward);
            }

            // Reset turn flags for next iteration
            sim.setHasDrawn(false);
            sim.setHasPlayedToStack(false);
        }

        // --- End-of-solo-game: trajectory replay (same as normal game win/loss) ---
        int totalMoves = moveHistory.size();
        if (!moveHistory.isEmpty()) {
            synchronized (GlobalAi.getInstance()) {
                if (reachedAce) {
                    // Replay all moves with win reward
                    for (int m = 0; m < totalMoves; m++) {
                        MoveRecord rec = moveHistory.get(m);
                        double recency = (totalMoves == 1) ? 1.0
                                : 0.3 + 0.7 * ((double) m / (totalMoves - 1));
                        double reward = 3.0 + 7.0 * recency;
                        GlobalAi.trainSafe(rec.inputs, rec.actionIndex, reward);
                    }
                } else {
                    // Didn't reach Ace — penalize (milder than losing to an opponent)
                    for (int m = 0; m < totalMoves; m++) {
                        MoveRecord rec = moveHistory.get(m);
                        double recency = (totalMoves == 1) ? 1.0
                                : 0.3 + 0.7 * ((double) m / (totalMoves - 1));
                        double reward = -(0.3 + 1.5 * recency);
                        GlobalAi.trainSafe(rec.inputs, rec.actionIndex, reward);
                    }
                }

                // Small mutation for exploration
                GlobalAi.mutateSafe(0.05, 0.02);
            }
        }

        // Return moves count (positive if reached Ace, -1 if not)
        return reachedAce ? totalMoves : -1;
    }

    // =========================================================================
    // STACK FOCUS TRAINING MODE
    // =========================================================================
    /**
     * Plays one solo game that ONLY and HEAVILY rewards placing cards onto the
     * player's own stack that reduce distance to Ace. All other signals
     * (discard rewards, opponent penalties, neutral progress rewards) are
     * stripped out so the network gets an extremely clean gradient:
     *   "stack a card that moves you closer → big reward, everything else → nothing".
     *
     * Reward structure:
     *   Stack card that brings dist closer:  base 3.0 * improvement (min 3.0, max 15.0)
     *                                        ×2 bonus when dist <= 3 (approaching finish)
     *                                        ×3 bonus when dist <= 1 (one step from Ace)
     *   Winning (Ace on top of stack):       immediate +50, then full trajectory replay
     *                                        with rewards from 10.0 (oldest) to 30.0 (newest)
     *   Stacking a card with no improvement: 0
     *   Discard / Pass:                      0  (no signal — we don't care about these)
     *   Did NOT reach Ace by turn limit:     mild trajectory penalty (-0.2 to -1.0)
     *
     * This gives the network a very strong, noise-free signal to learn that
     * "putting the right card on my stack" is the most important thing.
     */
    @SuppressWarnings("unchecked")
    private void playStackFocusedGame() {
        // --- Setup (identical to solo game) ---
        GameState sim = new GameState();
        sim.setPhase(GameState.Phase.PLAYING);
        initDeck(sim);

        sim.getPlayers().add(new Player("sf", "SF", false));
        Player solo = sim.getPlayers().get(0);

        // Deal starting stack card (no Aces/Jokers)
        if (!sim.getDrawPile().isEmpty()) {
            Card startCard = sim.getDrawPile().pop();
            while ((startCard.getRank() == Card.Rank.ACE || startCard.getRank() == Card.Rank.JOKER)
                    && !sim.getDrawPile().isEmpty()) {
                sim.getDrawPile().add(0, startCard);
                startCard = sim.getDrawPile().pop();
            }
            solo.getStack().add(startCard);
        }

        // Fork a lightly mutated brain — enough to explore without drowning the signal
        NeuralNetwork brain;
        synchronized (GlobalAi.getInstance()) {
            brain = GlobalAi.getInstance().deepCopy();
            brain.mutate(0.10, 0.05); // Small mutation so we still explore
        }

        List<MoveRecord> moveHistory = new ArrayList<>();
        int turnCount = 0;
        boolean reachedAce = false;

        // --- Game loop ---
        while (turnCount < MAX_TURNS_PER_GAME && running.get()) {
            turnCount++;

            // 1. Draw
            if (sim.getDrawPile().isEmpty()) reshuffleDeck(sim);
            if (sim.getDrawPile().isEmpty()) break;
            Card drawn = sim.getDrawPile().pop();
            solo.getHand().add(drawn);
            sim.setHasDrawn(true);

            // 2. Brain decision
            List<Double> inputs = AiInputMapper.extractInputs(sim, solo);
            List<Double> outputs = brain.feedForward(inputs);

            // 3. Keep stacking cards as long as the brain picks stack actions and valid plays exist.
            //    Discards/pass receive ZERO training signal — pure stack focus.
            boolean stackedThisTurn = false;
            int chosenActionIndex = 0;

            boolean keepStacking = true;
            while (keepStacking && !reachedAce) {
                keepStacking = false;

                // Re-run brain with fresh state after each card placed
                final List<Double> freshInputs = AiInputMapper.extractInputs(sim, solo);
                final List<Double> freshOutputs = brain.feedForward(freshInputs);
                inputs = freshInputs;
                outputs = freshOutputs;
                List<Integer> freshActions = new ArrayList<>();
                for (int i = 0; i < freshOutputs.size(); i++) freshActions.add(i);
                freshActions.sort((a, b) -> Double.compare(freshOutputs.get(b), freshOutputs.get(a)));

                for (int action : freshActions) {
                    if (action >= 4) { // STACK Specific Card
                        int targetCardIndex = action - 4;
                        int handIdx = -1;
                        for (int i = 0; i < solo.getHand().size(); i++) {
                            if (AiInputMapper.getCardIndex(solo.getHand().get(i)) == targetCardIndex) {
                                handIdx = i;
                                break;
                            }
                        }
                        if (handIdx == -1) continue;

                        Card card = solo.getHand().get(handIdx);
                        Card top = solo.getTopStack();
                        boolean valid = (top == null)
                                ? (card.getRank() != Card.Rank.ACE && card.getRank() != Card.Rank.JOKER)
                                : isSequenceValid(solo, top, card);
                        if (!valid) continue;

                        double distBefore = AiInputMapper.getDistanceToAce(solo);

                        solo.getHand().remove(handIdx);
                        solo.getStack().add(card);
                        sim.setHasPlayedToStack(true);
                        stackedThisTurn = true;
                        chosenActionIndex = action;

                        // Joker auto-pick
                        if (card.getRank() == Card.Rank.JOKER) {
                            Card below = solo.getStack().size() >= 2
                                    ? solo.getStack().get(solo.getStack().size() - 2) : null;
                            if (below != null) {
                                int ord = below.getRank().ordinal();
                                solo.setJokerStackValue(ord < Card.Rank.values().length - 1
                                        ? Card.Rank.values()[ord + 1] : Card.Rank.SEVEN);
                            }
                        }

                        double distAfter = AiInputMapper.getDistanceToAce(solo);
                        double improvement = distBefore - distAfter;

                        if (improvement > 0) {
                            double stackReward = Math.min(3.0 * improvement, 15.0);
                            if (distAfter <= 1) stackReward *= 3.0;
                            else if (distAfter <= 3) stackReward *= 2.0;
                            GlobalAi.trainSafe(inputs, chosenActionIndex, stackReward);
                        }

                        if (card.getRank() == Card.Rank.ACE) {
                            reachedAce = true;
                            GlobalAi.trainSafe(inputs, chosenActionIndex, 50.0);
                            moveHistory.add(new MoveRecord(inputs, chosenActionIndex, 0.0));
                        } else {
                            keepStacking = true; // Try to chain another card
                        }
                        break;
                    }
                    // Discard/pass: break without training — intentionally silent
                    break;
                }
            }

            if (reachedAce) break;

            // Fallback: if couldn't stack, silently discard the worst card (no training signal)

            if (!stackedThisTurn && !solo.getHand().isEmpty()) {
                int idx = findBestDiscard(solo);
                if (idx != -1) {
                    Card card = solo.getHand().remove(idx);
                    solo.getDiscardPile().add(card);
                    applySoloEffect(sim, solo, card);
                }
            }

            // Record move for trajectory replay (even non-stack moves)
            double distNow = AiInputMapper.getDistanceToAce(solo);
            moveHistory.add(new MoveRecord(inputs, chosenActionIndex, distNow));

            // Reset turn flags
            sim.setHasDrawn(false);
            sim.setHasPlayedToStack(false);
        }

        // --- End-of-game trajectory replay ---
        int totalMoves = moveHistory.size();
        if (!moveHistory.isEmpty()) {
            synchronized (GlobalAi.getInstance()) {
                if (reachedAce) {
                    // Large win rewards, recency-weighted
                    // Range: 10.0 (oldest) → 30.0 (newest)
                    for (int m = 0; m < totalMoves; m++) {
                        MoveRecord rec = moveHistory.get(m);
                        double recency = (totalMoves == 1) ? 1.0
                                : 0.3 + 0.7 * ((double) m / (totalMoves - 1));
                        double reward = 10.0 + 20.0 * recency;
                        GlobalAi.trainSafe(rec.inputs, rec.actionIndex, reward);
                    }
                    System.out.println("BackgroundTrainer [STACK_FOCUS]: WIN! Reached Ace in " + totalMoves + " moves.");
                } else {
                    // Did NOT reach Ace — mild penalty so the network doesn't ignore the goal
                    for (int m = 0; m < totalMoves; m++) {
                        MoveRecord rec = moveHistory.get(m);
                        double recency = (totalMoves == 1) ? 1.0
                                : 0.3 + 0.7 * ((double) m / (totalMoves - 1));
                        double reward = -(0.2 + 0.8 * recency);
                        GlobalAi.trainSafe(rec.inputs, rec.actionIndex, reward);
                    }
                }
                // Small mutation to keep exploring
                GlobalAi.mutateSafe(0.04, 0.015);
            }
        }
    }

    /**
     * Solo-mode discard effects: only applies effects that affect the player themselves.
     * Opponent-targeting effects (7, 8, 10) are no-ops in solo.
     */
    private void applySoloEffect(GameState sim, Player source, Card card) {
        switch (card.getRank()) {
            case THREE: // Self draws 3
                drawN(sim, source, 3);
                break;
            case FIVE: // Self draws 1
                drawN(sim, source, 1);
                break;
            case NINE: // Self draws 1
                drawN(sim, source, 1);
                break;
            case QUEEN: { // Fortune Seer: draw 3, keep best
                List<Card> options = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    if (sim.getDrawPile().isEmpty()) reshuffleDeck(sim);
                    if (!sim.getDrawPile().isEmpty()) options.add(sim.getDrawPile().pop());
                }
                if (!options.isEmpty()) {
                    Card best = chooseBestCardForStack(source, options);
                    source.getHand().add(best);
                    options.remove(best);
                    for (Card c : options) sim.getDrawPile().push(c);
                }
                break;
            }
            case JOKER: { // Joker discard: move cards from discard back to stack
                if (!source.getDiscardPile().isEmpty()) {
                    int moved = 0;
                    for (int attempt = 0; attempt < source.getDiscardPile().size() && moved < 2; attempt++) {
                        Card candidate = source.getDiscardPile().get(attempt);
                        Card stackTop = source.getTopStack();
                        boolean fits = (stackTop == null)
                                ? (candidate.getRank() != Card.Rank.ACE && candidate.getRank() != Card.Rank.JOKER)
                                : isSequenceValid(source, stackTop, candidate);
                        if (fits) {
                            source.getDiscardPile().remove(attempt);
                            source.getStack().add(candidate);
                            moved++;
                            attempt--;
                        }
                    }
                    if (moved == 0 && !source.getDiscardPile().isEmpty()) {
                        Card rescued = source.getDiscardPile().remove(source.getDiscardPile().size() - 1);
                        source.getHand().add(rescued);
                    }
                }
                break;
            }
            // 4/J (skip), K (reverse), 2 (next draws 2), 6 (skip),
            // 7 (sabotage), 8 (steal), 10 (strip) — no effect in solo
            default:
                break;
        }
    }

    // --- Simplified game helpers (no UI, no delays) ---

    private void initDeck(GameState game) {
        game.getDrawPile().clear();
        for (Card.Suit suit : Card.Suit.values()) {
            if (suit == Card.Suit.JOKER) continue;
            for (Card.Rank rank : Card.Rank.values()) {
                if (rank == Card.Rank.JOKER) continue;
                game.getDrawPile().add(new Card(suit, rank));
            }
        }
        game.getDrawPile().add(new Card(Card.Suit.JOKER, Card.Rank.JOKER));
        game.getDrawPile().add(new Card(Card.Suit.JOKER, Card.Rank.JOKER));
        Collections.shuffle(game.getDrawPile());
        if (!game.getDrawPile().isEmpty()) {
            // Ensure bottom card is not a Joker (it decides which 7s are active)
            while (game.getDrawPile().get(0).getRank() == Card.Rank.JOKER && game.getDrawPile().size() > 1) {
                Collections.shuffle(game.getDrawPile());
            }
            game.setCardUnderDeck(game.getDrawPile().get(0));
        }
    }

    private void reshuffleDeck(GameState sim) {
        // Collect all discard piles back into the draw pile
        int totalDiscards = 0;
        for (Player p : sim.getPlayers()) {
            totalDiscards += p.getDiscardPile().size();
            sim.getDrawPile().addAll(p.getDiscardPile());
            p.getDiscardPile().clear();
        }

        if (totalDiscards < 20) {
            // Add a fresh 54-card deck
            for (Card.Suit suit : Card.Suit.values()) {
                if (suit == Card.Suit.JOKER) continue;
                for (Card.Rank rank : Card.Rank.values()) {
                    if (rank == Card.Rank.JOKER) continue;
                    sim.getDrawPile().add(new Card(suit, rank));
                }
            }
            sim.getDrawPile().add(new Card(Card.Suit.JOKER, Card.Rank.JOKER));
            sim.getDrawPile().add(new Card(Card.Suit.JOKER, Card.Rank.JOKER));
        }

        Collections.shuffle(sim.getDrawPile());
    }

    private int findBestPlayToStack(GameState sim, Player p) {
        Card top = p.getTopStack();
        int bestIdx = -1;
        int bestVal = -1;
        for (int i = 0; i < p.getHand().size(); i++) {
            Card c = p.getHand().get(i);
            boolean valid;
            if (top == null) {
                valid = (c.getRank() != Card.Rank.ACE && c.getRank() != Card.Rank.JOKER);
            } else {
                valid = isSequenceValid(p, top, c);
            }
            if (valid) {
                int val = getCardValue(c);
                if (val > bestVal) {
                    bestVal = val;
                    bestIdx = i;
                }
            }
        }
        return bestIdx;
    }

    private boolean isSequenceValid(Player p, Card top, Card card) {
        if (card.getRank() == Card.Rank.JOKER) return true;
        if (top.getRank() == Card.Rank.JOKER) {
            Card.Rank jokerValue = p.getJokerStackValue();
            if (jokerValue == null) return true;
            if (jokerValue == Card.Rank.TWO && card.getRank() == Card.Rank.ACE) return true;
            return Math.abs(card.getRank().ordinal() - jokerValue.ordinal()) == 1;
        }
        if (top.getRank() == Card.Rank.TWO && card.getRank() == Card.Rank.ACE) return true;
        return Math.abs(card.getRank().ordinal() - top.getRank().ordinal()) == 1;
    }

    private int findBestDiscard(Player p) {
        if (p.getHand().isEmpty()) return -1;
        int bestIdx = 0;
        int bestVal = getCardValue(p.getHand().get(0));
        for (int i = 1; i < p.getHand().size(); i++) {
            int val = getCardValue(p.getHand().get(i));
            if (val > bestVal) {
                bestVal = val;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private int getCardValue(Card c) {
        if (c == null) return 0;
        if (c.getRank() == Card.Rank.JOKER) return 15;
        if (c.getRank() == Card.Rank.ACE) return 14;
        return c.getRank().ordinal() + 1;
    }

    /**
     * Applies card discard effects for background self-play games.
     * Now includes full simulation of interactive effects (7, 8, 10, Q, Joker)
     * so the network can learn their strategic value against opponents.
     */
    private void applySimpleEffect(GameState sim, Player source, Card card) {
        switch (card.getRank()) {

            // --- Instant effects (same as before) ---
            case FOUR:
            case JACK:
                sim.skipPlayers(1);
                break;
            case KING:
                sim.reverseDirection();
                break;
            case TWO: // Next player draws 2
                Player nextForTwo = getNextPlayer(sim);
                if (nextForTwo != null) drawN(sim, nextForTwo, 2);
                break;
            case THREE: // Self draws 3
                drawN(sim, source, 3);
                break;
            case FIVE: // Self draws 1
                drawN(sim, source, 1);
                break;
            case SIX: // Skip next
                sim.skipPlayers(1);
                break;
            case NINE: // Self draws 1 (per rules)
                drawN(sim, source, 1);
                break;

            // --- Interactive effects: simulated heuristically ---

            case SEVEN: {
                // Sabotage: put a card from hand onto the opponent's stack
                // Target: opponent that is closest to Ace (most dangerous)
                Player target = getMostDangerousOpponent(sim, source);
                if (target != null && !source.getHand().isEmpty()) {
                    // Pick the worst card for our hand to give (highest distance filler)
                    // — give a card that disrupts the target's sequence most
                    int worstIdx = findWorstCardToGive(source, target);
                    if (worstIdx >= 0) {
                        Card given = source.getHand().remove(worstIdx);
                        target.getStack().add(given);
                    }
                }
                break;
            }

            case EIGHT: {
                // Steal: take top card from the opponent with the largest stack
                Player richTarget = getLargestStackOpponent(sim, source);
                if (richTarget != null && richTarget.getStack().size() > 1) {
                    Card stolen = richTarget.getStack().remove(richTarget.getStack().size() - 1);
                    source.getHand().add(stolen);
                }
                break;
            }

            case TEN: {
                // Strip: take top 3 cards from the most-advanced opponent's stack (needs 4+)
                Player advancedTarget = getMostAdvancedOpponent(sim, source);
                if (advancedTarget != null && advancedTarget.getStack().size() >= 4) {
                    for (int i = 0; i < 3; i++) {
                        Card taken = advancedTarget.getStack().remove(advancedTarget.getStack().size() - 1);
                        source.getHand().add(taken);
                    }
                }
                break;
            }

            case QUEEN: {
                // Fortune Seer: draw 3, keep the one that best improves distance to Ace
                List<Card> options = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    if (sim.getDrawPile().isEmpty()) reshuffleDeck(sim);
                    if (!sim.getDrawPile().isEmpty()) options.add(sim.getDrawPile().pop());
                }
                if (!options.isEmpty()) {
                    // Pick the card whose rank is closest to the top of our stack
                    Card best = chooseBestCardForStack(source, options);
                    source.getHand().add(best);
                    options.remove(best);
                    // Return rest to deck
                    for (Card c : options) sim.getDrawPile().push(c);
                }
                break;
            }

            case JOKER: {
                // Joker discard: move up to 2 cards from our discard back to stack,
                // or 1 card from discard to hand. Heuristic: prefer stack moves.
                if (!source.getDiscardPile().isEmpty()) {
                    int moved = 0;
                    // Try to move cards that fit the current stack sequence
                    for (int attempt = 0; attempt < source.getDiscardPile().size() && moved < 2; attempt++) {
                        Card candidate = source.getDiscardPile().get(attempt);
                        Card stackTop = source.getTopStack();
                        boolean fits = (stackTop == null)
                                ? (candidate.getRank() != Card.Rank.ACE && candidate.getRank() != Card.Rank.JOKER)
                                : isSequenceValid(source, stackTop, candidate);
                        if (fits) {
                            source.getDiscardPile().remove(attempt);
                            source.getStack().add(candidate);
                            moved++;
                            attempt--; // re-check same index after removal
                        }
                    }
                    // If couldn't move to stack, pull best card to hand
                    if (moved == 0 && !source.getDiscardPile().isEmpty()) {
                        Card rescued = source.getDiscardPile().remove(source.getDiscardPile().size() - 1);
                        source.getHand().add(rescued);
                    }
                }
                break;
            }

            default:
                break;
        }
    }

    /** Opponent closest to Ace (most dangerous). */
    private Player getMostDangerousOpponent(GameState sim, Player self) {
        Player best = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : sim.getPlayers()) {
            if (p == self) continue;
            double d = AiInputMapper.getDistanceToAce(p);
            if (d < minDist) { minDist = d; best = p; }
        }
        return best;
    }

    /** Opponent that is furthest from Ace (largest stack size used as proxy). */
    private Player getLargestStackOpponent(GameState sim, Player self) {
        Player best = null;
        int maxStack = 0;
        for (Player p : sim.getPlayers()) {
            if (p == self) continue;
            if (p.getStack().size() > maxStack) { maxStack = p.getStack().size(); best = p; }
        }
        return best;
    }

    /** Opponent whose stack top is closest to Ace (most advanced). */
    private Player getMostAdvancedOpponent(GameState sim, Player self) {
        Player best = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : sim.getPlayers()) {
            if (p == self) continue;
            if (p.getStack().size() < 4) continue; // TEN needs 4+ cards
            double d = AiInputMapper.getDistanceToAce(p);
            if (d < minDist) { minDist = d; best = p; }
        }
        return best;
    }

    /**
     * Finds the index of the card in the source's hand that is worst for them
     * (furthest from their current stack sequence) to give to the target.
     */
    private int findWorstCardToGive(Player source, Player target) {
        int worstIdx = -1;
        int worstVal = Integer.MAX_VALUE;
        Card sourceTop = source.getTopStack();
        for (int i = 0; i < source.getHand().size(); i++) {
            Card c = source.getHand().get(i);
            // Skip Ace — can't place on someone's stack via 7
            if (c.getRank() == Card.Rank.ACE) continue;
            // Prefer cards that don't fit our own stack (useless to us)
            boolean fitsUs = (sourceTop != null) && isSequenceValid(source, sourceTop, c);
            int val = fitsUs ? 100 : getCardValue(c); // High val = prefer to keep
            if (val < worstVal) { worstVal = val; worstIdx = i; }
        }
        return worstIdx;
    }

    /**
     * From a list of candidate cards, picks the one that best continues
     * the player's current stack sequence (or has highest raw value if none fit).
     */
    private Card chooseBestCardForStack(Player p, List<Card> options) {
        Card bestFit = null;
        Card bestAny = options.get(0);
        int bestFitVal = -1;
        int bestAnyVal = getCardValue(options.get(0));
        Card top = p.getTopStack();
        for (Card c : options) {
            int val = getCardValue(c);
            if (val > bestAnyVal) { bestAnyVal = val; bestAny = c; }
            if (top != null && isSequenceValid(p, top, c) && val > bestFitVal) {
                bestFitVal = val; bestFit = c;
            }
        }
        return (bestFit != null) ? bestFit : bestAny;
    }

    private Player getNextPlayer(GameState sim) {
        int idx = (sim.getCurrentPlayerIndex() + sim.getPlayDirection() + sim.getPlayers().size())
                % sim.getPlayers().size();
        return sim.getPlayers().get(idx);
    }

    private void drawN(GameState sim, Player p, int n) {
        for (int i = 0; i < n; i++) {
            if (sim.getDrawPile().isEmpty()) reshuffleDeck(sim);
            if (!sim.getDrawPile().isEmpty()) {
                p.getHand().add(sim.getDrawPile().pop());
            }
        }
    }

    /**
     * Returns the minimum distance-to-Ace among all opponents of the given player.
     */
    private double getClosestOpponentDistance(GameState sim, int playerIdx) {
        double closest = 14.0;
        for (int i = 0; i < sim.getPlayers().size(); i++) {
            if (i == playerIdx) continue;
            double d = AiInputMapper.getDistanceToAce(sim.getPlayers().get(i));
            closest = Math.min(closest, d);
        }
        return closest;
    }
}
