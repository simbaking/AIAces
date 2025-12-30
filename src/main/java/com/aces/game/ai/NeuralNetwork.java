package com.aces.game.ai;

import java.util.ArrayList;
import java.util.List;

public class NeuralNetwork {
    // --- Architecture Blocks ---
    private List<Layer> strategyLayers; // First 5 layers
    private Layer strategyBottleneck; // The 3-node layer (Aggression, Hoarding, Planning)
    private List<Layer> executionLayers; // Next 5 layers
    private Layer outputLayer; // Final actions

    // Captured State for Visualization
    private List<Double> lastStrategyValues; // Values of the 3 strategy nodes

    // Storage for backprop
    private List<List<Double>> layerActivations = new ArrayList<>();

    public NeuralNetwork() {
    } // Default for serialization

    public NeuralNetwork(int inputSize, int outputSize) {
        this.strategyLayers = new ArrayList<>();
        this.executionLayers = new ArrayList<>();

        // -- Block 1: Strategy Formulation --
        // 5 Layers that process the standard 38 inputs
        int strategyWidth = 32;
        int standardInputs = 38; // Standard inputs (not counting Aggro-specific)
        strategyLayers.add(new Layer(strategyWidth, standardInputs));
        for (int i = 0; i < 4; i++) {
            strategyLayers.add(new Layer(strategyWidth, strategyWidth));
        }

        // -- Bottleneck: Strategy Definition --
        // Aggro neuron: 32 (from Strategy) + 4 (Aggro inputs) = 36 weights
        // Hoard/Plan neurons: 32 weights each
        // We create the bottleneck with 3 neurons, all with 36 inputs,
        // but Hoard/Plan weights 32-35 will be zeroed (no connection)
        this.strategyBottleneck = new Layer(3, strategyWidth + 4);

        // Zero out Aggro-specific weights for Hoard (neuron 1) and Plan (neuron 2)
        for (int n = 1; n <= 2; n++) {
            Neuron neuron = strategyBottleneck.getNeurons().get(n);
            List<Double> w = neuron.getWeights();
            for (int i = 32; i < 36; i++) {
                w.set(i, 0.0); // No Aggro input connection
            }
        }

        // -- Block 2: Tactical Execution --
        // 5 Layers that act on Strategy
        // Execution Input Size = 3 (Strategy) + inputSize (Residual)
        int executionInputSize = 3 + inputSize;
        int executionWidth = 32;

        Layer execL0 = new Layer(executionWidth, executionInputSize);
        executionLayers.add(execL0);

        // REDUCE Residual Connection Weights (Indices 3 to end)
        for (Neuron n : execL0.getNeurons()) {
            List<Double> w = n.getWeights();
            for (int i = 3; i < w.size(); i++) {
                w.set(i, w.get(i) * 0.1); // 10% strength
            }
        }

        for (int i = 0; i < 4; i++) {
            executionLayers.add(new Layer(executionWidth, executionWidth));
        }

        // -- Output --
        this.outputLayer = new Layer(outputSize, executionWidth);
    }

    // Storing inputs for training is complex. Switched to Evolutionary/Mutation
    // approach for MVP "Training" visualization.

    // Storage for backprop - defined above

    public List<Double> feedForward(List<Double> inputs) {
        layerActivations.clear();
        layerActivations.add(inputs); // Input is layer 0 activation

        // 1. Run Strategy Block (uses first 38 standard inputs)
        List<Double> standardInputs = inputs.subList(0, 38);
        List<Double> currentStrat = standardInputs;
        for (Layer l : strategyLayers) {
            currentStrat = l.feedForward(currentStrat);
            layerActivations.add(currentStrat);
        }

        // 2. Prepare Bottleneck Input (32 from Strategy + 4 Aggro-specific)
        // Aggro inputs are at indices 38-41
        List<Double> bnInput = new ArrayList<>(currentStrat); // 32 values from Strategy
        if (inputs.size() >= 42) {
            bnInput.add(inputs.get(38)); // DistDiff
            bnInput.add(inputs.get(39)); // AvgOppDist
            bnInput.add(inputs.get(40)); // MinDist
            bnInput.add(inputs.get(41)); // ClosestPlayer
        } else {
            // Fallback for old input size
            bnInput.add(0.0);
            bnInput.add(0.0);
            bnInput.add(0.0);
            bnInput.add(0.0);
        }

        // 3. Run Bottleneck (36 inputs)
        List<Double> strategyValues = strategyBottleneck.feedForward(bnInput);
        this.lastStrategyValues = strategyValues;
        layerActivations.add(strategyValues);

        // 4. Prepare Execution Input (Strategy + Residual Inputs)
        List<Double> executionIn = new ArrayList<>();
        executionIn.addAll(strategyValues);
        executionIn.addAll(inputs); // Reduced residual connection
        layerActivations.add(executionIn);

        // 5. Run Execution Block
        List<Double> current = executionIn;
        for (Layer l : executionLayers) {
            current = l.feedForward(current);
            layerActivations.add(current);
        }

        // 6. Output
        return outputLayer.feedForward(current);
    }

    // Backpropagation for Reinforcement Learning
    public void train(List<Double> inputs, int actionIndex, double reward) {
        // Re-run feedForward to populate layerActivations just in case context changed
        feedForward(inputs);

        // Output Layer Training
        // Input to OutputLayer is the last stored activation from Execution Block
        List<Double> inputToLast = layerActivations.get(layerActivations.size() - 1);

        Neuron actionNode = outputLayer.getNeurons().get(actionIndex);
        double output = actionNode.getValue();
        double error = reward - output;
        double delta = error * output * (1 - output);

        double learningRate = 0.2;

        actionNode.adjustWeights(inputToLast, delta, learningRate);
    }

    public void mutate(double rate, double strength) {
        // Randomly adjust weights - This simulates "Training" (Search)
        mutateLayer(outputLayer, rate, strength);
        mutateLayer(strategyBottleneck, rate, strength);
        for (Layer l : executionLayers)
            mutateLayer(l, rate, strength);
        for (Layer l : strategyLayers)
            mutateLayer(l, rate, strength);
    }

    private void mutateLayer(Layer l, double rate, double strength) {
        for (Neuron n : l.getNeurons()) {
            if (Math.random() < rate) {
                // Mutate weights
                for (int i = 0; i < n.getWeights().size(); i++) {
                    double w = n.getWeights().get(i);
                    w += (Math.random() * 2 - 1) * strength;
                    n.getWeights().set(i, w);
                }
            }
        }
    }

    // --- Getters for Visualization ---
    public List<Double> getLastStrategyValues() {
        return lastStrategyValues;
    }

    public List<Layer> getStrategyLayers() {
        return strategyLayers;
    }

    public Layer getStrategyBottleneck() {
        return strategyBottleneck;
    }

    public List<Layer> getExecutionLayers() {
        return executionLayers;
    }

    public Layer getOutputLayer() {
        return outputLayer;
    }

    // --- Setters for JSON Serialization ---
    public void setStrategyLayers(List<Layer> strategyLayers) {
        this.strategyLayers = strategyLayers;
    }

    public void setStrategyBottleneck(Layer strategyBottleneck) {
        this.strategyBottleneck = strategyBottleneck;
    }

    public void setExecutionLayers(List<Layer> executionLayers) {
        this.executionLayers = executionLayers;
    }

    public void setOutputLayer(Layer outputLayer) {
        this.outputLayer = outputLayer;
    }

    public void setLastStrategyValues(List<Double> lastStrategyValues) {
        this.lastStrategyValues = lastStrategyValues;
    }

    public List<List<Double>> getLayerActivations() {
        return layerActivations;
    }

    public void setLayerActivations(List<List<Double>> layerActivations) {
        this.layerActivations = layerActivations;
    }
}
