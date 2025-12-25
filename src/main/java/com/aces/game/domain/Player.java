package com.aces.game.domain;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class Player {
    private String id;
    private String name;
    private boolean pc; // "Player Character" (Human)

    private List<Card> hand = new ArrayList<>();
    private List<Card> stack = new ArrayList<>();
    private List<Card> discardPile = new ArrayList<>();

    // When a Joker is on top of the stack, this stores what rank it represents
    private Card.Rank jokerStackValue = null;

    public Player(String id, String name, boolean pc) {
        this.id = id;
        this.name = name;
        this.pc = pc;
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
