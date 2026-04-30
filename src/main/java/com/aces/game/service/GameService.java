package com.aces.game.service;

import com.aces.game.domain.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class GameService {

    // Simple in-memory store for now
    private GameState defaultGame;

    private final com.aces.game.ai.BackgroundTrainer backgroundTrainer;

    public GameService(com.aces.game.ai.BackgroundTrainer backgroundTrainer) {
        this.backgroundTrainer = backgroundTrainer;
    }
    // initialCpuCount is stored in GameState effectively, but we can keep a default
    // here if needed.

    @jakarta.annotation.PreDestroy
    public void onExit() {
        System.out.println("GameService: Saving AI Brain on Shutdown...");
        com.aces.game.ai.GlobalAi.save();
    }

    public GameState getGame() {
        return defaultGame;
    }

    public void startGame(String playerName, int cpuCount) {
        // Stop background training if running
        backgroundTrainer.stop();

        defaultGame = new GameState();
        defaultGame.setInitialCpuCount(cpuCount);
        defaultGame.setPhase(GameState.Phase.PLAYING);
        defaultGame.setInitialPlayerName(playerName);

        // Create Deck
        initializeDeck(defaultGame);

        // Human Player
        Player p1 = new Player("p1", playerName != null ? playerName : "You", true);
        defaultGame.getPlayers().add(p1);

        // CPU Names Pool
        String[] cpuNames = { "Alice", "Bob", "Charlie", "David", "Eve", "Frank" };

        // CPU Players
        for (int i = 0; i < cpuCount; i++) {
            String name = (i < cpuNames.length) ? cpuNames[i] : "CPU " + (i + 1);
            Player cpu = new Player("cpu" + (i + 1), name, false);
            defaultGame.getPlayers().add(cpu);
        }

        // Deal 1 card to stack, 0 to hand
        // Rule: If starting card is Ace or Joker, draw a new one
        for (Player p : defaultGame.getPlayers()) {
            if (!defaultGame.getDrawPile().isEmpty()) {
                Card startCard = defaultGame.getDrawPile().pop();
                // Keep drawing until we get a valid starting card
                while ((startCard.getRank() == Card.Rank.ACE || startCard.getRank() == Card.Rank.JOKER)
                        && !defaultGame.getDrawPile().isEmpty()) {
                    // Put invalid card at bottom of deck
                    defaultGame.getDrawPile().add(0, startCard);
                    startCard = defaultGame.getDrawPile().pop();
                }
                p.getStack().add(startCard);
            }
        }

        defaultGame.setGameMessage("Game Started! Draw a card to begin.");

    }

    // Default start (for restart button or testing) - reuse count and name
    public void startGame() {
        int count = (defaultGame != null) ? defaultGame.getInitialCpuCount() : 1;
        String name = (defaultGame != null) ? defaultGame.getInitialPlayerName() : "Player 1";
        startGame(name, count);
    }

    public void resetToMenu() {
        if (defaultGame != null) {
            defaultGame.setPhase(GameState.Phase.MENU);
            defaultGame.getPlayers().clear();
            defaultGame.getDrawPile().clear();
            defaultGame.getDiscardPile().clear();
        }
    }

    private void initializeDeck(GameState game) {
        game.getDrawPile().clear();

        // Standard 54-card deck: 52 cards (4 suits × 13 ranks) + 2 Jokers

        // Add all 52 standard cards (one of each)
        for (Card.Suit suit : Card.Suit.values()) {
            if (suit == Card.Suit.JOKER)
                continue; // Skip joker suit
            for (Card.Rank rank : Card.Rank.values()) {
                if (rank == Card.Rank.JOKER)
                    continue; // Skip joker rank
                game.getDrawPile().add(new Card(suit, rank));
            }
        }

        // Add exactly 2 Jokers
        game.getDrawPile().add(new Card(Card.Suit.JOKER, Card.Rank.JOKER));
        game.getDrawPile().add(new Card(Card.Suit.JOKER, Card.Rank.JOKER));

        // Shuffle the deck
        // Total: 52 + 2 = 54 cards

        // Shuffle the deck
        Collections.shuffle(game.getDrawPile());

        // Set bottom facing card (the LAST card in the list relative to pop? Stack pop
        // is last.
        // If we pop from end, bottom is 0. If we pop from 0 (using remove(0)), bottom
        // is size-1.
        // Stack pop is typically remove(size-1). Wait, Stack extends Vector. pop()
        // removes top (last item).
        // So bottom of deck is index 0.
        if (!game.getDrawPile().isEmpty()) {
            // Ensure bottom card is not a Joker (it decides which 7s are active)
            while (game.getDrawPile().get(0).getRank() == Card.Rank.JOKER && game.getDrawPile().size() > 1) {
                Collections.shuffle(game.getDrawPile());
            }
            game.setCardUnderDeck(game.getDrawPile().get(0));
        }
    }

    public void reshuffleDeck() {
        if (!defaultGame.getDiscardPile().isEmpty() || defaultGame.getDrawPile().isEmpty()) {
            // Check if we need to add a new deck
            if (defaultGame.getDiscardPile().size() < 20) {
                defaultGame.setGameMessage("Deck empty! Reshuffling discards AND adding a fresh deck...");
                System.out.println("Reshuffling with fewer than 20 cards - adding an extra deck!");

                // Add all 52 standard cards (one of each)
                for (Card.Suit suit : Card.Suit.values()) {
                    if (suit == Card.Suit.JOKER)
                        continue; // Skip joker suit
                    for (Card.Rank rank : Card.Rank.values()) {
                        if (rank == Card.Rank.JOKER)
                            continue; // Skip joker rank
                        defaultGame.getDrawPile().add(new Card(suit, rank));
                    }
                }
            
                // Add exactly 2 Jokers
                defaultGame.getDrawPile().add(new Card(Card.Suit.JOKER, Card.Rank.JOKER));
                defaultGame.getDrawPile().add(new Card(Card.Suit.JOKER, Card.Rank.JOKER));
            } else {
                defaultGame.setGameMessage("Reshuffling discards...");
                System.out.println("Reshuffling deck...");
            }

            // Move discard to draw
            defaultGame.getDrawPile().addAll(defaultGame.getDiscardPile());
            defaultGame.getDiscardPile().clear();
            
            // Clear all player individual discard piles to prevent duplications
            for (Player p : defaultGame.getPlayers()) {
                p.getDiscardPile().clear();
            }

            Collections.shuffle(defaultGame.getDrawPile());

        }
    }

    public void drawCard(String playerId) {
        Player p = defaultGame.getCurrentPlayer();
        if (!p.getId().equals(playerId)) {
            return;
        }

        // BLOCK: Cannot draw if handling an effect
        if (defaultGame.getEffectState() != GameState.EffectState.NONE) {
            defaultGame.setGameMessage("Finish the current action first!");
            return;
        }

        if (defaultGame.isHasDrawn()) {
            defaultGame.setGameMessage("You have already drawn a card this turn!");
            return;
        }

        if (defaultGame.getDrawPile().isEmpty()) {
            reshuffleDeck();
        }

        if (defaultGame.getDrawPile().isEmpty()) {
            defaultGame.setGameMessage("Deck empty and no discards to shuffle!");
            return;
        }

        Card drawn = defaultGame.getDrawPile().pop();
        p.getHand().add(drawn);
        defaultGame.setHasDrawn(true);
        defaultGame.setGameMessage("You drew: " + drawn.getDisplayString());
    }

    /**
     * Draw a card and return it for animation purposes
     */
    public Card drawCardAndReturn(String playerId) {
        Player p = defaultGame.getCurrentPlayer();
        if (!p.getId().equals(playerId)) {
            return null;
        }

        if (defaultGame.isHasDrawn()) {
            return null;
        }

        if (defaultGame.getDrawPile().isEmpty()) {
            reshuffleDeck();
        }

        if (defaultGame.getDrawPile().isEmpty()) {
            return null;
        }

        Card drawn = defaultGame.getDrawPile().pop();
        p.getHand().add(drawn);
        defaultGame.setHasDrawn(true);
        defaultGame.setGameMessage("You drew: " + drawn.getDisplayString());
        return drawn;
    }

    public void playToStack(String playerId, int cardIndex) {
        Player p = defaultGame.getCurrentPlayer();
        if (!p.getId().equals(playerId))
            return;

        // BLOCK: Cannot play if handling an effect
        if (defaultGame.getEffectState() != GameState.EffectState.NONE) {
            defaultGame.setGameMessage("Finish the current action first!");
            return;
        }

        if (cardIndex < 0 || cardIndex >= p.getHand().size())
            return;

        List<Double> preMoveInputs = null;
        if (p.isPc()) {
            preMoveInputs = com.aces.game.ai.AiInputMapper.extractInputs(defaultGame, p);
        }

        Card card = p.getHand().get(cardIndex);

        // Validation Logic
        boolean isValid = false;
        Card top = p.getTopStack();

        if (top == null) {
            // Cannot start stack with a Joker or Ace
            isValid = (card.getRank() != Card.Rank.JOKER && card.getRank() != Card.Rank.ACE);
        } else {
            isValid = isSequenceValid(p, top, card);
        }

        if (isValid) {
            // Capture distances BEFORE placing the card on the stack
            double preDistSelf = (preMoveInputs != null) ? com.aces.game.ai.AiInputMapper.getDistanceToAce(p) : 0;
            double preDistOpp  = (preMoveInputs != null) ? getClosestOpponentDistance() : 0;

            p.getHand().remove(cardIndex);
            p.getStack().add(card);
            defaultGame.setHasPlayedToStack(true);

            if (p.isPc() && preMoveInputs != null) {
                // Measure distance AFTER card is on stack
                double postDist = com.aces.game.ai.AiInputMapper.getDistanceToAce(p);
                int actionIndex = 4 + com.aces.game.ai.AiInputMapper.getCardIndex(card);
                double reward = computeLiveReward(preDistSelf, postDist, preDistOpp);
                com.aces.game.ai.GlobalAi.trainSafe(preMoveInputs, actionIndex, reward, 50.0);
            }

            // If playing a Joker, need to choose what value it represents
            if (card.getRank() == Card.Rank.JOKER) {
                // Store card index for reference (it's already on stack)
                defaultGame.setEffectState(GameState.EffectState.JOKER_STACK_VALUE);
                defaultGame.setGameMessage(
                        "Joker played! Choose what rank it represents (3-10, Q only - not 2, A, or K).");
                // Don't end turn yet - wait for value selection
                return;
            }

            // Clear jokerStackValue when a regular card is played
            p.setJokerStackValue(null);

            // Check Win
            if (card.getRank() == Card.Rank.ACE) {
                defaultGame.setGameOver(true);
                // Start background training when game ends
                backgroundTrainer.start();
                defaultGame.setWinner(p);
                defaultGame.setGameMessage("WINNER! " + p.getName() + " placed the Ace!");
                return;
            }

            if (!hasValidStackPlay(p)) {
                defaultGame.setGameMessage("Played " + card.getDisplayString() + ". No valid plays left, auto-passing.");
                endTurn();
            } else {
                defaultGame.setGameMessage("Played " + card.getDisplayString() + " to stack. Play another or Pass.");
            }
        } else {
            defaultGame.setGameMessage("Invalid move! Must be sequential (+/- 1).");
        }
    }

    private boolean isSequenceValid(Player p, Card top, Card card) {
        // Joker can be played on any card (will choose value after)
        if (card.getRank() == Card.Rank.JOKER) {
            return true;
        }

        // If top card is a Joker, use the stored jokerStackValue for validation
        if (top.getRank() == Card.Rank.JOKER) {
            Card.Rank jokerValue = p.getJokerStackValue();
            if (jokerValue == null) {
                // Joker value not yet set - shouldn't happen but allow for safety
                return true;
            }
            // Check if card is adjacent to the Joker's chosen value (including backdoor)
            int diff = Math.abs(card.getRank().ordinal() - jokerValue.ordinal());
            // Backdoor: 2 (ordinal ~1) can connect to Ace (ordinal ~12)
            if (jokerValue == Card.Rank.TWO && card.getRank() == Card.Rank.ACE) {
                return true;
            }
            return diff == 1;
        }

        // Normal sequential check
        int diff = Math.abs(card.getRank().ordinal() - top.getRank().ordinal());

        // Backdoor rule: 2 can connect to Ace (wrap around)
        if (top.getRank() == Card.Rank.TWO && card.getRank() == Card.Rank.ACE) {
            return true;
        }

        return diff == 1;
    }

    private String getAdjacentRanks(Card.Rank rank) {
        // Returns valid adjacent ranks for Joker value selection (excluding 2, A, K)
        Card.Rank[] ranks = Card.Rank.values();
        StringBuilder sb = new StringBuilder();
        int ordinal = rank.ordinal();

        // Check lower adjacent
        if (ordinal > 0) {
            Card.Rank lower = ranks[ordinal - 1];
            if (lower != Card.Rank.TWO && lower != Card.Rank.ACE && lower != Card.Rank.KING
                    && lower != Card.Rank.JOKER) {
                sb.append(lower);
            }
        }

        // Check upper adjacent
        if (ordinal < ranks.length - 1) {
            Card.Rank upper = ranks[ordinal + 1];
            if (upper != Card.Rank.TWO && upper != Card.Rank.ACE && upper != Card.Rank.KING
                    && upper != Card.Rank.JOKER) {
                if (sb.length() > 0)
                    sb.append(" or ");
                sb.append(upper);
            }
        }

        return sb.toString();
    }

    private boolean hasValidStackPlay(Player p) {
        if (p.getHand().isEmpty()) return false;
        Card top = p.getTopStack();
        if (top == null) {
            for (Card c : p.getHand()) {
                if (c.getRank() != Card.Rank.JOKER && c.getRank() != Card.Rank.ACE) {
                    return true;
                }
            }
            return false;
        }
        for (Card c : p.getHand()) {
            if (isSequenceValid(p, top, c)) {
                return true;
            }
        }
        return false;
    }

    public void skipTurn(String playerId) {
        Player p = defaultGame.getCurrentPlayer();
        if (!p.getId().equals(playerId)) {
            return;
        }

        if (!defaultGame.isHasDrawn()) {
            defaultGame.setGameMessage("You must draw a card before passing!");
            return;
        }

        List<Double> preMoveInputs = null;
        if (p.isPc()) {
            preMoveInputs = com.aces.game.ai.AiInputMapper.extractInputs(defaultGame, p);
        }

        if (defaultGame.isHasPlayedToStack()) {
            defaultGame.setGameMessage(p.getName() + " passed turn.");
            if (p.isPc() && preMoveInputs != null) {
                // Passing after a stack play is correct — mild positive if we're still ahead
                double dist    = com.aces.game.ai.AiInputMapper.getDistanceToAce(p);
                double oppDist = getClosestOpponentDistance();
                double reward  = (dist < oppDist) ? 0.5 : 0.1;
                com.aces.game.ai.GlobalAi.trainSafe(preMoveInputs, 0, reward, 50.0);
            }
            endTurn();
        } else {
            // Allow passing if user chooses to (e.g. stuck)
            defaultGame.setGameMessage(p.getName() + " passed turn (no play made).");
            if (p.isPc() && preMoveInputs != null) {
                // Passing without playing at all is generally bad — penalize unless stuck
                double dist    = com.aces.game.ai.AiInputMapper.getDistanceToAce(p);
                double oppDist = getClosestOpponentDistance();
                double reward  = (dist < oppDist) ? 0.0 : -0.3;
                com.aces.game.ai.GlobalAi.trainSafe(preMoveInputs, 0, reward, 50.0);
            }
            endTurn();
        }
    }

    private int getDiscardActionIndex(Card card) {
        if (isInteractiveEffect(card)) return 2; // D-ATK
        switch (card.getRank()) {
            case FOUR: case JACK: case KING: case TWO: case NINE:
                return 1; // D-SKIP / Instant
            default:
                return 3; // D-NRM
        }
    }

    public void discardAndEffect(String playerId, int cardIndex) {
        Player p = defaultGame.getCurrentPlayer();
        if (!p.getId().equals(playerId))
            return;

        // BLOCK: Cannot discard if handling an effect
        if (defaultGame.getEffectState() != GameState.EffectState.NONE) {
            defaultGame.setGameMessage("Finish the current action first!");
            return;
        }

        // BLOCK: Cannot discard if already played to stack
        if (defaultGame.isHasPlayedToStack()) {
            defaultGame.setGameMessage("Cannot discard! You played to the stack. You must Pass (End Turn) instead.");
            return;
        }

        if (defaultGame.isHasDiscarded()) {
            defaultGame.setGameMessage("You have already discarded a card this turn!");
            return;
        }

        if (cardIndex < 0 || cardIndex >= p.getHand().size())
            return;

        List<Double> preMoveInputs = null;
        if (p.isPc()) {
            preMoveInputs = com.aces.game.ai.AiInputMapper.extractInputs(defaultGame, p);
        }

        // Capture distance before the discard effect resolves
        double preDistSelf = (p.isPc() && preMoveInputs != null)
                ? com.aces.game.ai.AiInputMapper.getDistanceToAce(p) : 0;
        double preDistOpp  = (p.isPc() && preMoveInputs != null)
                ? getClosestOpponentDistance() : 0;

        Card card = p.getHand().remove(cardIndex);

        if (p.isPc() && preMoveInputs != null) {
            // Post-discard self distance (stack unchanged by discard itself)
            double postDist = com.aces.game.ai.AiInputMapper.getDistanceToAce(p);
            int actionIndex = getDiscardActionIndex(card);
            double reward = computeLiveReward(preDistSelf, postDist, preDistOpp);
            com.aces.game.ai.GlobalAi.trainSafe(preMoveInputs, actionIndex, reward, 50.0);
        }

        p.getDiscardPile().add(card);
        defaultGame.getDiscardPile().add(card); // Add to global discard for reshuffling and effects

        // Handle Interactive Effects
        if (isInteractiveEffect(card)) {
            defaultGame.setEffectState(getInitialEffectState(card));
            defaultGame.setEffectSourceRank(card.getRank());
            initializeInteraction(card, p);
        } else {
            // Instant Effects
            applyInstantEffect(card, p);
            endTurn();
        }
    }

    private boolean isInteractiveEffect(Card card) {
        switch (card.getRank()) {
            case QUEEN:
            case JOKER:
            case SEVEN:
            case EIGHT:
            case TEN:
                return true;
            default:
                return false;
        }
    }

    private GameState.EffectState getInitialEffectState(Card card) {
        switch (card.getRank()) {
            case QUEEN:
                return GameState.EffectState.QUEEN_PICK;
            case JOKER:
                return GameState.EffectState.JOKER_PICK;
            case SEVEN:
            case EIGHT:
            case TEN:
                return GameState.EffectState.SELECT_TARGET;
            default:
                return GameState.EffectState.NONE;
        }
    }

    private void initializeInteraction(Card card, Player p) {
        defaultGame.getTempBuffer().clear();

        if (card.getRank() == Card.Rank.QUEEN) {
            // Draw 3 to buffer
            int count = Math.min(3, defaultGame.getDrawPile().size());
            // If < 3, reshuffle?
            if (count < 3) {
                reshuffleDeck();
                count = Math.min(3, defaultGame.getDrawPile().size());
            }

            for (int i = 0; i < count; i++) {
                if (!defaultGame.getDrawPile().isEmpty()) {
                    defaultGame.getTempBuffer().add(defaultGame.getDrawPile().pop());
                }
            }
            defaultGame.setGameMessage("Queen played! Select 1 card to keep.");

        } else if (card.getRank() == Card.Rank.JOKER) {
            defaultGame.getTempBuffer().addAll(p.getDiscardPile());
            // Remove the LAST card (the Joker itself) from options if present
            if (!defaultGame.getTempBuffer().isEmpty()) {
                defaultGame.getTempBuffer().remove(defaultGame.getTempBuffer().size() - 1);
            }

            if (defaultGame.getTempBuffer().isEmpty()) {
                defaultGame.setGameMessage("Joker played! But discard pile is empty (except Joker).");
                defaultGame.setEffectState(GameState.EffectState.NONE);
                endTurn();
            } else {
                defaultGame.setGameMessage("Joker played! Choose ability:");
                defaultGame.setEffectState(GameState.EffectState.JOKER_CHOICE_MODE); // Set to Choice Mode
                defaultGame.setJokerPickCount(0);
            }
        } else if (card.getRank() == Card.Rank.SEVEN) {
            // Validate 7 BEFORE showing target selection
            if (!p.getHand().isEmpty()) {
                if (isValidSeven(card)) {
                    defaultGame.setGameMessage("Valid 7! Select a target to sabotage.");
                } else {
                    // 7 doesn't meet suit/color requirements - no effect
                    defaultGame.setGameMessage("7 discarded but didn't match bottom card condition. No effect.");
                    defaultGame.setEffectState(GameState.EffectState.NONE);
                    endTurn();
                }
            } else {
                defaultGame.setGameMessage("7 discarded, but you have no cards to use its power!");
                defaultGame.setEffectState(GameState.EffectState.NONE);
                endTurn();
            }
        } else if (card.getRank() == Card.Rank.EIGHT) {
            defaultGame.setGameMessage("Select a target player.");
        } else if (card.getRank() == Card.Rank.TEN) {
            // Check if any player has a stack with 4+ cards
            boolean hasValidStack = defaultGame.getPlayers().stream()
                    .anyMatch(player -> player.getStack().size() >= 4);
            if (hasValidStack) {
                defaultGame.setGameMessage("Select a target player with 4+ cards in stack.");
            } else {
                // No valid stacks - effect does nothing
                defaultGame.setGameMessage("TEN discarded but no stack has 4+ cards. Nothing happens.");
                defaultGame.setEffectState(GameState.EffectState.NONE);
                endTurn();
            }
        }
    }

    public void handleInteraction(String playerId, String actionData) {
        Player p = defaultGame.getCurrentPlayer();
        if (!p.getId().equals(playerId))
            return;

        switch (defaultGame.getEffectState()) {
            case QUEEN_PICK:
                try {
                    int pickIdx = Integer.parseInt(actionData);
                    if (pickIdx >= 0 && pickIdx < defaultGame.getTempBuffer().size()) {
                        p.getHand().add(defaultGame.getTempBuffer().remove(pickIdx));

                        // If only 1 card remains, just put it back
                        if (defaultGame.getTempBuffer().size() <= 1) {
                            for (Card c : defaultGame.getTempBuffer()) {
                                defaultGame.getDrawPile().push(c);
                            }
                            defaultGame.getTempBuffer().clear();
                            defaultGame.setGameMessage("Card selected. Remaining returned to deck.");
                            defaultGame.setEffectState(GameState.EffectState.NONE);
                            endTurn();
                        } else {
                            // 2 cards remain - let player choose order
                            defaultGame.setEffectState(GameState.EffectState.QUEEN_ORDER);
                            defaultGame.setGameMessage("Choose which card goes on TOP of the deck.");
                        }
                    }
                } catch (NumberFormatException e) {
                }
                break;

            case QUEEN_ORDER:
                try {
                    int pickIdx = Integer.parseInt(actionData);
                    if (pickIdx >= 0 && pickIdx < defaultGame.getTempBuffer().size()) {
                        // Selected card goes on top (pushed last = drawn first)
                        Card topCard = defaultGame.getTempBuffer().remove(pickIdx);

                        // Push remaining card(s) first (they go under)
                        for (Card c : defaultGame.getTempBuffer()) {
                            defaultGame.getDrawPile().push(c);
                        }
                        // Push selected card last (it goes on top)
                        defaultGame.getDrawPile().push(topCard);

                        defaultGame.getTempBuffer().clear();
                        defaultGame.setGameMessage("Cards returned to deck in your chosen order.");
                        defaultGame.setEffectState(GameState.EffectState.NONE);
                        endTurn();
                    }
                } catch (NumberFormatException e) {
                }
                break;

            case JOKER_CHOICE_MODE:
                // actionData should be "STACK" or "HAND"
                if ("STACK".equals(actionData)) {
                    defaultGame.setJokerModeToStack(true);
                    defaultGame.setGameMessage("Pick up to 2 cards for your STACK.");
                    defaultGame.setEffectState(GameState.EffectState.JOKER_PICK);
                } else if ("HAND".equals(actionData)) {
                    defaultGame.setJokerModeToStack(false);
                    defaultGame.setGameMessage("Pick 1 card for your HAND.");
                    defaultGame.setEffectState(GameState.EffectState.JOKER_PICK);
                }
                break;

            case JOKER_PICK:
                try {
                    int pickIdx = Integer.parseInt(actionData);
                    if (pickIdx >= 0 && pickIdx < defaultGame.getTempBuffer().size()) {
                        Card picked = defaultGame.getTempBuffer().get(pickIdx); // Peek first

                        if (defaultGame.isJokerModeToStack()) {
                            // --- STACK MODE (Up to 2 cards, Strict Validation) ---
                            Card top = p.getTopStack();
                            boolean fitsStack = false;
                            if (top == null) {
                                fitsStack = (picked.getRank() != Card.Rank.JOKER && picked.getRank() != Card.Rank.ACE);
                            } else {
                                fitsStack = isSequenceValid(p, top, picked);
                            }

                            if (!fitsStack) {
                                defaultGame.setGameMessage("Invalid selection! " + picked.getDisplayString()
                                        + " does not fit your stack sequence.");
                                return; // Let them pick again
                            }

                            // Proceed to move to stack
                            defaultGame.getTempBuffer().remove(pickIdx);
                            boolean found = removeCardFromPlayerDiscard(p, picked);

                            if (found) {
                                defaultGame.getDiscardPile().remove(picked);
                                p.getStack().add(picked);

                                // Check Win
                                if (picked.getRank() == Card.Rank.ACE) {
                                    defaultGame.setGameOver(true);
                                    // Start background training when game ends
                                    backgroundTrainer.start();
                                    defaultGame.setWinner(p);
                                    defaultGame.setGameMessage("WINNER! " + p.getName() + " placed the Ace via Joker!");
                                    defaultGame.setEffectState(GameState.EffectState.NONE);
                                    endTurn();
                                    return;
                                }

                                int count = defaultGame.getJokerPickCount() + 1;
                                defaultGame.setJokerPickCount(count);

                                if (count < 2 && !defaultGame.getTempBuffer().isEmpty()) {
                                    defaultGame.setGameMessage(
                                            "Recovered " + picked.getDisplayString() + " to STACK. Pick one more.");
                                } else {
                                    defaultGame.getTempBuffer().clear();
                                    defaultGame.setEffectState(GameState.EffectState.NONE);
                                    defaultGame.setGameMessage("Joker effect complete.");
                                    endTurn();
                                }
                            }
                        } else {
                            // --- HAND MODE (1 card, No Validation needed) ---
                            defaultGame.getTempBuffer().remove(pickIdx);
                            boolean found = removeCardFromPlayerDiscard(p, picked);

                            if (found) {
                                defaultGame.getDiscardPile().remove(picked);
                                p.getHand().add(picked);
                                defaultGame.setGameMessage("Recovered " + picked.getDisplayString() + " to HAND.");
                            }

                            // Always end after 1 card
                            defaultGame.getTempBuffer().clear();
                            defaultGame.setEffectState(GameState.EffectState.NONE);
                            endTurn();
                        }
                    }
                } catch (NumberFormatException e) {
                }
                break;

            case SELECT_TARGET:
                Player target = defaultGame.getPlayers().stream()
                        .filter(pl -> pl.getId().equals(actionData))
                        .findFirst().orElse(null);

                if (target != null && !target.getId().equals(p.getId())) {
                    // Always route to applyTargetedEffect, which now handles 3+ player logic internally
                    applyTargetedEffect(p, target, defaultGame.getEffectSourceRank());
                    // Check if effect changed state (e.g., EIGHT goes to EIGHT_PICK_CARD)
                    if (defaultGame.getEffectState() == GameState.EffectState.NONE ||
                            defaultGame.getEffectState() == GameState.EffectState.SELECT_TARGET) {
                        defaultGame.setEffectState(GameState.EffectState.NONE);
                        endTurn();
                    }
                }
                break;

            case EIGHT_CHOOSE_SOURCE:
                // actionData = "hand", "stack", or "discard"
                String targetId = defaultGame.getEightTargetPlayerId();
                Player eightTarget = defaultGame.getPlayers().stream()
                        .filter(pl -> pl.getId().equals(targetId))
                        .findFirst().orElse(null);

                if (eightTarget != null) {
                    Card stolen = null;
                    String sourceDesc = "";

                    switch (actionData) {
                        case "hand":
                            // Go to card picking state (blind selection)
                            if (!eightTarget.getHand().isEmpty()) {
                                defaultGame.setEffectState(GameState.EffectState.EIGHT_PICK_CARD);
                                defaultGame.setGameMessage("Pick a card from " + eightTarget.getName() + "'s hand!");
                                return; // Don't end turn yet
                            } else {
                                defaultGame.setGameMessage(eightTarget.getName() + "'s hand is empty!");
                            }
                            break;
                        case "stack":
                            // Rule: Can only steal from stack if they have MORE THAN ONE card
                            if (eightTarget.getStack().size() > 1) {
                                stolen = eightTarget.getStack().remove(eightTarget.getStack().size() - 1);
                                sourceDesc = "stack";
                            } else {
                                defaultGame.setGameMessage(
                                        eightTarget.getName() + " only has 1 card in stack! Cannot steal.");
                                return; // Don't end turn, let them choose again
                            }
                            break;
                        case "discard":
                            if (!eightTarget.getDiscardPile().isEmpty()) {
                                stolen = eightTarget.getDiscardPile().remove(eightTarget.getDiscardPile().size() - 1);
                                // Also remove from global discard pile to prevent duplicates
                                defaultGame.getDiscardPile().remove(stolen);
                                sourceDesc = "discard pile";
                            }
                            break;
                    }

                    if (stolen != null) {
                        p.getHand().add(stolen);
                        defaultGame.setGameMessage("EIGHT! Took " + stolen.getDisplayString() + " from "
                                + eightTarget.getName() + "'s " + sourceDesc + "!");
                    }
                }

                defaultGame.setEightTargetPlayerId(null);
                defaultGame.setEffectState(GameState.EffectState.NONE);
                endTurn();
                break;

            case EIGHT_PICK_CARD:
                // actionData may contain pick index from frontend, but rules mandate RANDOM theft
                String pickTargetId = defaultGame.getEightTargetPlayerId();
                Player pickTarget = defaultGame.getPlayers().stream()
                        .filter(pl -> pl.getId().equals(pickTargetId))
                        .findFirst().orElse(null);

                if (pickTarget != null && !pickTarget.getHand().isEmpty()) {
                    java.util.Random rnd = new java.util.Random();
                    int randomPickIdx = rnd.nextInt(pickTarget.getHand().size());

                    Card stolen = pickTarget.getHand().remove(randomPickIdx);
                    // Rules imply stolen card goes into the active player's hand
                    p.getHand().add(stolen);
                    defaultGame.setGameMessage(
                            "EIGHT! Randomly stole " + stolen.getDisplayString() + " from " + pickTarget.getName() + "!");
                }
                
                defaultGame.setEightTargetPlayerId(null);
                defaultGame.setEffectState(GameState.EffectState.NONE);
                endTurn();
                break;

            case JOKER_STACK_VALUE:
                // actionData = rank name (e.g., "THREE", "FOUR", etc.)
                try {
                    Card.Rank chosenRank = Card.Rank.valueOf(actionData.toUpperCase());
                    // Validate: Can't be 2, Ace, or King
                    if (chosenRank == Card.Rank.TWO || chosenRank == Card.Rank.ACE ||
                            chosenRank == Card.Rank.KING || chosenRank == Card.Rank.JOKER) {
                        defaultGame.setGameMessage("Joker cannot represent 2, Ace, King, or another Joker!");
                        return;
                    }

                    // Validate: Chosen value must be adjacent to card below Joker
                    // The Joker is now on top, so card below is at size-2
                    if (p.getStack().size() >= 2) {
                        Card cardBelow = p.getStack().get(p.getStack().size() - 2);
                        Card.Rank belowRank = cardBelow.getRank();
                        // If card below is also a Joker, use its stored value
                        if (belowRank == Card.Rank.JOKER && p.getJokerStackValue() != null) {
                            belowRank = p.getJokerStackValue();
                        }
                        int diff = Math.abs(chosenRank.ordinal() - belowRank.ordinal());
                        if (diff != 1) {
                            defaultGame.setGameMessage("Joker value must be adjacent to " + belowRank + "! Choose " +
                                    getAdjacentRanks(belowRank));
                            return;
                        }
                    }

                    p.setJokerStackValue(chosenRank);
                    defaultGame.setEffectState(GameState.EffectState.NONE);
                    
                    if (!hasValidStackPlay(p)) {
                        defaultGame.setGameMessage("Joker is acting as " + chosenRank + ". No valid plays left, auto-passing.");
                        endTurn();
                    } else {
                        defaultGame.setGameMessage("Joker is now acting as " + chosenRank + "!");
                    }
                } catch (IllegalArgumentException e) {
                    defaultGame.setGameMessage("Invalid rank selection!");
                }
                break;

            case SEVEN_PASS_CARD:
                try {
                    int cardIdx = Integer.parseInt(actionData);
                    String passTargetId = defaultGame.getSevenTargetPlayerId();
                    Player passTarget = defaultGame.getPlayers().stream()
                            .filter(pl -> pl.getId().equals(passTargetId))
                            .findFirst().orElse(null);

                    if (passTarget != null && cardIdx >= 0 && cardIdx < p.getHand().size()) {
                        Card gift = p.getHand().remove(cardIdx);
                        passTarget.getStack().add(gift);
                        defaultGame.setGameMessage(
                                "Placed " + gift.getDisplayString() + " on " + passTarget.getName() + "'s stack!");
                        defaultGame.setSevenTargetPlayerId(null);
                        defaultGame.setEffectState(GameState.EffectState.NONE);
                        
                        // Check Win
                        if (gift.getRank() == Card.Rank.ACE) {
                            defaultGame.setGameOver(true);
                            // Start background training when game ends
                            backgroundTrainer.start();
                            defaultGame.setWinner(passTarget);
                            defaultGame.setGameMessage("WINNER! " + passTarget.getName() + " placed the Ace!");
                        }
                        
                        endTurn();
                    }
                } catch (NumberFormatException e) {
                }
                break;
        }
    }

    private boolean isValidSeven(Card seven) {
        Card bottom = defaultGame.getCardUnderDeck();
        if (bottom == null)
            return true; // Fallback

        int playerCount = defaultGame.getPlayers().size();

        if (playerCount == 2) {
            // Must match suit
            return seven.getSuit() == bottom.getSuit();
        } else if (playerCount == 3) {
            // Must match color
            boolean sevenRed = (seven.getSuit() == Card.Suit.HEARTS || seven.getSuit() == Card.Suit.DIAMONDS);
            boolean bottomRed = (bottom.getSuit() == Card.Suit.HEARTS || bottom.getSuit() == Card.Suit.DIAMONDS);
            return sevenRed == bottomRed;
        } else if (playerCount == 4) {
            // Any suit EXCEPT bottom suit
            return seven.getSuit() != bottom.getSuit();
        } else {
            // 5+ players: Any 7 works
            return true;
        }
    }

    private boolean removeCardFromPlayerDiscard(Player p, Card picked) {
        for (int i = 0; i < p.getDiscardPile().size(); i++) {
            if (p.getDiscardPile().get(i).equals(picked)) {
                p.getDiscardPile().remove(i);
                return true;
            }
        }
        return false;
    }

    private void applyTargetedEffect(Player source, Player target, Card.Rank rank) {
        switch (rank) {
            case EIGHT: // Steal a card from target
                // Check if target has ANY cards to steal
                boolean hasHand = !target.getHand().isEmpty();
                boolean hasStack = !target.getStack().isEmpty();
                boolean hasDiscard = !target.getDiscardPile().isEmpty();

                defaultGame.setEightTargetPlayerId(target.getId());

                // If 2 Players: Rule is "Can only take from someone's hand"
                if (defaultGame.getPlayers().size() == 2) {
                    if (hasHand) {
                        // Skip source selection, go directly to picking from hand
                        defaultGame.setEffectState(GameState.EffectState.EIGHT_PICK_CARD);
                        defaultGame.setGameMessage("Stealing from " + target.getName() + "'s hand... pick a card!");
                    } else {
                        defaultGame.setGameMessage(target.getName() + " has no cards in hand!");
                        defaultGame.setEffectState(GameState.EffectState.NONE);
                    }
                } else {
                    // 3+ Players: Choose Source (Hand, Stack, Discard)
                    // Rule: Cannot take from stack if only 1 card. Cannot take empty.
                    boolean canTakeStack = target.getStack().size() > 1;
                    
                    if (hasHand || canTakeStack || hasDiscard) {
                        defaultGame.setEffectState(GameState.EffectState.EIGHT_CHOOSE_SOURCE);
                        // Make grammar dynamic based on what's actually available
                        String options = "";
                        if (hasHand) options += "Hand, ";
                        if (canTakeStack) options += "Stack, ";
                        if (hasDiscard) options += "Discard";
                        if (options.endsWith(", ")) options = options.substring(0, options.length() - 2);
                        
                        defaultGame.setGameMessage(
                                "Choose where to steal from " + target.getName() + ": " + options + "?");
                    } else {
                        defaultGame.setGameMessage(target.getName() + " has no eligible cards to steal!");
                        defaultGame.setEffectState(GameState.EffectState.NONE);
                    }
                }
                break;
            case TEN: // Take top 3 from stack (if 4+ cards)
                if (target.getStack().size() >= 4) {
                    // Take top 3 cards from target's stack
                    List<Card> taken = new ArrayList<>();
                    for (int i = 0; i < 3; i++) {
                        taken.add(target.getStack().remove(target.getStack().size() - 1));
                    }
                    // Add to the hand of the player who played the TEN
                    source.getHand().addAll(taken);
                    defaultGame.setGameMessage("TEN! Took 3 cards from " + target.getName() + "'s stack!");
                } else {
                    defaultGame.setGameMessage(target.getName() + "'s stack needs 4+ cards!");
                }
                defaultGame.setEffectState(GameState.EffectState.NONE);
                break;
            case SEVEN: // Sabotage: Put card from hand to target stack
                // Check if valid 7 first
                // Actually the 7 is already played. The rule "7 (that matches...) you can
                // put..."
                // implies the effect ONLY happens if the 7 was valid according to specific
                // rules.
                // Since we assume the move was allowed, we check if the effect triggers.

                // Defensive check: ensure discard pile is not empty
                if (defaultGame.getDiscardPile().isEmpty()) {
                    defaultGame.setGameMessage("7 discarded but no card in discard pile. No effect.");
                    defaultGame.setEffectState(GameState.EffectState.NONE);
                    break;
                }

                // We need the card itself to check.
                // But applyTargetedEffect only gets rank.
                // We need to pass the card or check top of discard.
                Card playedSeven = defaultGame.getDiscardPile().get(defaultGame.getDiscardPile().size() - 1);
                if (isValidSeven(playedSeven)) {
                    defaultGame.setSevenTargetPlayerId(target.getId());
                    defaultGame.setEffectState(GameState.EffectState.SEVEN_PASS_CARD);
                    defaultGame.setGameMessage(
                            "Valid 7! Pick a card from YOUR hand to put on " + target.getName() + "'s stack!");
                } else {
                    defaultGame.setGameMessage("7 played, but didn't match bottom card condition. No effect.");
                    defaultGame.setEffectState(GameState.EffectState.NONE);
                }
                break;
            default:
                break;
        }
    }

    private void applyInstantEffect(Card card, Player p) {
        int playerCount = defaultGame.getPlayers().size();

        switch (card.getRank()) {
            case THREE: // Draw 3
                drawN(p, 3);
                defaultGame.setGameMessage("THREE! Drew 3 cards.");
                break;
            case FOUR: // Skip 2 (or 1 in 2-player)
                int skipCount = (playerCount == 2) ? 1 : 2;
                defaultGame.skipPlayers(skipCount);
                defaultGame.setGameMessage("FOUR! Skipped " + skipCount + " player(s).");
                break;
            case FIVE: // Draw 1
                drawN(p, 1);
                defaultGame.setGameMessage("FIVE! Drew 1 card.");
                break;
            case SIX: // Skip 1
                defaultGame.skipPlayers(1);
                defaultGame.setGameMessage("SIX! Skipped next player.");
                break;
            case NINE: // Skip 1
                defaultGame.skipPlayers(1);
                defaultGame.setGameMessage("NINE! Skipped next player.");
                break;
            case JACK: // Reverse (or Skip in 2-player)
                if (playerCount == 2) {
                    defaultGame.skipPlayers(1);
                    defaultGame.setGameMessage("JACK! Skipped next player.");
                } else {
                    defaultGame.reverseDirection();
                    defaultGame.setGameMessage("JACK! Reversed play direction!");
                }
                break;
            default:
                defaultGame.setGameMessage("Discarded " + card.getDisplayString() + ".");
        }
    }

    private void drawN(Player p, int n) {
        for (int i = 0; i < n; i++) {
            if (defaultGame.getDrawPile().isEmpty())
                reshuffleDeck();
            if (!defaultGame.getDrawPile().isEmpty())
                p.getHand().add(defaultGame.getDrawPile().pop());
        }
    }

    private void endTurn() {
        if (!defaultGame.isGameOver()) {
            // Mutate neural network once per completed turn
            com.aces.game.ai.GlobalAi.getInstance().mutate(0.1, 0.05);

            defaultGame.nextTurn();
            // If next player is CPU, set pending flag for animated step execution
            if (!defaultGame.getCurrentPlayer().isPc()) {
                defaultGame.setCpuTurnPending(true);
            } else {
                defaultGame.setCpuTurnPending(false);
            }
        }
    }

    /**
     * Computes a signed reward for a live-game move from the perspective of the acting player.
     *
     * @param preDistSelf  Player's distance to Ace BEFORE the move.
     * @param postDistSelf Player's distance to Ace AFTER the move.
     * @param preDistOpp   Closest opponent distance to Ace BEFORE the move.
     * @return Reward signal in range roughly [-1, +3].
     */
    private double computeLiveReward(double preDistSelf, double postDistSelf, double preDistOpp) {
        double progress = preDistSelf - postDistSelf; // positive = got closer
        double reward;
        if (progress > 0) {
            reward = Math.min(progress * 0.5, 3.0);   // e.g. closing 2 steps = +1.0
            // Boost if we're now ahead of closest opponent
            if (postDistSelf < preDistOpp) reward *= 1.4;
        } else if (progress == 0) {
            // No stack change (discard, pass-after-play): mild reward only if already leading
            reward = (postDistSelf < preDistOpp) ? 0.2 : 0.0;
        } else {
            // Got further away — penalise
            reward = -0.5 * Math.abs(progress);
        }
        return reward;
    }

    /**
     * Returns the closest distance-to-Ace among all NON-human (CPU) opponents
     * in the current live game. Used for relative reward computation.
     */
    private double getClosestOpponentDistance() {
        Player self = defaultGame.getCurrentPlayer();
        double closest = 14.0;
        for (Player p : defaultGame.getPlayers()) {
            if (p == self) continue;
            double d = com.aces.game.ai.AiInputMapper.getDistanceToAce(p);
            closest = Math.min(closest, d);
        }
        return closest;
    }

    /** Sleeps for the given delay (clamped 0-5000ms). */
    private void cpuDelay(int delayMs) {
        int clamped = Math.max(0, Math.min(5000, delayMs));
        if (clamped <= 0) return;
        try {
            Thread.sleep(clamped);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Executes ONE step of a CPU turn. Called by controller for animated
     * progression. Split into draw phase and action phase with page refresh
     * in between so the drawn card is visible.
     * @param delayMs milliseconds to pause after each sub-action.
     */
    public void processCpuStep(int delayMs) {
        if (defaultGame.getPhase() != GameState.Phase.PLAYING)
            return;

        Player current = defaultGame.getCurrentPlayer();
        if (current.isPc()) // If human, don't process
            return;

        // Initial delay before CPU starts acting
        cpuDelay(delayMs);

        // PHASE 1: Draw phase - draw card and return so the page refreshes
        if (!defaultGame.isHasDrawn()) {
            if (defaultGame.getDrawPile().isEmpty())
                reshuffleDeck();
            if (!defaultGame.getDrawPile().isEmpty()) {
                Card drawn = defaultGame.getDrawPile().pop();
                current.getHand().add(drawn);
                defaultGame.setHasDrawn(true);
                defaultGame.setCpuLastDrawnCard(drawn); // Track for face-up display
                defaultGame.setLastAction(current.getName() + " drew a card.");
                defaultGame.setCpuTurnPending(true); // Will trigger another cpu-step call
            }
            return; // Return early so page refreshes and shows the drawn card
        }

        // PHASE 2: Action phase - clear the drawn card highlight and execute
        defaultGame.setCpuLastDrawnCard(null);

        // PHASE 2a: If there's a pending effect, resolve ONE step and return
        if (defaultGame.getEffectState() != GameState.EffectState.NONE) {
            GameState.EffectState stateBefore = defaultGame.getEffectState();
            resolveCpuEffect(current);
            // Safety: if effect state didn't change, force-clear to prevent infinite loop
            if (defaultGame.getEffectState() == stateBefore) {
                System.out.println("CPU SAFETY: Effect state stuck at " + stateBefore + ", force-clearing.");
                defaultGame.setEffectState(GameState.EffectState.NONE);
                endTurn();
            }
            // If more effects remain, keep cpuTurnPending true for next step
            if (defaultGame.getEffectState() != GameState.EffectState.NONE) {
                defaultGame.setCpuTurnPending(true);
            } else {
                // Effects done - set pending based on whose turn it is now
                if (!defaultGame.getCurrentPlayer().isPc())
                    defaultGame.setCpuTurnPending(true);
                else
                    defaultGame.setCpuTurnPending(false);
            }
            return;
        }

        // PHASE 2b: Normal action (no pending effects)
        executeCpuStep(current, delayMs);
    }

    private void executeCpuStep(Player cpu, int delayMs) {
        // Skip if somehow called for human player
        if (cpu.isPc()) {
            defaultGame.setCpuTurnPending(false);
            return;
        }

        if (defaultGame.isGameOver())
            return;

        // 2. Brain Decision
        List<Double> inputs = com.aces.game.ai.AiInputMapper.extractInputs(defaultGame, cpu);
        List<Double> outputs = com.aces.game.ai.GlobalAi.getInstance().feedForward(inputs);

        /* ACTIONS: 0: PASS, 1: D-SKIP, 2: D-ATK, 3: D-NRM, 4-57: STACK Specific Card */
        boolean acted = false;

        // --- Multi-card stack loop ---
        // Keep stacking as long as the brain picks stack actions and valid plays remain.
        boolean keepStacking = true;
        while (keepStacking && !defaultGame.isGameOver()) {
            keepStacking = false;

            // Re-run brain with updated state after each card placed
            final List<Double> freshInputs = com.aces.game.ai.AiInputMapper.extractInputs(defaultGame, cpu);
            final List<Double> freshOutputs = com.aces.game.ai.GlobalAi.getInstance().feedForward(freshInputs);
            inputs = freshInputs;
            outputs = freshOutputs;
            List<Integer> freshActions = new ArrayList<>();
            for (int i = 0; i < freshOutputs.size(); i++) freshActions.add(i);
            freshActions.sort((a, b) -> Double.compare(freshOutputs.get(b), freshOutputs.get(a)));

            for (int action : freshActions) {
                if (action >= 4) { // PLAY STACK Specific Card
                    int targetCardIndex = action - 4;
                    int handIdx = -1;
                    for (int i = 0; i < cpu.getHand().size(); i++) {
                        if (com.aces.game.ai.AiInputMapper.getCardIndex(cpu.getHand().get(i)) == targetCardIndex) {
                            handIdx = i;
                            break;
                        }
                    }

                    if (handIdx != -1) {
                        Card card = cpu.getHand().get(handIdx);
                        Card top = cpu.getTopStack();
                        boolean valid = (top == null)
                                ? (card.getRank() != Card.Rank.ACE && card.getRank() != Card.Rank.JOKER)
                                : isSequenceValid(cpu, top, card);

                        if (valid) {
                            playToStack(cpu.getId(), handIdx);
                            acted = true;
                            cpuDelay(delayMs / 2); // Shorter delay between chained cards
                            if (defaultGame.isGameOver()) return;
                            // If Joker was played, a JOKER_STACK_VALUE effect is pending — let it resolve first
                            if (defaultGame.getEffectState() != GameState.EffectState.NONE) {
                                defaultGame.setCpuTurnPending(true);
                                return;
                            }
                            keepStacking = true; // Try to stack another card
                            break;
                        }
                    }
                } else if (action >= 1 && action <= 3) { // DISCARD (1: SKIP, 2: ATTACK, 3: NORMAL)
                    if (!defaultGame.isHasPlayedToStack()) {
                        String cat = (action == 1) ? "SKIP" : (action == 2) ? "ATTACK" : "NORMAL";
                        int idx = findBestDiscard(cpu, cat);
                        if (idx == -1 && !cat.equals("NORMAL"))
                            idx = findBestDiscard(cpu, "NORMAL");

                        if (idx != -1) {
                            discardAndEffect(cpu.getId(), idx);
                            acted = true;
                            if (defaultGame.getEffectState() != GameState.EffectState.NONE) {
                                defaultGame.setCpuTurnPending(true);
                            } else if (!defaultGame.getCurrentPlayer().isPc()) {
                                defaultGame.setCpuTurnPending(true);
                            } else {
                                defaultGame.setCpuTurnPending(false);
                            }
                        }
                    }
                    break; // Discard ends the action phase — no stacking after
                } else if (action == 0) { // PASS
                    if (defaultGame.isHasPlayedToStack()) {
                        acted = true;
                        skipTurn(cpu.getId());
                    }
                    break; // Pass ends the action phase
                }
            }
        }

        // Fallback / Pass if network completely failed to pick a valid move
        if (!acted && !defaultGame.isGameOver()) {
            if (defaultGame.isHasPlayedToStack()) {
                skipTurn(cpu.getId());
            } else {
                if (!cpu.getHand().isEmpty()) {
                    int idx = findBestDiscard(cpu, "NORMAL");
                    if (idx != -1) {
                        discardAndEffect(cpu.getId(), idx);
                        if (defaultGame.getEffectState() != GameState.EffectState.NONE) {
                            defaultGame.setCpuTurnPending(true);
                        }
                        return;
                    }
                }
                skipTurn(cpu.getId());
            }
        }
    }

    private void resolveCpuEffect(Player cpu) {
        switch (defaultGame.getEffectState()) {
            case QUEEN_PICK:
                // Pick index 0
                if (!defaultGame.getTempBuffer().isEmpty()) {
                    handleInteraction(cpu.getId(), "0");
                }
                break;
            case JOKER_PICK:
                if (!defaultGame.getTempBuffer().isEmpty()) {
                    handleInteraction(cpu.getId(), "0");
                }
                break;
            case SELECT_TARGET:
                // Target Human (Player 1) or Random
                Player target = defaultGame.getPlayers().stream()
                        .filter(p -> !p.getId().equals(cpu.getId()))
                        .findAny().orElse(null);

                if (target != null) {
                    handleInteraction(cpu.getId(), target.getId());
                } else {
                    // Fallback
                    defaultGame.setEffectState(GameState.EffectState.NONE);
                    defaultGame.nextTurn();
                }
                break;
            case JOKER_STACK_VALUE:
                // CPU must choose a value for the Joker
                // Find a valid adjacent rank to the card below (at size-2)
                // Joker is already on top, so look at one below
                Card below = null;
                if (cpu.getStack().size() >= 2) {
                    below = cpu.getStack().get(cpu.getStack().size() - 2);
                }

                String choice = null;
                if (below != null) {
                    // Use jokerStackValue if the card below is also a Joker
                    Card.Rank belowRank = below.getRank();
                    if (belowRank == Card.Rank.JOKER && cpu.getJokerStackValue() != null) {
                        belowRank = cpu.getJokerStackValue();
                    }
                    int ord = belowRank.ordinal();
                    // Try upper adjacent
                    if (ord < Card.Rank.values().length - 1) {
                        Card.Rank upper = Card.Rank.values()[ord + 1];
                        if (upper != Card.Rank.TWO && upper != Card.Rank.ACE && upper != Card.Rank.KING
                                && upper != Card.Rank.JOKER) {
                            choice = upper.name();
                        }
                    }
                    // Try lower adjacent if upper was invalid
                    if (choice == null && ord > 0) {
                        Card.Rank lower = Card.Rank.values()[ord - 1];
                        if (lower != Card.Rank.TWO && lower != Card.Rank.ACE && lower != Card.Rank.KING
                                && lower != Card.Rank.JOKER) {
                            choice = lower.name();
                        }
                    }
                }

                // Fallback: if no valid choice found, force-clear the effect
                if (choice == null) {
                    System.out.println("CPU: No valid Joker stack value found, clearing effect.");
                    defaultGame.setEffectState(GameState.EffectState.NONE);
                    endTurn();
                } else {
                    handleInteraction(cpu.getId(), choice);
                }
                break;
            case SEVEN_PASS_CARD:
                // CPU gives first card in hand
                if (!cpu.getHand().isEmpty()) {
                    handleInteraction(cpu.getId(), "0");
                } else {
                    // Should not happen if logic is correct, but safe fallback
                    defaultGame.setEffectState(GameState.EffectState.NONE);
                    defaultGame.nextTurn();
                }
                break;
            case QUEEN_ORDER:
                // CPU orders cards: pick 0 to be on top
                if (!defaultGame.getTempBuffer().isEmpty()) {
                    handleInteraction(cpu.getId(), "0");
                } else {
                    defaultGame.setEffectState(GameState.EffectState.NONE);
                    defaultGame.nextTurn();
                }
                break;
            case EIGHT_CHOOSE_SOURCE:
                // Intelligent CPU choice for stealing
                // Prioritize Stack (if valid) > Hand > Discard
                String eightTargetId = defaultGame.getEightTargetPlayerId();
                Player eightTarget = defaultGame.getPlayers().stream()
                        .filter(pl -> pl.getId().equals(eightTargetId))
                        .findFirst().orElse(null);

                String sourceChoice = null;
                if (eightTarget != null) {
                    if (eightTarget.getStack().size() > 1) {
                        sourceChoice = "stack";
                    } else if (!eightTarget.getHand().isEmpty()) {
                        sourceChoice = "hand";
                    } else if (!eightTarget.getDiscardPile().isEmpty()) {
                        sourceChoice = "discard";
                    }
                }
                if (sourceChoice != null) {
                    handleInteraction(cpu.getId(), sourceChoice);
                } else {
                    // Target has nothing to steal, clear effect and end turn
                    System.out.println("CPU: Eight target has no stealable cards, clearing effect.");
                    defaultGame.setEightTargetPlayerId(null);
                    defaultGame.setEffectState(GameState.EffectState.NONE);
                    endTurn();
                }
                break;
            case EIGHT_PICK_CARD:
                // Blind pick from hand (always pick index 0 for now)
                handleInteraction(cpu.getId(), "0");
                break;
            case JOKER_CHOICE_MODE:
                // CPU AI: prefer STACK mode if any card in tempBuffer fits the stack sequence,
                // otherwise use HAND mode to recover a card to hand
                boolean preferStack = false;
                Card cpuTop = cpu.getTopStack();
                for (Card c : defaultGame.getTempBuffer()) {
                    boolean fits;
                    if (cpuTop == null) {
                        fits = (c.getRank() != Card.Rank.JOKER && c.getRank() != Card.Rank.ACE);
                    } else {
                        fits = isSequenceValid(cpu, cpuTop, c);
                    }
                    if (fits) {
                        preferStack = true;
                        break;
                    }
                }
                handleInteraction(cpu.getId(), preferStack ? "STACK" : "HAND");
                break;
            default:
                defaultGame.setEffectState(GameState.EffectState.NONE);
                defaultGame.nextTurn();
        }
    }

    // --- AI Heuristics ---

    private int findBestPlayToStack(Player p) {
        Card top = p.getTopStack();
        int bestIdx = -1;
        int bestVal = -1;
        for (int i = 0; i < p.getHand().size(); i++) {
            Card c = p.getHand().get(i);
            boolean valid = (top == null) ? (c.getRank() != Card.Rank.ACE && c.getRank() != Card.Rank.JOKER)
                    : isSequenceValid(p, top, c);
            if (valid) {
                int val = getAiCardValue(c);
                if (val > bestVal) {
                    bestVal = val;
                    bestIdx = i;
                }
            }
        }
        return bestIdx;
    }

    private int findBestDiscard(Player p, String category) {
        int bestIdx = -1;
        int bestScore = -999;

        // Prioritize specific categories if requested
        for (int i = 0; i < p.getHand().size(); i++) {
            Card c = p.getHand().get(i);
            boolean matches = false;

            if (category.equals("SKIP"))
                matches = (c.getRank() == Card.Rank.JACK || c.getRank() == Card.Rank.KING
                        || c.getRank() == Card.Rank.FOUR);
            else if (category.equals("ATTACK"))
                matches = (c.getRank() == Card.Rank.TWO || c.getRank() == Card.Rank.JOKER
                        || c.getRank() == Card.Rank.SEVEN || c.getRank() == Card.Rank.EIGHT);
            else
                matches = true; // Normal matches all

            if (matches) {
                int score = getAiCardValue(c);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
        }

        // If specific category not found, return -1 (Caller handles fallback)
        // Except for Normal, which should find *something* unless hand is empty.
        if (bestIdx == -1 && !category.equals("NORMAL")) {
            return -1;
        }
        return bestIdx;
    }

    private int getAiCardValue(Card c) {
        if (c == null)
            return 0;
        if (c.getRank() == Card.Rank.JOKER)
            return 15;
        if (c.getRank() == Card.Rank.ACE)
            return 14;
        return c.getRank().ordinal() + 1; // 2..13
    }
}
