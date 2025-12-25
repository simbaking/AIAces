package com.aces.game.domain;

import lombok.Data;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

@Data
public class GameState {
    private List<Player> players = new ArrayList<>();
    private java.util.Stack<Card> drawPile = new java.util.Stack<>();
    private java.util.Stack<Card> discardPile = new java.util.Stack<>();
    private int currentPlayerIndex = 0;
    private String gameMessage = "";
    private boolean gameOver = false;
    private Player winner;
    private int initialCpuCount = 1;
    private String initialPlayerName = "Player 1";

    // --- New Fields for Advanced Rules ---
    public enum Phase {
        MENU,
        PLAYING
    }

    public enum EffectState {
        NONE,
        QUEEN_PICK, // Queen Step 1: Pick 1 of 3
        QUEEN_ORDER, // Queen Step 2: Order remaining to Top
        JOKER_PICK, // Joker discard: Pick 2 from Discard
        SELECT_TARGET, // 7/8/10: Pick Opponent
        EIGHT_CHOOSE_SOURCE, // 8 in 3+ players: Choose hand/stack/discard
        EIGHT_PICK_CARD, // 8: Pick from opponent's face-down hand
        JOKER_STACK_VALUE, // Joker played to stack: Choose what rank it represents
        SEVEN_PASS_CARD // 7: Choose card from hand to give to target
    }

    private Phase phase = Phase.MENU;
    private EffectState effectState = EffectState.NONE;

    // Temporary storage for cards during selection (e.g. Queen peek)
    private List<Card> tempBuffer = new ArrayList<>();

    // For effect targeting
    private String pendingToasterPlayerId; // ID of player using effect
    private Card pendingEffectCard;

    // Effect state tracking
    private Card.Rank effectSourceRank;
    private boolean awaitingEffectChoice = false; // e.g., for Joker or 7
    private String effectType;
    private boolean hasDrawn = false;

    // CPU Turn Animation Support
    private boolean cpuTurnPending = false;
    private String lastAction = ""; // Describes what the last player did

    // Turn order mechanics
    private int playDirection = 1; // 1 = forward, -1 = reverse
    private int skipsRemaining = 0; // Number of players to skip

    // For 8 card effect in 3+ players (store the selected target)
    private String eightTargetPlayerId;

    // The visible card at the bottom of the draw pile
    private Card bottomFacingCard;

    // For 7 card effect (store the selected target)
    private String sevenTargetPlayerId;

    public Card getBottomFacingCard() {
        return bottomFacingCard;
    }

    public void setBottomFacingCard(Card bottomFacingCard) {
        this.bottomFacingCard = bottomFacingCard;
    }

    public String getSevenTargetPlayerId() {
        return sevenTargetPlayerId;
    }

    public void setSevenTargetPlayerId(String sevenTargetPlayerId) {
        this.sevenTargetPlayerId = sevenTargetPlayerId;
    }

    public Player getCurrentPlayer() {
        return players.get(currentPlayerIndex);
    }

    public void nextTurn() {
        // Handle skips
        int playersToAdvance = 1 + skipsRemaining;
        skipsRemaining = 0; // Reset after applying

        for (int i = 0; i < playersToAdvance; i++) {
            currentPlayerIndex = (currentPlayerIndex + playDirection + players.size()) % players.size();
        }

        hasDrawn = false;
        gameMessage = "It's " + getCurrentPlayer().getName() + "'s turn.";
    }

    public void reverseDirection() {
        playDirection *= -1;
    }

    public void skipPlayers(int count) {
        skipsRemaining = count;
    }

    // --- Helper Methods for UI ---
    public boolean isModalActive() {
        return effectState != EffectState.NONE;
    }

    public boolean isSelectTarget() {
        return effectState == EffectState.SELECT_TARGET;
    }

    public boolean isGiveCard() {
        return effectSourceRank == Card.Rank.SEVEN;
    }

    public boolean isTradeHands() {
        return effectSourceRank == Card.Rank.EIGHT;
    }

    public boolean isStealCard() {
        return effectSourceRank == Card.Rank.TEN;
    }

    public boolean isQueenPick() {
        return effectState == EffectState.QUEEN_PICK;
    }

    public boolean isQueenOrder() {
        return effectState == EffectState.QUEEN_ORDER;
    }

    public boolean isJokerPick() {
        return effectState == EffectState.JOKER_PICK;
    }

    public boolean isEightChooseSource() {
        return effectState == EffectState.EIGHT_CHOOSE_SOURCE;
    }

    public boolean isEightPickCard() {
        return effectState == EffectState.EIGHT_PICK_CARD;
    }

    public boolean isJokerStackValue() {
        return effectState == EffectState.JOKER_STACK_VALUE;
    }

    public boolean isSevenPassCard() {
        return effectState == EffectState.SEVEN_PASS_CARD;
    }
}
