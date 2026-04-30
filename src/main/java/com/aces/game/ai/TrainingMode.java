package com.aces.game.ai;

/**
 * Training modes for the BackgroundTrainer.
 *
 * STANDARD    – the original balanced training: 2/3 multi-player CPU-vs-CPU games
 *               plus 1/3 solo batch games with delta-amplification on improvement.
 *
 * STACK_FOCUS – a hyper-focused training mode that ONLY and HEAVILY rewards placing
 *               cards onto your own stack that bring you closer to Ace.
 *               Discards, opponent effects, and all other signals are zeroed out so the
 *               network receives an extremely clean reinforcement signal:
 *               "put the right card on your stack → win, everything else → nothing".
 */
public enum TrainingMode {
    STANDARD,
    STACK_FOCUS
}
