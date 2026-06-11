package com.aces.game.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Neuron {
    private double value;
    private List<Double> weights;
    private double bias;

    // Adam Optimizer State (transient so it doesn't serialize and bloat the file)
    private transient List<Double> mWeights;
    private transient List<Double> vWeights;
    private transient double mBias = 0;
    private transient double vBias = 0;
    private transient int t = 0;

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

    public void adjustWeightsAdam(List<Double> inputs, double delta, double baseLearningRate) {
        if (mWeights == null) {
            mWeights = new ArrayList<>(java.util.Collections.nCopies(weights.size(), 0.0));
            vWeights = new ArrayList<>(java.util.Collections.nCopies(weights.size(), 0.0));
        }
        t++;

        double beta1 = 0.9;
        double beta2 = 0.999;
        double epsilon = 1e-8;

        for (int i = 0; i < weights.size(); i++) {
            double gradient = inputs.get(i) * delta;

            double m = beta1 * mWeights.get(i) + (1 - beta1) * gradient;
            double v = beta2 * vWeights.get(i) + (1 - beta2) * (gradient * gradient);

            mWeights.set(i, m);
            vWeights.set(i, v);

            double mHat = m / (1 - Math.pow(beta1, t));
            double vHat = v / (1 - Math.pow(beta2, t));

            double change = baseLearningRate * mHat / (Math.sqrt(vHat) + epsilon);
            weights.set(i, weights.get(i) + change);
        }

        // Bias Adam update
        double gradBias = delta;
        mBias = beta1 * mBias + (1 - beta1) * gradBias;
        vBias = beta2 * vBias + (1 - beta2) * (gradBias * gradBias);
        double mHatBias = mBias / (1 - Math.pow(beta1, t));
        double vHatBias = vBias / (1 - Math.pow(beta2, t));
        this.bias += baseLearningRate * mHatBias / (Math.sqrt(vHatBias) + epsilon);
    }
}
