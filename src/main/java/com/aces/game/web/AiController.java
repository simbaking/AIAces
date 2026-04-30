package com.aces.game.web;

import com.aces.game.ai.AiInputMapper;
import com.aces.game.ai.GlobalAi;
import com.aces.game.ai.NeuralNetwork;
import com.aces.game.ai.TrainingMode;
import com.aces.game.domain.GameState;
import com.aces.game.domain.Player;
import com.aces.game.service.GameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class AiController {

    private final GameService gameService;
    private final com.aces.game.ai.BackgroundTrainer backgroundTrainer;

    public AiController(GameService gameService, com.aces.game.ai.BackgroundTrainer backgroundTrainer) {
        this.gameService = gameService;
        this.backgroundTrainer = backgroundTrainer;
    }

    @GetMapping("/ai/training-status")
    public Map<String, Object> getTrainingStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("isRunning", backgroundTrainer.isRunning());
        response.put("gamesPlayed", backgroundTrainer.getGamesPlayed());
        response.put("trainingMode", backgroundTrainer.getTrainingMode().name());
        return response;
    }

    /**
     * Switch training mode at runtime.
     * Example: POST /ai/set-training-mode?mode=STACK_FOCUS
     *          POST /ai/set-training-mode?mode=STANDARD
     */
    @PostMapping("/ai/set-training-mode")
    public Map<String, Object> setTrainingMode(@RequestParam String mode) {
        Map<String, Object> response = new HashMap<>();
        try {
            TrainingMode tm = TrainingMode.valueOf(mode.toUpperCase());
            backgroundTrainer.setTrainingMode(tm);
            response.put("success", true);
            response.put("trainingMode", tm.name());
            response.put("message", "Training mode switched to " + tm.name());
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("error", "Unknown mode '" + mode + "'. Valid values: STANDARD, STACK_FOCUS");
        }
        return response;
    }

    @GetMapping("/ai/state/{playerId}")
    public Map<String, Object> getAiState(@PathVariable String playerId) {
        Map<String, Object> response = new HashMap<>();

        try {
            GameState game = gameService.getGame();
            if (game == null) {
                System.out.println("AiController: Game is NULL");
                return response;
            }

            // Find player
            Player p = game.getPlayers().stream()
                    .filter(pl -> pl.getId().equalsIgnoreCase(playerId))
                    .findFirst().orElse(null);

            // Fallback: Find ANY CPU
            if (p == null) {
                System.out.println("AiController: ID " + playerId + " not found. Falling back to first CPU.");
                p = game.getPlayers().stream().filter(pl -> !pl.isPc()).findFirst().orElse(null);
            }

            if (p != null) {
                NeuralNetwork brain = GlobalAi.getInstance();

                // Inputs
                List<Double> inputs = AiInputMapper.extractInputs(game, p);

                // Outputs
                List<Double> outputs = brain.feedForward(inputs);

                response.put("inputs", inputs);
                response.put("strategy", brain.getLastStrategyValues());
                response.put("outputs", outputs);
                response.put("brain", brain); // Include full structure for visualization
            } else {
                System.out.println("AiController: No CPU player found!");
            }

        } catch (Exception e) {
            e.printStackTrace();
            response.put("error", e.getMessage());
        }

        return response;
    }
}
