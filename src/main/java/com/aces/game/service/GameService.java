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
    // initialCpuCount is stored in GameState effectively, but we can keep a default
    // here if needed.

    public GameState getGame() {
        return defaultGame;
    }

    public void startGame(String playerName, int cpuCount) {
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
            game.setBottomFacingCard(game.getDrawPile().get(0));
        }
    }

    public void reshuffleDeck() {
        if (!defaultGame.getDiscardPile().isEmpty()) {
            defaultGame.setGameMessage("Reshuffling discards...");
            System.out.println("Reshuffling deck...");

            // Move discard to draw
            defaultGame.getDrawPile().addAll(defaultGame.getDiscardPile());
            defaultGame.getDiscardPile().clear();
            Collections.shuffle(defaultGame.getDrawPile());

            if (!defaultGame.getDrawPile().isEmpty()) {
                defaultGame.setBottomFacingCard(defaultGame.getDrawPile().get(0));
            }
        }
    }

    public void drawCard(String playerId) {
        Player p = defaultGame.getCurrentPlayer();
        if (!p.getId().equals(playerId)) {
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

        if (cardIndex < 0 || cardIndex >= p.getHand().size())
            return;

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
            p.getHand().remove(cardIndex);
            p.getStack().add(card);

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
                defaultGame.setWinner(p);
                defaultGame.setGameMessage("WINNER! " + p.getName() + " placed the Ace!");
                return;
            }

            defaultGame.setGameMessage("Played " + card.getDisplayString() + " to stack.");
            endTurn();
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

    public void skipTurn(String playerId) {
        Player p = defaultGame.getCurrentPlayer();
        if (!p.getId().equals(playerId)) {
            return;
        }

        if (!defaultGame.isHasDrawn()) {
            defaultGame.setGameMessage("You must draw a card before skipping!");
            return;
        }

        defaultGame.setGameMessage("Skipped play phase.");
        endTurn();
    }

    public void discardAndEffect(String playerId, int cardIndex) {
        Player p = defaultGame.getCurrentPlayer();
        if (!p.getId().equals(playerId))
            return;

        if (cardIndex < 0 || cardIndex >= p.getHand().size())
            return;
        Card card = p.getHand().remove(cardIndex);
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
                // Auto skip?
                defaultGame.setEffectState(GameState.EffectState.NONE);
                endTurn();
            } else {
                defaultGame.setGameMessage("Joker played! Pick a card from your discard pile.");
            }
        } else if (card.getRank() == Card.Rank.SEVEN) {
            // Validate 7 BEFORE showing target selection
            if (isValidSeven(card)) {
                defaultGame.setGameMessage("Valid 7! Select a target to sabotage.");
            } else {
                // 7 doesn't meet suit/color requirements - no effect
                defaultGame.setGameMessage("7 discarded but didn't match bottom card condition. No effect.");
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

            case JOKER_PICK:
                try {
                    int pickIdx = Integer.parseInt(actionData);
                    if (pickIdx >= 0 && pickIdx < defaultGame.getTempBuffer().size()) {
                        Card picked = defaultGame.getTempBuffer().get(pickIdx);

                        // Remove from actual discard pile (Find first match)
                        // Note: defaultGame.getTempBuffer() was a shallow copy at init time?
                        // Actually in init, we did addAll.
                        // We need to find the card object in p.getDiscardPile().

                        boolean found = false;
                        for (int i = 0; i < p.getDiscardPile().size(); i++) {
                            // Don't pick the Joker we just played (which should be at end)
                            // But picked is definitely not the Joker because we removed it from buffer.
                            if (p.getDiscardPile().get(i).equals(picked)) {
                                p.getDiscardPile().remove(i);
                                found = true;
                                break;
                            }
                        }

                        if (found) {
                            // Also remove from global discard pile to prevent duplicates on reshuffle
                            defaultGame.getDiscardPile().remove(picked);
                            p.getHand().add(picked);
                            defaultGame.setGameMessage("Recovered " + picked.getDisplayString());
                        }

                        defaultGame.getTempBuffer().clear();
                        defaultGame.setEffectState(GameState.EffectState.NONE);
                        endTurn();
                    }
                } catch (NumberFormatException e) {
                }
                break;

            case SELECT_TARGET:
                Player target = defaultGame.getPlayers().stream()
                        .filter(pl -> pl.getId().equals(actionData))
                        .findFirst().orElse(null);

                if (target != null && !target.getId().equals(p.getId())) {
                    // For 8 in 3+ players, go to source selection
                    if (defaultGame.getEffectSourceRank() == Card.Rank.EIGHT &&
                            defaultGame.getPlayers().size() > 2) {
                        defaultGame.setEightTargetPlayerId(target.getId());
                        defaultGame.setEffectState(GameState.EffectState.EIGHT_CHOOSE_SOURCE);
                        defaultGame.setGameMessage(
                                "Choose: steal from " + target.getName() + "'s Hand, Stack, or Discard?");
                    } else {
                        applyTargetedEffect(p, target, defaultGame.getEffectSourceRank());
                        // Check if effect changed state (e.g., EIGHT goes to EIGHT_PICK_CARD)
                        if (defaultGame.getEffectState() == GameState.EffectState.NONE ||
                                defaultGame.getEffectState() == GameState.EffectState.SELECT_TARGET) {
                            defaultGame.setEffectState(GameState.EffectState.NONE);
                            endTurn();
                        }
                        // Otherwise, a new state was set (like EIGHT_PICK_CARD), don't end turn
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
                // actionData = card index in target's hand
                try {
                    int cardIdx = Integer.parseInt(actionData);
                    String pickTargetId = defaultGame.getEightTargetPlayerId();
                    Player pickTarget = defaultGame.getPlayers().stream()
                            .filter(pl -> pl.getId().equals(pickTargetId))
                            .findFirst().orElse(null);

                    if (pickTarget != null && cardIdx >= 0 && cardIdx < pickTarget.getHand().size()) {
                        Card stolen = pickTarget.getHand().remove(cardIdx);
                        p.getHand().add(stolen);
                        defaultGame.setGameMessage(
                                "EIGHT! Took " + stolen.getDisplayString() + " from " + pickTarget.getName() + "!");
                    }
                } catch (NumberFormatException e) {
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
                    defaultGame.setGameMessage("Joker is now acting as " + chosenRank + "!");
                    defaultGame.setEffectState(GameState.EffectState.NONE);
                    endTurn();
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
                        endTurn();
                    }
                } catch (NumberFormatException e) {
                }
                break;
        }
    }

    private boolean isValidSeven(Card seven) {
        Card bottom = defaultGame.getBottomFacingCard();
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

    private void applyTargetedEffect(Player source, Player target, Card.Rank rank) {
        switch (rank) {
            case EIGHT: // Pick card from hand (blind selection)
                if (defaultGame.getPlayers().size() > 2) {
                    // Start selection phase
                    defaultGame.setEightTargetPlayerId(target.getId());
                    defaultGame.setEffectState(GameState.EffectState.EIGHT_CHOOSE_SOURCE);
                    defaultGame.setGameMessage("Choose where to steal from: Hand, Stack, or Discard!");
                } else {
                    // Default behavior (<= 2 players): Steal from hand
                    if (!target.getHand().isEmpty()) {
                        defaultGame.setEightTargetPlayerId(target.getId());
                        defaultGame.setEffectState(GameState.EffectState.EIGHT_PICK_CARD);
                        defaultGame.setGameMessage("Pick a card from " + target.getName() + "'s hand!");
                    } else {
                        defaultGame.setGameMessage(target.getName() + " has no cards to steal!");
                        defaultGame.setEffectState(GameState.EffectState.NONE);
                        endTurn();
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
                    // Add to discard pile (they're destroyed)
                    defaultGame.getDiscardPile().addAll(taken);
                    defaultGame.setGameMessage("TEN! Took 3 cards from " + target.getName() + "'s stack!");
                } else {
                    defaultGame.setGameMessage(target.getName() + "'s stack needs 4+ cards!");
                }
                defaultGame.setEffectState(GameState.EffectState.NONE);
                endTurn();
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
                    endTurn();
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
                    endTurn();
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
            defaultGame.nextTurn();
            // If next player is CPU, set pending flag for animated step execution
            // If next player is CPU, set pending flag for animated step execution
            if (!defaultGame.getCurrentPlayer().isPc()) {
                defaultGame.setCpuTurnPending(true);
            } else {
                defaultGame.setCpuTurnPending(false);
            }
        }
    }

    /**
     * Executes ONE step of a CPU turn. Called by controller for animated
     * progression.
     * Returns true if the CPU turn is complete, false if more steps needed.
     */
    public boolean processCpuStep() {
        if (defaultGame.getCurrentPlayer().isPc()) {
            // Human's turn - no CPU step needed
            defaultGame.setCpuTurnPending(false);
            return true;
        }

        Player cpu = defaultGame.getCurrentPlayer();

        // Step 1: Draw if hand is empty
        if (cpu.getHand().isEmpty()) {
            if (defaultGame.getDrawPile().isEmpty())
                reshuffleDeck();
            if (!defaultGame.getDrawPile().isEmpty()) {
                Card drawn = defaultGame.getDrawPile().pop();
                cpu.getHand().add(drawn);
                defaultGame.setLastAction(cpu.getName() + " drew a card.");
                return false; // More steps may follow
            }
        }

        // Step 2: Try to play to stack
        Card top = cpu.getTopStack();
        for (int i = 0; i < cpu.getHand().size(); i++) {
            Card c = cpu.getHand().get(i);
            // Cannot start stack with Joker or Ace
            boolean isValid = (top == null) ? (c.getRank() != Card.Rank.JOKER && c.getRank() != Card.Rank.ACE)
                    : isSequenceValid(cpu, top, c);

            if (isValid) {
                cpu.getHand().remove(i);
                cpu.getStack().add(c);

                // Check win
                if (c.getRank() == Card.Rank.ACE) {
                    defaultGame.setGameOver(true);
                    defaultGame.setWinner(cpu);
                    defaultGame.setGameMessage("WINNER! " + cpu.getName() + " placed the Ace!");
                    defaultGame.setCpuTurnPending(false);
                    return true;
                }

                defaultGame.setLastAction(cpu.getName() + " played " + c.getDisplayString() + " to stack.");
                defaultGame.nextTurn();

                // Check if next player is also CPU
                // Check if next player is also CPU
                if (!defaultGame.getCurrentPlayer().isPc()) {
                    defaultGame.setCpuTurnPending(true);
                    return true;
                } else {
                    defaultGame.setCpuTurnPending(false);
                    return true;
                }
            }
        }

        // Step 3: Must discard
        if (!cpu.getHand().isEmpty()) {
            Card discarded = cpu.getHand().remove(0);
            cpu.getDiscardPile().add(discarded);
            defaultGame.getDiscardPile().add(discarded); // Add to global discard for effects

            // Handle effects if triggered
            if (isInteractiveEffect(discarded)) {
                defaultGame.setEffectState(getInitialEffectState(discarded));
                defaultGame.setEffectSourceRank(discarded.getRank());
                initializeInteraction(discarded, cpu);

                // Keep resolving effects until finished (handles multi-step like 7 or Queen)
                while (defaultGame.getEffectState() != GameState.EffectState.NONE) {
                    resolveCpuEffect(cpu);
                }
                // NOTE: Interactive effects handle endTurn()/nextTurn() internally when
                // complete.
                // We do NOT call defaultGame.nextTurn() here to avoid skipping the next player.
            } else {
                applyInstantEffect(discarded, cpu);
                defaultGame.setLastAction(cpu.getName() + " discarded " + discarded.getDisplayString() + ".");
                defaultGame.nextTurn();
            }

            // Check if next player is also CPU
            // Check if next player is also CPU
            if (!defaultGame.getCurrentPlayer().isPc()) {
                // Next is CPU, keep polling
                defaultGame.setCpuTurnPending(true);
                return true;
            } else {
                // Next is Human, stop polling
                defaultGame.setCpuTurnPending(false);
                return true;
            }
        }

        // Fallback: skip turn
        defaultGame.nextTurn();
        if (!defaultGame.getCurrentPlayer().isPc()) {
            defaultGame.setCpuTurnPending(true);
        } else {
            defaultGame.setCpuTurnPending(false);
        }
        return true;
    }

    private void executeCpuTurn() {
        Player cpu = defaultGame.getCurrentPlayer();

        // 1. Draw if hand empty
        if (cpu.getHand().isEmpty()) {
            if (defaultGame.getDrawPile().isEmpty())
                reshuffleDeck();
            if (!defaultGame.getDrawPile().isEmpty()) {
                cpu.getHand().add(defaultGame.getDrawPile().pop());
            }
        }

        // 2. Try to Play to Stack
        boolean played = false;
        Card top = cpu.getTopStack();

        // Strategy: Find FIRST valid card.
        for (int i = 0; i < cpu.getHand().size(); i++) {
            Card c = cpu.getHand().get(i);
            boolean isValid = false;

            if (top == null)
                isValid = true;
            else
                isValid = isSequenceValid(cpu, top, c);

            if (isValid) {
                // Play it
                playToStack(cpu.getId(), i);
                played = true;
                break;
            }
        }

        if (!played) {
            // Must Discard
            if (!cpu.getHand().isEmpty()) {
                // Random discard for now
                discardAndEffect(cpu.getId(), 0);

                // Handle Effect if triggered
                if (defaultGame.getEffectState() != GameState.EffectState.NONE) {
                    resolveCpuEffect(cpu);
                }
            } else {
                // Forced pass? (Shouldn't happen if we force draw)
                defaultGame.nextTurn();
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
                Card top = cpu.getTopStack();
                // Joker is already on top, so look at one below
                Card below = null;
                if (cpu.getStack().size() >= 2) {
                    below = cpu.getStack().get(cpu.getStack().size() - 2);
                }

                String choice = "SEVEN"; // Default fallback
                if (below != null) {
                    // Try to pick one that allows next play? Or just any valid adjacent.
                    // Simple logic: pick upper adjacent if valid
                    int ord = below.getRank().ordinal();
                    // Try upper
                    if (ord < Card.Rank.values().length - 1) {
                        Card.Rank upper = Card.Rank.values()[ord + 1];
                        if (upper != Card.Rank.TWO && upper != Card.Rank.ACE && upper != Card.Rank.KING
                                && upper != Card.Rank.JOKER) {
                            choice = upper.name();
                        }
                    }
                    // Try lower if upper invalid
                    if (choice.equals("SEVEN")) {
                        if (ord > 0) {
                            Card.Rank lower = Card.Rank.values()[ord - 1];
                            if (lower != Card.Rank.TWO && lower != Card.Rank.ACE && lower != Card.Rank.KING
                                    && lower != Card.Rank.JOKER) {
                                choice = lower.name();
                            }
                        }
                    }
                }

                handleInteraction(cpu.getId(), choice);
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
                String targetId = defaultGame.getEightTargetPlayerId();
                Player eightTarget = defaultGame.getPlayers().stream()
                        .filter(pl -> pl.getId().equals(targetId))
                        .findFirst().orElse(null);

                String sourceChoice = "hand"; // Default
                if (eightTarget != null) {
                    if (eightTarget.getStack().size() > 1) {
                        sourceChoice = "stack";
                    } else if (!eightTarget.getHand().isEmpty()) {
                        sourceChoice = "hand";
                    } else if (!eightTarget.getDiscardPile().isEmpty()) {
                        sourceChoice = "discard";
                    }
                }
                handleInteraction(cpu.getId(), sourceChoice);
                break;
            case EIGHT_PICK_CARD:
                // Blind pick from hand (always pick index 0 for now)
                handleInteraction(cpu.getId(), "0");
                break;
            default:
                defaultGame.setEffectState(GameState.EffectState.NONE);
                defaultGame.nextTurn();
        }
    }
}
