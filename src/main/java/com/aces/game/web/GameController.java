package com.aces.game.web;

import com.aces.game.service.GameService;
import com.aces.game.domain.Card;
import com.aces.game.domain.Player;
import com.aces.game.domain.GameState;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import java.util.HashMap;
import java.util.Map;

@Controller
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/")
    public String home(Model model) {
        GameState game = gameService.getGame();
        // If game is already running or initialized, maybe direct there?
        // For now, always show Menu if phase is MENU, or redirect to /game if PLAYING
        if (game != null && game.getPhase() == GameState.Phase.PLAYING) {
            return "redirect:/game";
        }
        return "menu"; // New template
    }

    @PostMapping("/start")
    public String startGame(@RequestParam("playerName") String playerName,
            @RequestParam(defaultValue = "1") int cpuCount) {
        gameService.startGame(playerName, cpuCount);
        return "redirect:/game";
    }

    @GetMapping("/game")
    public String game(Model model) {
        GameState game = gameService.getGame();
        if (game == null || game.getPhase() == GameState.Phase.MENU) {
            return "redirect:/";
        }

        model.addAttribute("state", game); // for easier detailed rendering
        Player human = gameService.getGame().getPlayers().stream()
                .filter(p -> p.isPc()).findFirst().orElse(null);
        model.addAttribute("human", human);
        if (human == null) {
            // Should not happen if game started correctly
            return "redirect:/";
        }

        // Add target player for 8 card picking
        if (game.getEightTargetPlayerId() != null) {
            Player eightTarget = game.getPlayers().stream()
                    .filter(p -> p.getId().equals(game.getEightTargetPlayerId()))
                    .findFirst().orElse(null);
            model.addAttribute("eightTarget", eightTarget);
        }

        return "game";
    }

    @PostMapping("/game/draw")
    public String draw(@RequestParam String playerId) {
        gameService.drawCard(playerId);
        return "redirect:/game";
    }

    @PostMapping("/game/draw-ajax")
    @ResponseBody
    public Map<String, Object> drawAjax(@RequestParam String playerId) {
        Map<String, Object> result = new HashMap<>();
        Card drawn = gameService.drawCardAndReturn(playerId);
        if (drawn != null) {
            result.put("success", true);
            result.put("imagePath", drawn.getImagePath());
            result.put("display", drawn.getDisplayString());
        } else {
            result.put("success", false);
        }
        return result;
    }

    @PostMapping("/game/play")
    public String play(@RequestParam String playerId, @RequestParam int cardIndex) {
        gameService.playToStack(playerId, cardIndex);
        return "redirect:/game";
    }

    @PostMapping("/game/discard")
    public String discard(@RequestParam String playerId, @RequestParam int cardIndex) {
        gameService.discardAndEffect(playerId, cardIndex);
        return "redirect:/game";
    }

    @PostMapping("/game/restart")
    public String restart() {
        gameService.resetToMenu();
        return "redirect:/"; // Go back to Main Menu
    }

    @PostMapping("/game/effect")
    public String handleEffect(@RequestParam String playerId, @RequestParam String actionData) {
        gameService.handleInteraction(playerId, actionData);
        return "redirect:/game";
    }

    @PostMapping("/game/cpu-step")
    public String cpuStep() {
        gameService.processCpuStep();
        return "redirect:/game";
    }

    @PostMapping("/game/skip")
    public String skip(@RequestParam String playerId) {
        gameService.skipTurn(playerId);
        return "redirect:/game";
    }
}
