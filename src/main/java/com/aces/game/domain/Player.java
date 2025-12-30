package com.aces.game.domain;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack; // Added import for Stack

@Data
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
}
