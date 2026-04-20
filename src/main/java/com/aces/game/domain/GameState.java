package com.aces.game.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

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
        JOKER_CHOICE_MODE, // Joker Step 1: Choose Stack (x2) or Hand (x1)
        JOKER_PICK, // Joker Step 2: Pick the cards
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
    private boolean hasDiscarded = false;
    private boolean hasPlayedToStack = false;

    // CPU Turn Animation Support
    private boolean cpuTurnPending = false;
    private String lastAction = ""; // Describes what the last player did
    private Card cpuLastDrawnCard = null; // Track the card CPU just drew (for face-up display)

    // Turn order mechanics
    private int playDirection = 1; // 1 = forward, -1 = reverse
    private int skipsRemaining = 0; // Number of players to skip

    // For 8 card effect in 3+ players (store the selected target)
    private String eightTargetPlayerId;

    // The visible card under the draw deck (determines which 7s are active)
    private Card cardUnderDeck;

    // For 7 card effect (store the selected target)
    private String sevenTargetPlayerId;

    // For Joker discard effect (track count of picked cards)
    private int jokerPickCount = 0;
    private boolean jokerModeToStack = true; // true = 2 cards to stack, false = 1 card to hand

    // ===== Getters and Setters =====

    public List<Player> getPlayers() {
        return players;
    }

    public void setPlayers(List<Player> players) {
        this.players = players;
    }

    public Stack<Card> getDrawPile() {
        return drawPile;
    }

    public void setDrawPile(Stack<Card> drawPile) {
        this.drawPile = drawPile;
    }

    public Stack<Card> getDiscardPile() {
        return discardPile;
    }

    public void setDiscardPile(Stack<Card> discardPile) {
        this.discardPile = discardPile;
    }

    public int getCurrentPlayerIndex() {
        return currentPlayerIndex;
    }

    public void setCurrentPlayerIndex(int currentPlayerIndex) {
        this.currentPlayerIndex = currentPlayerIndex;
    }

    public String getGameMessage() {
        return gameMessage;
    }

    public void setGameMessage(String gameMessage) {
        this.gameMessage = gameMessage;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public void setGameOver(boolean gameOver) {
        this.gameOver = gameOver;
    }

    public Player getWinner() {
        return winner;
    }

    public void setWinner(Player winner) {
        this.winner = winner;
    }

    public int getInitialCpuCount() {
        return initialCpuCount;
    }

    public void setInitialCpuCount(int initialCpuCount) {
        this.initialCpuCount = initialCpuCount;
    }

    public String getInitialPlayerName() {
        return initialPlayerName;
    }

    public void setInitialPlayerName(String initialPlayerName) {
        this.initialPlayerName = initialPlayerName;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public EffectState getEffectState() {
        return effectState;
    }

    public void setEffectState(EffectState effectState) {
        this.effectState = effectState;
    }

    public List<Card> getTempBuffer() {
        return tempBuffer;
    }

    public void setTempBuffer(List<Card> tempBuffer) {
        this.tempBuffer = tempBuffer;
    }

    public String getPendingToasterPlayerId() {
        return pendingToasterPlayerId;
    }

    public void setPendingToasterPlayerId(String pendingToasterPlayerId) {
        this.pendingToasterPlayerId = pendingToasterPlayerId;
    }

    public Card getPendingEffectCard() {
        return pendingEffectCard;
    }

    public void setPendingEffectCard(Card pendingEffectCard) {
        this.pendingEffectCard = pendingEffectCard;
    }

    public Card.Rank getEffectSourceRank() {
        return effectSourceRank;
    }

    public void setEffectSourceRank(Card.Rank effectSourceRank) {
        this.effectSourceRank = effectSourceRank;
    }

    public boolean isAwaitingEffectChoice() {
        return awaitingEffectChoice;
    }

    public void setAwaitingEffectChoice(boolean awaitingEffectChoice) {
        this.awaitingEffectChoice = awaitingEffectChoice;
    }

    public String getEffectType() {
        return effectType;
    }

    public void setEffectType(String effectType) {
        this.effectType = effectType;
    }

    public boolean isHasDrawn() {
        return hasDrawn;
    }

    public void setHasDrawn(boolean hasDrawn) {
        this.hasDrawn = hasDrawn;
    }

    public boolean isHasDiscarded() {
        return hasDiscarded;
    }

    public void setHasDiscarded(boolean hasDiscarded) {
        this.hasDiscarded = hasDiscarded;
    }

    public boolean isHasPlayedToStack() {
        return hasPlayedToStack;
    }

    public void setHasPlayedToStack(boolean hasPlayedToStack) {
        this.hasPlayedToStack = hasPlayedToStack;
    }

    public boolean isCpuTurnPending() {
        return cpuTurnPending;
    }

    public void setCpuTurnPending(boolean cpuTurnPending) {
        this.cpuTurnPending = cpuTurnPending;
    }

    public String getLastAction() {
        return lastAction;
    }

    public void setLastAction(String lastAction) {
        this.lastAction = lastAction;
    }

    public Card getCpuLastDrawnCard() {
        return cpuLastDrawnCard;
    }

    public void setCpuLastDrawnCard(Card cpuLastDrawnCard) {
        this.cpuLastDrawnCard = cpuLastDrawnCard;
    }

    public int getPlayDirection() {
        return playDirection;
    }

    public void setPlayDirection(int playDirection) {
        this.playDirection = playDirection;
    }

    public int getSkipsRemaining() {
        return skipsRemaining;
    }

    public void setSkipsRemaining(int skipsRemaining) {
        this.skipsRemaining = skipsRemaining;
    }

    public String getEightTargetPlayerId() {
        return eightTargetPlayerId;
    }

    public void setEightTargetPlayerId(String eightTargetPlayerId) {
        this.eightTargetPlayerId = eightTargetPlayerId;
    }

    public Card getCardUnderDeck() {
        return cardUnderDeck;
    }

    public void setCardUnderDeck(Card cardUnderDeck) {
        this.cardUnderDeck = cardUnderDeck;
    }

    public String getSevenTargetPlayerId() {
        return sevenTargetPlayerId;
    }

    public void setSevenTargetPlayerId(String sevenTargetPlayerId) {
        this.sevenTargetPlayerId = sevenTargetPlayerId;
    }

    public int getJokerPickCount() {
        return jokerPickCount;
    }

    public void setJokerPickCount(int jokerPickCount) {
        this.jokerPickCount = jokerPickCount;
    }

    public boolean isJokerModeToStack() {
        return jokerModeToStack;
    }

    public void setJokerModeToStack(boolean jokerModeToStack) {
        this.jokerModeToStack = jokerModeToStack;
    }

    // ===== Game Logic Methods =====

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
        hasPlayedToStack = false;
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

    public boolean isJokerChoiceMode() {
        return effectState == EffectState.JOKER_CHOICE_MODE;
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

    public boolean hasDrawn() {
        return hasDrawn;
    }
}
