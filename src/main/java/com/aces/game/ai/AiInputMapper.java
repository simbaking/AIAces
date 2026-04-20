package com.aces.game.ai;

import com.aces.game.domain.Card;
import com.aces.game.domain.GameState;
import com.aces.game.domain.Player;

import java.util.ArrayList;
import java.util.List;

public class AiInputMapper {

    /**
     * Converts the current GameState into a normalized list of inputs (0.0 - 1.0).
     *
     * Fixed Input Size: 96
     * Structure:
     * [0] Player Count
     * [1] My Position (Turn Order)
     * [2-5] Self Data (TopStack, StackSize, LastDiscard, HandSize)
     * [6-33] Opponent Data (7 opponents * 4 inputs each)
     * [34] Deck Count
     * [35] Draw Pile Size
     * [36] Bottom Rank
     * [37] Bottom Suit
     * [38-91] Specific Hand Cards
     * [92-95] Aggro-Specific (DistDiff, AvgOppDist, MinDist, ClosestPlayer)
     */
    public static List<Double> extractInputs(GameState state, Player self) {
        List<Double> inputs = new ArrayList<>();

        // 1. # of Players
        inputs.add(state.getPlayers().size() / 8.0); // Normalize for max 8

        // 2. My Position (Normalized)
        int selfIdx = state.getPlayers().indexOf(self);
        int playerCount = state.getPlayers().size();
        inputs.add(selfIdx / 7.0);

        // Relative Player Info
        // Loop for MAX 8 players (Self + 7 Opponents)
        // Self is index 0 in this relative loop
        for (int i = 0; i < 8; i++) {
            if (i < playerCount) {
                // Actual Player
                int relativeIdx = (selfIdx + i) % playerCount;
                Player p = state.getPlayers().get(relativeIdx);

                // 1. Top Stack Rank
                inputs.add(normalizeRank(p.getTopStack()));
                // 2. Stack Size
                inputs.add(p.getStack().size() / 54.0);
                // 3. Top Discard Rank
                inputs.add(normalizeRank(p.getLastDiscard()));
                // 4. Hand Count
                inputs.add(p.getHand().size() / 20.0);
            } else {
                // Padding for missing players
                inputs.add(0.0);
                inputs.add(0.0);
                inputs.add(0.0);
                inputs.add(0.0);
            }
        }

        // 19. Deck Count
        int numDecks = (state.getPlayers().size() > 2) ? 2 : 1;
        inputs.add(numDecks / 4.0);

        // 20. Generic Deck Makeup (Draw Pile Size)
        inputs.add(state.getDrawPile().size() / 54.0);

        // Card under Deck
        inputs.add(normalizeRank(state.getCardUnderDeck()));
        if (state.getCardUnderDeck() != null) {
            inputs.add(normalizeSuit(state.getCardUnderDeck()));
        } else {
            inputs.add(0.0);
        }

        // === HAND SPECIFIC INPUTS (Index 38-91) ===
        double[] handInputs = new double[54];
        for (Card c : self.getHand()) {
            int idx = getCardIndex(c);
            handInputs[idx] += 1.0;
        }
        for (double v : handInputs) {
            inputs.add(v);
        }

        // === AGGRO-SPECIFIC INPUTS (Index 92-95) ===
        // Calculate distance to Ace for self and all opponents
        double myDistToAce = getDistanceToAce(self);
        double totalOppDist = 0;
        double minDist = myDistToAce;
        int closestPlayerIdx = 0; // Relative to self (0 = self)

        for (int i = 1; i < playerCount; i++) { // Start at 1 to skip self
            int relativeIdx = (selfIdx + i) % playerCount;
            Player opp = state.getPlayers().get(relativeIdx);
            double oppDist = getDistanceToAce(opp);
            totalOppDist += oppDist;

            if (oppDist < minDist) {
                minDist = oppDist;
                closestPlayerIdx = i; // Relative position
            }
        }

        double avgOppDist = (playerCount > 1) ? totalOppDist / (playerCount - 1) : 14.0;

        // [92] DistDiff: My distance minus avg opponent distance (negative = I'm ahead)
        inputs.add((myDistToAce - avgOppDist) / 14.0);

        // [93] AvgOppDist: Average opponent distance to Ace
        inputs.add(avgOppDist / 14.0);

        // [94] MinDist: Smallest distance to Ace among all players
        inputs.add(minDist / 14.0);

        // [95] ClosestPlayer: Relative position of closest player (0=self,
        // 1-7=opponents)
        inputs.add(closestPlayerIdx / 7.0);

        return inputs;
    }

    /**
     * Calculate distance to Ace (how many ranks away from Ace).
     * Ace = 0 (already there), Two = 1, ... King = 12, Empty stack = 14.
     */
    public static double getDistanceToAce(Player p) {
        Card top = p.getTopStack();
        if (top == null)
            return 14.0; // No stack = max distance
        if (top.getRank() == Card.Rank.JOKER)
            return 0.0; // Joker = instant win

        int rankValue = switch (top.getRank()) {
            case ACE -> 0; // Already at Ace!
            case TWO -> 1;
            case THREE -> 2;
            case FOUR -> 3;
            case FIVE -> 4;
            case SIX -> 5;
            case SEVEN -> 6;
            case EIGHT -> 7;
            case NINE -> 8;
            case TEN -> 9;
            case JACK -> 10;
            case QUEEN -> 11;
            case KING -> 12;
            case JOKER -> 0;
        };
        return rankValue;
    }

    private static double normalizeRank(Card c) {
        if (c == null)
            return 0.0;
        if (c.getRank() == Card.Rank.JOKER)
            return 1.0;

        // Map Ace=1, Two=2 ... King=13. Normalized / 14.
        int val = switch (c.getRank()) {
            case ACE -> 1;
            case TWO -> 2;
            case THREE -> 3;
            case FOUR -> 4;
            case FIVE -> 5;
            case SIX -> 6;
            case SEVEN -> 7;
            case EIGHT -> 8;
            case NINE -> 9;
            case TEN -> 10;
            case JACK -> 11;
            case QUEEN -> 12;
            case KING -> 13;
            case JOKER -> 14;
        };
        return val / 14.0;
    }

    private static double normalizeSuit(Card c) {
        if (c == null)
            return 0.0;
        return switch (c.getSuit()) {
            case SPADES -> 0.0;
            case HEARTS -> 0.33;
            case CLUBS -> 0.66;
            case DIAMONDS -> 1.0;
            default -> 0.0;
        };
    }

    /**
     * Maps any valid standard deck card to a unique integer from 0 to 53.
     * Order: Spades (0-12), Hearts (13-25), Clubs (26-38), Diamonds (39-51), Jokers (52, 53).
     * Inside each suit, ranks go TWO (0) to ACE (12).
     */
    public static int getCardIndex(Card c) {
        if (c.getRank() == Card.Rank.JOKER) {
            // Technically deck has 2 identical jokers, we can just return 52 for both for action mapping purposes
            return 52;
        }
        
        int suitOffset = switch (c.getSuit()) {
            case SPADES -> 0;
            case HEARTS -> 13;
            case CLUBS -> 26;
            case DIAMONDS -> 39;
            case JOKER -> 52; // Fallback
        };
        
        int rankOffset = switch (c.getRank()) {
            case TWO -> 0;
            case THREE -> 1;
            case FOUR -> 2;
            case FIVE -> 3;
            case SIX -> 4;
            case SEVEN -> 5;
            case EIGHT -> 6;
            case NINE -> 7;
            case TEN -> 8;
            case JACK -> 9;
            case QUEEN -> 10;
            case KING -> 11;
            case ACE -> 12;
            case JOKER -> 0; // Handled above
        };
        
        return suitOffset + rankOffset;
    }
}
