package com.aces.game;

import org.springframework.boot.SpringApplication;
import java.io.BufferedReader;
import java.io.InputStreamReader;

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

        killExistingServer();

        SpringApplication.run(AcesGameApplication.class, args);
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
