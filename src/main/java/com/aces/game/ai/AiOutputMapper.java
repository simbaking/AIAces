package com.aces.game.ai;

import com.aces.game.domain.Card;
import com.aces.game.domain.Player;

import java.util.List;

public class AiOutputMapper {

    /**
     * Translates Neural Network outputs into a Game Action.
     * 
     * Output Nodes Convention:
     * 0: "Pass" / "End Turn" (Score)
     * 1..20: "Play Card [i-1] to Stack" (Score)
     * 21..40: "Discard Card [i-21]" (Score)
     * 
     * Returns a simple object or string command to be parsed by GameService.
     */
    public static class ActionDecision {
        public enum ActionType {
            PASS, PLAY, DISCARD
        }

        public ActionType type;
        public int cardIndex; // Index in hand
        public double confidence;
    }

    public static ActionDecision decide(List<Double> outputs, Player p) {
        // Find the index of the highest score
        int maxIdx = -1;
        double maxVal = -1.0;

        // Mask invalid moves:
        // - Cannot play/discard index >= hand size
        // - Cannot pass if haven't acted? (Rules vary, but AI should learn validity.
        // For now, we might prioritize valid moves manually if AI is dumb initially)

        for (int i = 0; i < outputs.size(); i++) {
            // Check logical validity first to filter dumb moves
            boolean possible = isOutputPossible(i, p);

            if (possible && outputs.get(i) > maxVal) {
                maxVal = outputs.get(i);
                maxIdx = i;
            }
        }

        ActionDecision decision = new ActionDecision();
        decision.confidence = maxVal;

        if (maxIdx == 0) {
            decision.type = ActionDecision.ActionType.PASS;
        } else if (maxIdx >= 1 && maxIdx <= 20) {
            decision.type = ActionDecision.ActionType.PLAY;
            decision.cardIndex = maxIdx - 1;
        } else if (maxIdx >= 21 && maxIdx <= 40) {
            decision.type = ActionDecision.ActionType.DISCARD;
            decision.cardIndex = maxIdx - 21;
        } else {
            // Fallback
            decision.type = ActionDecision.ActionType.PASS;
        }

        return decision;
    }

    private static boolean isOutputPossible(int idx, Player p) {
        int handSize = p.getHand().size();
        if (idx == 0)
            return true; // Pass always "possible" in theory
        if (idx >= 1 && idx <= 20) {
            // Play Card i
            int cardIdx = idx - 1;
            return cardIdx < handSize;
        }
        if (idx >= 21 && idx <= 40) {
            // Discard Card i
            int cardIdx = idx - 21;
            return cardIdx < handSize;
        }
        return false;
    }
}
