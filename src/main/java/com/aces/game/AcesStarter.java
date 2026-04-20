package com.aces.game;

import org.springframework.boot.SpringApplication;

/**
 * Ace's Starter - Entry point to launch the AIAces server.
 */
public class AcesStarter {

    public static void main(String[] args) {
        System.out.println("=================================");
        System.out.println("   Ace's Starter - Launching...  ");
        System.out.println("=================================");
        SpringApplication.run(AcesGameApplication.class, args);
    }
}
