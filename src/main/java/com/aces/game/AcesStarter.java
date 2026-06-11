package com.aces.game;

import org.springframework.boot.SpringApplication;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import javax.swing.JOptionPane;

/**
 * Ace's Starter - Entry point to launch the AIAces server.
 * Automatically clears any existing server running on port 8080 before starting.
 */
public class AcesStarter {

    private static final int SERVER_PORT = 8080;

    public static void main(String[] args) {
        System.out.println("=================================");
        System.out.println("   Ace's Starter - Launching...  ");
        System.out.println("=================================");

        // Prompt for ngrok URL
        try {
            System.setProperty("java.awt.headless", "false");
            String url = JOptionPane.showInputDialog(null, 
                "Enter ngrok Public URL for AI Training (leave blank to skip):\n(e.g., https://your-id.ngrok-free.app)", 
                "AI Training Setup", JOptionPane.QUESTION_MESSAGE);
            
            if (url != null && !url.trim().isEmpty()) {
                System.setProperty("training.server.url", url.trim());
                System.out.println("[AcesStarter] Set training.server.url to: " + url.trim());
            } else {
                System.out.println("[AcesStarter] URL prompt skipped. Using existing env vars if any.");
            }
        } catch (Exception e) {
            System.out.println("[AcesStarter] GUI prompt not available: " + e.getMessage());
        }

        killExistingServer();
        gitPull();

        SpringApplication.run(AcesGameApplication.class, args);
    }

    /**
     * Pulls the latest code and weights from origin/latest-ai before starting.
     * This ensures brain_tf.json and any code fixes are always current.
     */
    private static void gitPull() {
        try {
            String repoDir = System.getProperty("user.dir");
            System.out.println("[AcesStarter] Pulling latest code and weights from git...");
            // git pull origin latest-ai --rebase --autostash
            ProcessBuilder pb = new ProcessBuilder("git", "pull", "origin", "latest-ai", "--rebase", "--autostash")
                    .directory(new java.io.File(repoDir))
                    .redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[git] " + line);
                }
            }
            int exit = proc.waitFor();
            if (exit == 0) {
                System.out.println("[AcesStarter] Git pull complete.");
            } else {
                System.out.println("[AcesStarter] Git pull exited with code " + exit + " (continuing anyway).");
            }
        } catch (Exception e) {
            System.out.println("[AcesStarter] Git pull skipped: " + e.getMessage());
        }
    }

    /**
     * Finds and kills any process currently running on SERVER_PORT.
     * Works on macOS/Linux and Windows.
     */
    private static void killExistingServer() {
        System.out.println("[AcesStarter] Checking for existing server on port " + SERVER_PORT + "...");
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String pid = null;

            if (os.contains("win")) {
                // Windows: netstat to find PID
                Process findProcess = Runtime.getRuntime().exec(
                    new String[]{"cmd", "/c", "netstat -ano | findstr :" + SERVER_PORT}
                );
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(findProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.contains("LISTENING")) {
                            String[] parts = line.split("\\s+");
                            pid = parts[parts.length - 1];
                            break;
                        }
                    }
                }
                if (pid != null && !pid.isEmpty()) {
                    System.out.println("[AcesStarter] Killing existing server (PID: " + pid + ")...");
                    Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/PID", pid});
                }
            } else {
                // macOS / Linux: lsof to find PID
                Process findProcess = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", "lsof -ti tcp:" + SERVER_PORT}
                );
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(findProcess.getInputStream()))) {
                    pid = reader.readLine();
                }
                if (pid != null && !pid.trim().isEmpty()) {
                    pid = pid.trim();
                    System.out.println("[AcesStarter] Killing existing server (PID: " + pid + ")...");
                    Runtime.getRuntime().exec(new String[]{"kill", "-9", pid});
                }
            }

            if (pid == null || pid.trim().isEmpty()) {
                System.out.println("[AcesStarter] No existing server found. Starting fresh.");
            } else {
                // Give the OS a moment to release the port
                Thread.sleep(1000);
                System.out.println("[AcesStarter] Existing server cleared. Starting new instance...");
            }

        } catch (Exception e) {
            System.out.println("[AcesStarter] Warning: Could not check/kill existing server: " + e.getMessage());
        }
    }
}
