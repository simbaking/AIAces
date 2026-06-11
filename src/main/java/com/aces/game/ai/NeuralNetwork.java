package com.aces.game.ai;

import java.util.ArrayList;
import java.util.List;

public class NeuralNetwork {
    // --- Architecture Blocks ---
    private List<Layer> strategyLayers; // First 5 layers
    private Layer strategyBottleneck; // The 3-node layer (Aggression, Hoarding, Planning)
    private List<Layer> planPreLayers; // 3 layers, 5 nodes - feeds INTO Plan bottleneck
    private List<Layer> planPostLayers; // 3 layers, 5 nodes - receives FROM Plan bottleneck (shared weights)
    private List<Layer> executionLayers; // Next 5 layers
    private Layer outputLayer; // Final actions

    // Captured State for Visualization
    private List<Double> lastStrategyValues; // Values of the 3 strategy nodes
    private List<Double> lastPlanPreValues; // Output of planPreLayers for visualization
    private List<Double> lastPlanPostValues; // Output of planPostLayers for visualization

    // Storage for backprop
    private List<List<Double>> layerActivations = new ArrayList<>();

    public NeuralNetwork() {
    } // Default for serialization

    public NeuralNetwork(int inputSize, int outputSize) {
        this.strategyLayers = new ArrayList<>();
        this.executionLayers = new ArrayList<>();

        // -- Block 1: Strategy Formulation --
        // 9 Layers that process the standard 38 inputs
        int strategyWidth = 64;
        int standardInputs = 38; // Standard inputs (not counting Aggro-specific)
        strategyLayers.add(new Layer(strategyWidth, standardInputs));
        for (int i = 0; i < 8; i++) {
            strategyLayers.add(new Layer(strategyWidth, strategyWidth));
        }

        // -- Bottleneck: Strategy Definition --
        // Aggro neuron: 64 (from Strategy) + 4 (Aggro inputs) = 68 weights
        // Hoard neuron: 64 (from Strategy) + 0 (Aggro inputs) = only uses 64 weights
        // Plan neuron: 64 (from Strategy) + 0 (Aggro inputs) = only uses 64 weights
        // We create all 3 neurons with 68 inputs
        this.strategyBottleneck = new Layer(3, strategyWidth + 4); // +4 Aggro

        // Zero out Aggro-specific weights (64-67) for Hoard (neuron 1) and Plan (neuron 2)
        for (int n = 1; n <= 2; n++) {
            Neuron neuron = strategyBottleneck.getNeurons().get(n);
            List<Double> w = neuron.getWeights();
            for (int i = 64; i < 68; i++) {
                w.set(i, 0.0); // No Aggro input connection
            }
        }

        // -- Plan-Specific Processing Networks --
        // 5 layers, 10 neurons each, first layer takes strategy output (64 values)
        int planWidth = 10;
        this.planPreLayers = new ArrayList<>();
        planPreLayers.add(new Layer(planWidth, strategyWidth)); // Layer 0: 64 (strategy output) -> 10
        planPreLayers.add(new Layer(planWidth, planWidth));      // Layer 1: 10 -> 10
        planPreLayers.add(new Layer(planWidth, planWidth));      // New interspersed layer
        planPreLayers.add(new Layer(planWidth, planWidth));      // Layer 2: 10 -> 10
        planPreLayers.add(new Layer(planWidth, planWidth));      // New interspersed layer

        // Post layers share weights with pre layers (will be synced, except layer 0)
        this.planPostLayers = new ArrayList<>();
        planPostLayers.add(new Layer(planWidth, planWidth));      // Layer 0: 10 (planPreOut) -> 10
        planPostLayers.add(new Layer(planWidth, planWidth));      // Layer 1: 10 -> 10
        planPostLayers.add(new Layer(planWidth, planWidth));      // New interspersed layer
        planPostLayers.add(new Layer(planWidth, planWidth));      // Layer 2: 10 -> 10
        planPostLayers.add(new Layer(planWidth, planWidth));      // New interspersed layer

        // Sync weights: copy from pre to post
        syncPlanWeights();

        // -- Block 2: Tactical Execution --
        // 9 Layers that act on Strategy
        // Execution Input Size = 3 (Strategy) + 10 (planPost output) + inputSize (Residual)
        int executionInputSize = 3 + planWidth + inputSize;
        int executionWidth = 64;

        Layer execL0 = new Layer(executionWidth, executionInputSize);
        executionLayers.add(execL0);

        // REDUCE Residual Connection Weights (Indices 3+planWidth to end)
        for (Neuron n : execL0.getNeurons()) {
            List<Double> w = n.getWeights();
            for (int i = 3 + planWidth; i < w.size(); i++) {
                w.set(i, w.get(i) * 0.1); // 10% strength
            }
        }

        for (int i = 0; i < 8; i++) {
            executionLayers.add(new Layer(executionWidth, executionWidth));
        }

        // -- Output --
        this.outputLayer = new Layer(outputSize, executionWidth);
    }

    /**
     * Sync weights from planPreLayers to planPostLayers.
     * This ensures both networks are identical.
     */
    public void syncPlanWeights() {
        if (planPreLayers == null || planPostLayers == null) return;
        // Skip layer 0: pre layer 0 has strategyWidth inputs, post layer 0 has planWidth inputs
        for (int layerIdx = 1; layerIdx < planPreLayers.size(); layerIdx++) {
            Layer preLayer = planPreLayers.get(layerIdx);
            Layer postLayer = planPostLayers.get(layerIdx);
            for (int neuronIdx = 0; neuronIdx < preLayer.getNeurons().size(); neuronIdx++) {
                Neuron preNeuron = preLayer.getNeurons().get(neuronIdx);
                Neuron postNeuron = postLayer.getNeurons().get(neuronIdx);
                // Copy weights
                for (int wIdx = 0; wIdx < preNeuron.getWeights().size(); wIdx++) {
                    postNeuron.getWeights().set(wIdx, preNeuron.getWeights().get(wIdx));
                }
                // Copy bias
                postNeuron.setBias(preNeuron.getBias());
            }
        }
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

        // 2. Run Plan Pre-Processing Network (32 strategy outputs -> 5 outputs)
        List<Double> planPreOut = new ArrayList<>(currentStrat);
        if (planPreLayers != null) {
            for (Layer l : planPreLayers) {
                planPreOut = l.feedForward(planPreOut);
            }
            this.lastPlanPreValues = planPreOut;
        }

        // 3. Prepare Bottleneck Input (32 from Strategy + 4 Aggro-specific)
        // Aggro inputs are at indices 38-41
        List<Double> bnInput = new ArrayList<>(currentStrat); // 32 values from Strategy
        if (inputs.size() >= 42) {
            bnInput.add(inputs.get(38)); // DistDiff
            bnInput.add(inputs.get(39)); // AvgOppDist
            bnInput.add(inputs.get(40)); // MinDist
            bnInput.add(inputs.get(41)); // ClosestPlayer
        } else {
            bnInput.add(0.0);
            bnInput.add(0.0);
            bnInput.add(0.0);
            bnInput.add(0.0);
        }

        // 4. Run Bottleneck (36 inputs)
        // Note: Plan neuron (index 2) receives additional influence from planPreOut
        List<Double> strategyValues = strategyBottleneck.feedForward(bnInput);
        
        // Modulate Plan value with planPreOut (add average of planPreOut)
        if (planPreOut != null && !planPreOut.isEmpty()) {
            double planBoost = planPreOut.stream().mapToDouble(d -> d).average().orElse(0.0);
            double originalPlan = strategyValues.get(2);
            // Sigmoid blend: keep in 0-1 range
            double modifiedPlan = 1.0 / (1.0 + Math.exp(-(originalPlan + planBoost * 0.5 - 0.5)));
            strategyValues.set(2, modifiedPlan);
        }
        
        this.lastStrategyValues = strategyValues;
        layerActivations.add(strategyValues);

        // 5. Run Plan Post-Processing Network (planPreOut -> 10 outputs, shared weights from layer 1+)
        List<Double> planPostOut = new ArrayList<>(planPreOut);
        if (planPostLayers != null) {
            syncPlanWeights(); // Ensure weights are synced before running (layers 1+)
            for (Layer l : planPostLayers) {
                planPostOut = l.feedForward(planPostOut);
            }
            this.lastPlanPostValues = planPostOut;
        }

        // 6. Prepare Execution Input (Strategy + PlanPost + Residual Inputs)
        List<Double> executionIn = new ArrayList<>();
        executionIn.addAll(strategyValues);      // 3 values
        executionIn.addAll(planPostOut);          // 5 values from Plan post-processing
        executionIn.addAll(inputs);               // 42 values (reduced residual connection)
        layerActivations.add(executionIn);

        // 7. Run Execution Block
        List<Double> current = executionIn;
        for (Layer l : executionLayers) {
            current = l.feedForward(current);
            layerActivations.add(current);
        }

        // 8. Output
        return outputLayer.feedForward(current);
    }

    // Backpropagation for Reinforcement Learning
    public void train(List<Double> inputs, int actionIndex, double reward, double weightMultiplier) {
        // Re-run feedForward to populate layerActivations just in case context changed
        feedForward(inputs);

        // Output Layer Training
        // Input to OutputLayer is the last stored activation from Execution Block
        List<Double> inputToLast = layerActivations.get(layerActivations.size() - 1);

        Neuron actionNode = outputLayer.getNeurons().get(actionIndex);
        double output = actionNode.getValue();
        double error = reward - output;
        double delta = error * output * (1 - output);

        // Adam optimizer converges much faster, so we use a lower base learning rate
        double learningRate = 0.05 * weightMultiplier;

        actionNode.adjustWeightsAdam(inputToLast, delta, learningRate);
    }

    public void train(List<Double> inputs, int actionIndex, double reward) {
        train(inputs, actionIndex, reward, 1.0);
    }

    public void mutate(double rate, double strength) {
        // Randomly adjust weights - This simulates "Training" (Search)
        mutateLayer(outputLayer, rate, strength);
        mutateLayer(strategyBottleneck, rate, strength);
        for (Layer l : executionLayers)
            mutateLayer(l, rate, strength);
        for (Layer l : strategyLayers)
            mutateLayer(l, rate, strength);
        // Mutate planPreLayers (planPostLayers will be synced)
        if (planPreLayers != null) {
            for (Layer l : planPreLayers)
                mutateLayer(l, rate, strength);
            syncPlanWeights(); // Keep post in sync with pre
        }
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

    /**
     * Creates a fully independent deep copy of this neural network.
     * All layers, neurons, weights, and biases are duplicated so mutations
     * on the copy do not affect the original.
     */
    public NeuralNetwork deepCopy() {
        NeuralNetwork copy = new NeuralNetwork();

        // Copy strategy layers
        copy.strategyLayers = new ArrayList<>();
        for (Layer l : this.strategyLayers) {
            copy.strategyLayers.add(deepCopyLayer(l));
        }

        // Copy strategy bottleneck
        copy.strategyBottleneck = deepCopyLayer(this.strategyBottleneck);

        // Copy plan pre layers
        if (this.planPreLayers != null) {
            copy.planPreLayers = new ArrayList<>();
            for (Layer l : this.planPreLayers) {
                copy.planPreLayers.add(deepCopyLayer(l));
            }
        }

        // Copy plan post layers
        if (this.planPostLayers != null) {
            copy.planPostLayers = new ArrayList<>();
            for (Layer l : this.planPostLayers) {
                copy.planPostLayers.add(deepCopyLayer(l));
            }
        }

        // Copy execution layers
        copy.executionLayers = new ArrayList<>();
        for (Layer l : this.executionLayers) {
            copy.executionLayers.add(deepCopyLayer(l));
        }

        // Copy output layer
        copy.outputLayer = deepCopyLayer(this.outputLayer);

        return copy;
    }

    private static Layer deepCopyLayer(Layer original) {
        Layer copy = new Layer();
        List<Neuron> copiedNeurons = new ArrayList<>();
        for (Neuron n : original.getNeurons()) {
            Neuron cn = new Neuron();
            cn.setWeights(new ArrayList<>(n.getWeights()));
            cn.setBias(n.getBias());
            copiedNeurons.add(cn);
        }
        copy.setNeurons(copiedNeurons);
        return copy;
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

    /**
     * Computes the weight deltas between this network and a snapshot taken earlier,
     * multiplies those deltas by the given multiplier, and reapplies them on top
     * of the current weights. This amplifies the learning that occurred since the snapshot.
     *
     * newWeight = currentWeight + (currentWeight - snapshotWeight) * (multiplier - 1)
     * Equivalently: newWeight = snapshotWeight + (currentWeight - snapshotWeight) * multiplier
     *
     * @param snapshot The network state captured before training began
     * @param multiplier How much to scale the deltas (e.g., 1.25 = 25% boost)
     */
    public void applyAmplifiedDeltas(NeuralNetwork snapshot, double multiplier) {
        if (multiplier <= 1.0) return; // No amplification needed

        // Strategy layers
        amplifyLayerList(this.strategyLayers, snapshot.strategyLayers, multiplier);

        // Strategy bottleneck
        amplifyLayer(this.strategyBottleneck, snapshot.strategyBottleneck, multiplier);

        // Plan pre layers
        if (this.planPreLayers != null && snapshot.planPreLayers != null) {
            amplifyLayerList(this.planPreLayers, snapshot.planPreLayers, multiplier);
        }

        // Plan post layers
        if (this.planPostLayers != null && snapshot.planPostLayers != null) {
            amplifyLayerList(this.planPostLayers, snapshot.planPostLayers, multiplier);
        }

        // Execution layers
        amplifyLayerList(this.executionLayers, snapshot.executionLayers, multiplier);

        // Output layer
        amplifyLayer(this.outputLayer, snapshot.outputLayer, multiplier);

        // Keep plan weights in sync
        if (this.planPreLayers != null) {
            syncPlanWeights();
        }
    }

    private void amplifyLayerList(List<Layer> currentLayers, List<Layer> snapshotLayers, double multiplier) {
        if (currentLayers == null || snapshotLayers == null) return;
        int count = Math.min(currentLayers.size(), snapshotLayers.size());
        for (int i = 0; i < count; i++) {
            amplifyLayer(currentLayers.get(i), snapshotLayers.get(i), multiplier);
        }
    }

    private void amplifyLayer(Layer current, Layer snapshot, double multiplier) {
        if (current == null || snapshot == null) return;
        int neuronCount = Math.min(current.getNeurons().size(), snapshot.getNeurons().size());
        for (int n = 0; n < neuronCount; n++) {
            Neuron curNeuron = current.getNeurons().get(n);
            Neuron snapNeuron = snapshot.getNeurons().get(n);
            // Amplify weights
            int wCount = Math.min(curNeuron.getWeights().size(), snapNeuron.getWeights().size());
            for (int w = 0; w < wCount; w++) {
                double curW = curNeuron.getWeights().get(w);
                double snapW = snapNeuron.getWeights().get(w);
                double delta = curW - snapW;
                curNeuron.getWeights().set(w, curW + delta * (multiplier - 1.0));
            }
            // Amplify bias
            double biasDelta = curNeuron.getBias() - snapNeuron.getBias();
            curNeuron.setBias(curNeuron.getBias() + biasDelta * (multiplier - 1.0));
        }
    }

    // --- Plan Network Getters/Setters ---
    public List<Layer> getPlanPreLayers() {
        return planPreLayers;
    }

    public void setPlanPreLayers(List<Layer> planPreLayers) {
        this.planPreLayers = planPreLayers;
    }

    public List<Layer> getPlanPostLayers() {
        return planPostLayers;
    }

    public void setPlanPostLayers(List<Layer> planPostLayers) {
        this.planPostLayers = planPostLayers;
    }

    public List<Double> getLastPlanPreValues() {
        return lastPlanPreValues;
    }

    public void setLastPlanPreValues(List<Double> lastPlanPreValues) {
        this.lastPlanPreValues = lastPlanPreValues;
    }

    public List<Double> getLastPlanPostValues() {
        return lastPlanPostValues;
    }

    public void setLastPlanPostValues(List<Double> lastPlanPostValues) {
        this.lastPlanPostValues = lastPlanPostValues;
    }
}
