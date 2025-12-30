# AI Agent Implementation Plan

## Goal
Implement a generic, visualizable Neural Network AI for Computer players.
- **Backend**: Custom clear Java implementation of a Feed-Forward Neural Network (No heavy external libs yet, to keep it visualizable/controllable).
- **Frontend**: A "Developer View" to see the live activation of nodes.

## Architecture

### 1. Data Structure (`com.aces.game.ai`)
- **`Neuron`**: Holds `value` (activation) and `weights` (connections to previous layer).
- **`Layer`**: List of Neurons.
- **`NeuralNetwork`**: List of Layers. Methods: `forward(inputs)`, `mutate()` (for evolution later), `toJson()` (for UI).

### 2. Integration (`GameService`)
- Add `NeuralNetwork brain` to the `Player` class.
- When CPU turn starts:
  1. **Extract Inputs**: Convert GameState -> `double[]` (Inputs).
  2. **Think**: `brain.forward(inputs)`.
  3. **Decide**: Interpret Outputs -> Action.
  4. **Visualize**: Store the last activation state for the UI to fetch.

### 3. Visualization (`ai-debug.html` or Overlay)
- Canvas-based rendering of the network.
- **Input Nodes**: Labelled (e.g., "Hand Size", "Top Stack Val").
- **Hidden Layers**: Circles showing activation intensity (color/opacity).
- **Output Nodes**: Labelled actions (e.g., "Draw", "Play Low").
- **Weights**: Lines connecting nodes (thickness/color = weight strength).

## Proposed Files
- `src/main/java/com/aces/game/ai/Neuron.java`
- `src/main/java/com/aces/game/ai/Layer.java`
- `src/main/java/com/aces/game/ai/NeuralNetwork.java`
- `src/main/java/com/aces/game/ai/AiInputMapper.java` (Helper to convert GameState to numbers)

## Next Steps
1. Create the backend classes.
2. Wait for User input on **Network Topology** (How many layers? How many nodes?) and **Input/Output definitions**.
