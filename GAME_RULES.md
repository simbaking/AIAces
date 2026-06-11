# Ace's Game Rules

## Objective
Get to Ace by playing cards on your stack in order (strictly up or strictly down).
- **Back door**: 2 connects to Ace.
- **Normal**: King connects to Ace? (Text says "from the back door (2) or normal (king)"). This implies strictly up/down order wraps or connects specifically at ends.
- **Win Condition**: Play an Ace on your stack.
  - **Constraint**: You cannot end in a Joker as an Ace to win the game.

## Setup
- Everyone has their own stack.
- If the starting card is **Ace** or **Joker**, draw a new one.

## Turn Structure
1. **Draw a card**.
2. **Action**:
   - **Advance Stack**: Play as many cards as you can in order (up or down).
   - **Discard**: Discard one card and perform its special ability.

## Card Abilities (On Discard)
| Card | Effect |
|---|---|
| **3** | Draw 3 cards. |
| **5** | Draw 1 card. |
| **4** | Skip next **two** players (unless only 1 opponent, then skip 1). |
| **6** | Skip next player. |
| **9** | Skip next player. |
| **7** | Put any card from your hand on top of someone else's stack. <br> **Conditions**: <br> - **2 Players**: Must match **SUIT** of card under draw pile. <br> - **3 Players**: Must match **COLOR** of card under draw pile. <br> - **4 Players**: Any suit **BUT** the one under draw pile. <br> - **5+ Players**: Any 7 works. |
| **8** | Pick a random card from someone's hand. <br> **If > 2 Players**: Can alternatively: <br> - Pick top card from someone's stack (if they have > 1 card). <br> - Take from discard pile. |
| **10** | Take top 3 cards from anyone's stack (must have at least 4 cards). |
| **J** | Reverse order (Skip in 2 player). |
| **Q** | (Fortune Seer) Look at top 3 of draw deck, pick one to put in hand. |
| **Joker** | (When played on discard) Move **2 cards** already in your discard to your stack OR move **1 card** from your discard to your hand. |

## Special Rules
- **Stack Play**: Cards must be played in order up or down.
- **Jokers (Stack)**: Wild. Can work as any card **EXCEPT 2, Ace, or King**.
- **Reshuffle**: When draw pile finishes, shuffle discard piles.
- **Slap/Tap**: Before the opponent tries move the already played card, you must slap(tap) on the already played card. (Physical rule? Implementation: Maybe auto-slap or button?).

## Player Count Modifiers
- **3+ Players**: Consider using multiple decks.
