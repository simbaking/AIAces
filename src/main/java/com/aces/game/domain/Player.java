package com.aces.game.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack; // Added import for Stack

public class Player {
    private String id;
    private String name;
    private boolean isPc; // True if Human, False if CPU (legacy naming)

    private List<Card> hand = new ArrayList<>();
    private Stack<Card> stack = new Stack<>();
    private List<Card> discardPile = new ArrayList<>();

    // When a Joker is on top of the stack, this stores what rank it represents
    private Card.Rank jokerStackValue = null;

    // AI Brain removed - using GlobalAi
    // private com.aces.game.ai.NeuralNetwork brain;

    public Player(String id, String name, boolean isPc) {
        this.id = id;
        this.name = name;
        this.isPc = isPc;
        this.hand = new ArrayList<>();
        this.stack = new Stack<>();
        this.discardPile = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isPc() {
        return isPc;
    }

    public void setPc(boolean pc) {
        isPc = pc;
    }

    public List<Card> getHand() {
        return hand;
    }

    public void setHand(List<Card> hand) {
        this.hand = hand;
    }

    public Stack<Card> getStack() {
        return stack;
    }

    public void setStack(Stack<Card> stack) {
        this.stack = stack;
    }

    public List<Card> getDiscardPile() {
        return discardPile;
    }

    public void setDiscardPile(List<Card> discardPile) {
        this.discardPile = discardPile;
    }

    public Card.Rank getJokerStackValue() {
        return jokerStackValue;
    }

    public void setJokerStackValue(Card.Rank jokerStackValue) {
        this.jokerStackValue = jokerStackValue;
    }

    public Card getLastDiscard() {
        if (discardPile.isEmpty())
            return null;
        return discardPile.get(discardPile.size() - 1);
    }

    public Card getTopStack() {
        if (stack.isEmpty())
            return null;
        return stack.get(stack.size() - 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Player player = (Player) o;
        return id != null ? id.equals(player.id) : player.id == null;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "Player(" + "id=" + id + ", name=" + name + ", isPc=" + isPc + ')';
    }
}
