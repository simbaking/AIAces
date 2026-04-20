package com.aces.game.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.List;

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

                // Verify Plan Pre-Layers exist (5 layers, 10 neurons each, first layer takes 64 strategy outputs)
                boolean hasPlanPreLayers = INSTANCE.getPlanPreLayers() != null 
                    && INSTANCE.getPlanPreLayers().size() == 5
                    && INSTANCE.getPlanPreLayers().get(0).getNeurons().size() == 10
                    && INSTANCE.getPlanPreLayers().get(0).getNeurons().get(0).getWeights().size() == 64;

                // Verify Plan Post-Layers exist (5 layers, 10 neurons each, first layer takes 10 planPre outputs)
                boolean hasPlanPostLayers = INSTANCE.getPlanPostLayers() != null 
                    && INSTANCE.getPlanPostLayers().size() == 5
                    && INSTANCE.getPlanPostLayers().get(0).getNeurons().size() == 10
                    && INSTANCE.getPlanPostLayers().get(0).getNeurons().get(0).getWeights().size() == 10;

                // Expected execution input size: 3 (strategy) + 10 (planPost) + 96 (inputs) = 109
                if (checkSize != 38 || execSize != 109 || outputSize != 58 || bnSize != 68 || !hasPlanPreLayers || !hasPlanPostLayers) {
                    System.out.println("GlobalAi: Mismatched brain topology (In=" + checkSize + ", ExecIn=" + execSize
                            + ", Out=" + outputSize + ", Bn=" + bnSize + ", PlanPre=" + hasPlanPreLayers + ", PlanPost=" + hasPlanPostLayers + "). Resetting to new architecture.");
                    INSTANCE = new NeuralNetwork(96, 58);
                }
            } else {
                System.out.println("GlobalAi: Creating new brain.");
                INSTANCE = new NeuralNetwork(96, 58);
            }
        } catch (Exception e) {
            System.err.println("GlobalAi: Failed to load brain. Starting fresh. Error: " + e.getMessage());
            e.printStackTrace();
            INSTANCE = new NeuralNetwork(96, 58);
        }
    }

    public static NeuralNetwork getInstance() {
        return INSTANCE;
    }

    /** Thread-safe feedForward */
    public static synchronized List<Double> feedForwardSafe(List<Double> inputs) {
        return INSTANCE.feedForward(inputs);
    }

    /** Thread-safe train */
    public static synchronized void trainSafe(List<Double> inputs, int actionIndex, double reward) {
        INSTANCE.train(inputs, actionIndex, reward, 1.0);
    }

    /** Thread-safe train with multiplier */
    public static synchronized void trainSafe(List<Double> inputs, int actionIndex, double reward, double weightMultiplier) {
        INSTANCE.train(inputs, actionIndex, reward, weightMultiplier);
    }

    /** Thread-safe mutate */
    public static synchronized void mutateSafe(double rate, double strength) {
        INSTANCE.mutate(rate, strength);
    }

    public static synchronized void save() {
        try {
            mapper.writeValue(new File(FILE_PATH), INSTANCE);
            System.out.println("GlobalAi: Brain saved to " + FILE_PATH);
        } catch (IOException e) {
            System.err.println("GlobalAi: Failed to save brain! " + e.getMessage());
        }
    }
}
