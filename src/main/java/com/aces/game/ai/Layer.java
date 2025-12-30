package com.aces.game.ai;

import java.util.ArrayList;
import java.util.List;

public class Layer {
    private List<Neuron> neurons;

    public Layer() {
    } // Default for serialization

    public Layer(int size, int inputSize) {
        this.neurons = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            this.neurons.add(new Neuron(inputSize));
        }
    }

    public List<Double> feedForward(List<Double> inputs) {
        List<Double> outputs = new ArrayList<>();
        for (Neuron n : neurons) {
            outputs.add(n.activate(inputs));
        }
        return outputs;
    }

    public List<Neuron> getNeurons() {
        return neurons;
    }

    public void setNeurons(List<Neuron> neurons) {
        this.neurons = neurons;
    }
}
