package com.aces.game.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Neuron {
    private double value;
    private List<Double> weights;
    private double bias;

    public Neuron() {
    } // Default for serialization

    public Neuron(int inputSize) {
        this.weights = new ArrayList<>();
        this.bias = Math.random() * 2 - 1; // Random -1 to 1
        for (int i = 0; i < inputSize; i++) {
            this.weights.add(Math.random() * 2 - 1); // Random weights
        }
    }

    public double activate(List<Double> inputs) {
        double sum = bias;
        for (int i = 0; i < inputs.size(); i++) {
            sum += inputs.get(i) * weights.get(i);
        }
        // Sigmoid activation
        this.value = 1.0 / (1.0 + Math.exp(-sum));
        return this.value;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public List<Double> getWeights() {
        return weights;
    }

    public void setWeights(List<Double> weights) {
        this.weights = weights;
    }

    public double getBias() {
        return bias;
    }

    public void setBias(double bias) {
        this.bias = bias;
    }

    public void adjustWeights(List<Double> inputs, double delta, double learningRate) {
        for (int i = 0; i < weights.size(); i++) {
            double change = inputs.get(i) * delta * learningRate;
            weights.set(i, weights.get(i) + change);
        }
        this.bias += delta * learningRate;
    }
}
