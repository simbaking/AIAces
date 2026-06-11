package com.aces.game.ai;

import com.aces.game.domain.Card;
import com.aces.game.domain.GameState;
import com.aces.game.domain.Player;

import java.util.ArrayList;
import java.util.List;

public class RuleBasedAi {

    /**
     * Decision tree for the hard-coded Rule-Based "Teacher" AI.
     * Evaluates the game state and returns the optimal action index (0-57).
     *
     * Actions:
     * 0: PASS
     * 1: D-SKIP (Discard skip cards: 4, 6, 9, J, K, 2)
     * 2: D-ATK (Discard interactive/attack cards: 7, 8, 10, Q, Joker)
     * 3: D-NRM (Discard normal cards: 3, 5, etc.)
     * 4-57: STACK (Play a specific card from hand to stack)
     */
    public static int getDecision(GameState state, Player self) {

        // 1. STACK PLAY PHASE
        // If we haven't played to the stack yet, or we have already but want to continue stacking
        if (canPlayToStack(state, self)) {
            // Find all valid stack cards in hand
            List<Card> validStackCards = getValidStackCards(self);

            if (!validStackCards.isEmpty()) {
                // Decision 1.1: Can I win RIGHT NOW?
                for (Card c : validStackCards) {
                    if (c.getRank() == Card.Rank.ACE) {
                        return getStackActionIndex(c);
                    }
                }

                // Decision 1.2: Which valid stack card gets me closer to Ace?
                Card bestStackCard = null;
                double bestImprovement = -1;
                double currentDist = AiInputMapper.getDistanceToAce(self);

                for (Card c : validStackCards) {
                    double simulatedDist = simulateDistanceAfterPlay(self, c);
                    double improvement = currentDist - simulatedDist;

                    // Prefer cards that actually move us closer to Ace
                    if (improvement > bestImprovement) {
                        bestImprovement = improvement;
                        bestStackCard = c;
                    }
                }

                if (bestImprovement > 0 && bestStackCard != null) {
                    return getStackActionIndex(bestStackCard);
                }

                // Decision 1.3: If no card moves us closer, play a Joker if it's our only move
                // and we are stuck, but basic strategy says save Joker unless necessary.
                // For now, if no improvement, we stop stacking.
            }
        }

        // 2. DISCARD / PASS PHASE
        // If we have already played to the stack, we MUST pass.
        if (state.isHasPlayedToStack()) {
            return -1; // SKIP TRAINING (Let the Neural Net learn to pass on its own)
        }

        // If we haven't played to the stack, we MUST discard.
        // Let's decide which discard type is best based on Game Theory.
        
        /* --- FUTURE DEVELOPMENT (Non-Stack Strategy) ---
        // Decision 2.1: Is an opponent about to win? (Threat Assessment)
        Player biggestThreat = null;
        double minThreatDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p == self) continue;
            double dist = AiInputMapper.getDistanceToAce(p);
            if (dist < minThreatDist) {
                minThreatDist = dist;
                biggestThreat = p;
            }
        }

        boolean isThreatImminent = (minThreatDist <= 2.0);

        if (isThreatImminent && biggestThreat != null) {
            // We need to attack or skip!
            if (hasCardCategory(self.getHand(), 2)) {
                return 2; // D-ATK (e.g. play 10 to shrink stack, or 7 to block)
            }
            if (hasCardCategory(self.getHand(), 1)) {
                return 1; // D-SKIP (e.g. skip their turn)
            }
        }

        // Decision 2.2: Do we have very few cards? (Hoarding)
        if (self.getHand().size() <= 3) {
            // Try to draw cards
            if (hasCardCategory(self.getHand(), 3)) { // 3 or 5 are NRM
                for (Card c : self.getHand()) {
                    if (c.getRank() == Card.Rank.THREE || c.getRank() == Card.Rank.FIVE) {
                        return 3; // D-NRM
                    }
                }
            }
            if (hasCardCategory(self.getHand(), 2)) { // Q is ATK category
                for (Card c : self.getHand()) {
                    if (c.getRank() == Card.Rank.QUEEN) {
                        return 2; // D-ATK
                    }
                }
            }
        }

        // Decision 2.3: General Discard (Trash)
        // Discard the card that is furthest from our current stack sequence.
        // BackgroundTrainer's findBestDiscard will handle the specific card choice within the category,
        // but we just need to return the category. 3 (NRM) is a safe fallback.
        if (hasCardCategory(self.getHand(), 3)) return 3;
        if (hasCardCategory(self.getHand(), 1)) return 1;
        if (hasCardCategory(self.getHand(), 2)) return 2;
        ------------------------------------------------ */

        return -1; // -1 means "No Teacher Action available for this state"
    }

    private static boolean canPlayToStack(GameState state, Player self) {
        if (self.getHand().isEmpty()) return false;
        // Even if we have played to stack, we can continue playing as long as we have valid cards
        return true;
    }

    private static List<Card> getValidStackCards(Player self) {
        List<Card> valid = new ArrayList<>();
        Card top = self.getTopStack();
        for (Card c : self.getHand()) {
            if (top == null) {
                if (c.getRank() != Card.Rank.ACE && c.getRank() != Card.Rank.JOKER) {
                    valid.add(c);
                }
            } else {
                if (isSequenceValid(self, top, c)) {
                    valid.add(c);
                }
            }
        }
        return valid;
    }

    private static boolean isSequenceValid(Player p, Card top, Card card) {
        if (top.getRank() == Card.Rank.ACE) return false;
        if (card.getRank() == Card.Rank.JOKER) return true;

        if (top.getRank() == Card.Rank.JOKER) {
            Card.Rank jokerValue = p.getJokerStackValue();
            if (jokerValue == null) return true;
            int diff = Math.abs(card.getRank().ordinal() - jokerValue.ordinal());
            if (jokerValue == Card.Rank.TWO && card.getRank() == Card.Rank.ACE) return true;
            return diff == 1;
        }

        int diff = Math.abs(card.getRank().ordinal() - top.getRank().ordinal());
        if (top.getRank() == Card.Rank.TWO && card.getRank() == Card.Rank.ACE) return true;
        return diff == 1;
    }

    private static int getStackActionIndex(Card card) {
        return 4 + AiInputMapper.getCardIndex(card);
    }

    private static double simulateDistanceAfterPlay(Player self, Card cardToPlay) {
        // Temporarily act as if the card is the top of the stack
        if (cardToPlay.getRank() == Card.Rank.ACE) return 0.0;
        
        // Use AiInputMapper logic
        if (cardToPlay.getRank() == Card.Rank.JOKER) {
            // Assume it will be placed optimally (e.g. +1 towards Ace)
            Card top = self.getTopStack();
            if (top != null) {
                int ord = top.getRank().ordinal();
                Card.Rank simulatedJokerRank = (ord < Card.Rank.values().length - 1) ? Card.Rank.values()[ord + 1] : Card.Rank.SEVEN;
                return getMinDistanceForRank(simulatedJokerRank);
            }
            return 6.0; 
        }

        return getMinDistanceForRank(cardToPlay.getRank());
    }

    private static double getMinDistanceForRank(Card.Rank rank) {
        int linearDist = switch (rank) {
            case ACE -> 0;
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
            case JOKER -> 6;
        };
        if (linearDist == 0) return 0;
        return Math.min(linearDist, 13 - linearDist);
    }

    private static boolean hasCardCategory(List<Card> hand, int category) {
        for (Card c : hand) {
            int cat = getDiscardActionIndex(c);
            if (cat == category) return true;
        }
        return false;
    }

    private static int getDiscardActionIndex(Card card) {
        if (isInteractiveEffect(card)) return 2; // D-ATK
        switch (card.getRank()) {
            case FOUR: case JACK: case KING: case TWO: case NINE:
                return 1; // D-SKIP / Instant
            default:
                return 3; // D-NRM
        }
    }

    private static boolean isInteractiveEffect(Card card) {
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
}
