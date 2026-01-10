package com.aces.game;

import org.springframework.boot.SpringApplication;

public class ServerStarter {

    public static void main(String[] args) {
        System.out.println("Starting AIAces Server...");
        SpringApplication.run(AcesGameApplication.class, args);
    }
}
