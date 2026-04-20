package com.aces.game.web;

import com.aces.game.ai.AiInputMapper;
import com.aces.game.ai.GlobalAi;
import com.aces.game.ai.NeuralNetwork;
import com.aces.game.domain.GameState;
import com.aces.game.domain.Player;
import com.aces.game.service.GameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
