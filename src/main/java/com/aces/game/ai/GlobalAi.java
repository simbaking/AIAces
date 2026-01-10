package com.aces.game.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;

public class GlobalAi {

    private static NeuralNetwork INSTANCE;
    private static final String FILE_PATH = "brain.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    static {
        // Try to load
        try {
            File f = new File(FILE_PATH);
            if (f.exists()) {
                System.out.println("GlobalAi: Loading existing brain from " + f.getAbsolutePath());
                INSTANCE = mapper.readValue(f, NeuralNetwork.class);

                // Verify Input Size (Neuron Weights in first layer = Input Size)
                int checkSize = INSTANCE.getStrategyLayers().get(0).getNeurons().get(0).getWeights().size();

                // Verify Execution Layer Input Size (should be 41: 38 Inputs + 3 Strategy)
                // Also verify Output Layer Size (should be 5)
                int execSize = 0;
                int outputSize = 0;
                if (!INSTANCE.getExecutionLayers().isEmpty()) {
                    execSize = INSTANCE.getExecutionLayers().get(0).getNeurons().get(0).getWeights().size();
                }
                if (INSTANCE.getOutputLayer() != null && !INSTANCE.getOutputLayer().getNeurons().isEmpty()) {
                    outputSize = INSTANCE.getOutputLayer().getNeurons().size();
                }

                // Verify Bottleneck Size (should be 36: 32 Strategy + 4 Aggro)
                int bnSize = 0;
                if (INSTANCE.getStrategyBottleneck() != null && !INSTANCE.getStrategyBottleneck().getNeurons().isEmpty()) {
                    bnSize = INSTANCE.getStrategyBottleneck().getNeurons().get(0).getWeights().size();
                }

                // Verify Plan Pre-Layers exist (3 layers, 5 neurons each)
                boolean hasPlanLayers = INSTANCE.getPlanPreLayers() != null 
                    && INSTANCE.getPlanPreLayers().size() == 3
                    && INSTANCE.getPlanPreLayers().get(0).getNeurons().size() == 5;

                // Expected execution input size: 3 (strategy) + 5 (planPost) + 42 (inputs) = 50
                if (checkSize != 38 || execSize != 50 || outputSize != 5 || bnSize != 36 || !hasPlanLayers) {
                    System.out.println("GlobalAi: Mismatched brain topology (In=" + checkSize + ", ExecIn=" + execSize
                            + ", Out=" + outputSize + ", Bn=" + bnSize + ", PlanLayers=" + hasPlanLayers + "). Resetting to new architecture with Plan networks.");
                    INSTANCE = new NeuralNetwork(42, 5);
                }
            } else {
                System.out.println("GlobalAi: Creating new brain.");
                INSTANCE = new NeuralNetwork(42, 5);
            }
        } catch (Exception e) {
            System.err.println("GlobalAi: Failed to load brain. Starting fresh. Error: " + e.getMessage());
            e.printStackTrace();
            INSTANCE = new NeuralNetwork(42, 5);
        }
    }

    public static NeuralNetwork getInstance() {
        return INSTANCE;
    }

    public static void save() {
        try {
            mapper.writeValue(new File(FILE_PATH), INSTANCE);
            System.out.println("GlobalAi: Brain saved to " + FILE_PATH);
        } catch (IOException e) {
            System.err.println("GlobalAi: Failed to save brain! " + e.getMessage());
        }
    }
}
