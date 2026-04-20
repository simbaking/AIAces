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
    private static final int MAX_TURNS_PER_GAME = 500; // Safety limit

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
            System.out.println("BackgroundTrainer: Starting self-play training...");
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

    private void trainingLoop() {
        while (running.get()) {
            try {
                playOneGame();
                gamesPlayed.incrementAndGet();

                if (gamesPlayed.get() % 50 == 0) {
                    System.out.println("BackgroundTrainer: " + gamesPlayed.get() + " games completed.");
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

            // Sort actions by AI confidence
            List<Integer> sortedActions = new ArrayList<>();
            for (int i = 0; i < outputs.size(); i++) sortedActions.add(i);
            sortedActions.sort((a, b) -> Double.compare(outputs.get(b), outputs.get(a)));

            // 3. Execute action
            /* ACTIONS: 0=PASS, 1=D-SKIP, 2=D-ATK, 3=D-NRM, 4-57=STACK Specific Card */
            boolean acted = false;
            int chosenActionIndex = 0; // The actual action we ended up taking

            for (int action : sortedActions) {
                if (action >= 4) { // STACK Specific Card
                    int targetCardIndex = action - 4;
                    // Does the player have this exact card?
                    int handIdx = -1;
                    for (int i = 0; i < current.getHand().size(); i++) {
                        if (AiInputMapper.getCardIndex(current.getHand().get(i)) == targetCardIndex) {
                            handIdx = i;
                            break;
                        }
                    }

                    if (handIdx != -1) {
                        Card card = current.getHand().get(handIdx);
                        // Is it a valid sequence?
                        Card top = current.getTopStack();
                        boolean valid = false;
                        if (top == null) {
                            valid = (card.getRank() != Card.Rank.ACE && card.getRank() != Card.Rank.JOKER);
                        } else {
                            valid = isSequenceValid(current, top, card);
                        }

                        if (valid) {
                            // Play it!
                            current.getHand().remove(handIdx);
                            current.getStack().add(card);
                            sim.setHasPlayedToStack(true);
                            acted = true;
                            chosenActionIndex = action;

                            // Handle Joker stack value (auto-pick)
                            if (card.getRank() == Card.Rank.JOKER) {
                                Card below = current.getStack().size() >= 2
                                        ? current.getStack().get(current.getStack().size() - 2)
                                        : null;
                                if (below != null) {
                                    int ord = below.getRank().ordinal();
                                    if (ord < Card.Rank.values().length - 1) {
                                        current.setJokerStackValue(Card.Rank.values()[ord + 1]);
                                    } else {
                                        current.setJokerStackValue(Card.Rank.SEVEN);
                                    }
                                }
                            }

                            // Check win: Ace on top of stack
                            if (card.getRank() == Card.Rank.ACE) {
                                sim.setGameOver(true);
                                sim.setWinner(current);
                                // Record this final winning move before we break
                                double distNow = AiInputMapper.getDistanceToAce(current);
                                moveHistory[playerIdx].add(new MoveRecord(inputs, chosenActionIndex, distNow));
                                break;
                            }
                            break; // Done acting
                        }
                    }
                } else if (action >= 1 && action <= 3) { // DISCARD
                    if (!sim.isHasPlayedToStack()) {
                        int idx = findBestDiscard(current); // Keep basic heuristic for picking WHICH card to discard
                        if (idx != -1) {
                            Card card = current.getHand().remove(idx);
                            current.getDiscardPile().add(card);
                            acted = true;
                            chosenActionIndex = action;
                            // Skip effect resolution for simplicity — just apply instant effects
                            applySimpleEffect(sim, current, card);
                            break; // Done acting
                        }
                    }
                } else if (action == 0) { // PASS
                    if (sim.isHasPlayedToStack()) {
                        acted = true;
                        chosenActionIndex = action;
                        break;
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

            // Record the move
            moveHistory[playerIdx].add(new MoveRecord(inputs, chosenActionIndex, distNow));

            // Update rolling distance window
            LinkedList<Double> window = distanceWindow[playerIdx];
            window.addLast(distNow);
            if (window.size() > 11) { // Keep 11 entries: current move + up to 10 previous moves
                window.removeFirst();
            }

            // 5. Per-move intermediate training based on the average of up to 11 moves (10 previous + current)
            if (window.size() >= 2) {
                double sum = 0;
                // Include all elements in the window (including distNow)
                for (int i = 0; i < window.size(); i++) {
                    sum += window.get(i);
                }
                double avgDist = sum / window.size();  // Average distance including the current move
                double progress = avgDist - distNow; // Positive = got closer to Ace

                double reward;
                if (progress > 0) {
                    // Got closer — small positive reward scaled by how much progress
                    reward = Math.min(0.3 * progress, 3.0); // Cap at 3.0
                } else if (progress == 0) {
                    // No change — tiny neutral reward
                    reward = 0.05;
                } else {
                    // Got further away — no reward
                    reward = 0.0;
                }

                GlobalAi.trainSafe(inputs, chosenActionIndex, reward);
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
                            // Loser: penalty scales from 0.1 (oldest, mild) to 0.0 (newest, harsh)
                            reward = 0.1 * (1.0 - recency);
                        }

                        GlobalAi.trainSafe(rec.inputs, rec.actionIndex, reward);
                    }
                }

                // Small mutation for exploration
                GlobalAi.mutateSafe(0.05, 0.02);
            }
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
     * Simplified card effects for background games.
     * Only applies instant effects (skip, reverse, draw).
     * Skips interactive effects (Queen pick, Joker choice, etc.) for speed.
     */
    private void applySimpleEffect(GameState sim, Player source, Card card) {
        switch (card.getRank()) {
            case FOUR: // Skip next player
            case JACK:
                sim.skipPlayers(1);
                break;
            case KING: // Reverse direction
                sim.reverseDirection();
                break;
            case TWO: // Next player draws 2
                Player nextForTwo = getNextPlayer(sim);
                if (nextForTwo != null) drawN(sim, nextForTwo, 2);
                break;
            case NINE: // Player draws 1
                drawN(sim, source, 1);
                break;
            default:
                break;
        }
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
}
