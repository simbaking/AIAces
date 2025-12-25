package com.aces.game.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Card {
    public enum Suit {
        HEARTS, DIAMONDS, CLUBS, SPADES, JOKER
    }

    public enum Rank {
        TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE, TEN, JACK, QUEEN, KING, ACE, JOKER
    }

    private Suit suit;
    private Rank rank;
    
    // For CSS classes or display
    public String getDisplayString() {
        if (rank == Rank.JOKER) return "JOKER";
        return rank.toString() + " of " + suit.toString();
    }
    
    public String getCssClass() {
        if (rank == Rank.JOKER) return "joker";
        return suit.toString().toLowerCase() + "-" + rank.toString().toLowerCase();
    }

    public String getImagePath() {
        if (rank == Rank.JOKER) {
            // Randomly or fixed mapped. Let's use red_joker by default or based on ID?
            // Actually, let's just pick one.
            return "/images/cards/red_joker.png"; 
        }
        
        String rankStr;
        switch(rank) {
            case JACK: rankStr = "jack"; break;
            case QUEEN: rankStr = "queen"; break;
            case KING: rankStr = "king"; break;
            case ACE: rankStr = "ace"; break;
            default: rankStr = String.valueOf(rank.ordinal() + 2); break; // TWO -> 0+2=2
        }
        
        String suitStr = suit.toString().toLowerCase();
        
        return "/images/cards/" + rankStr + "_of_" + suitStr + ".png";
    }
}
