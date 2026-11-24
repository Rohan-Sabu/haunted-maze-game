# 👻 Haunted Maze Game

Enter a mysterious maze where danger lurks around every corner! Collect ancient relics, find the hidden key, and reach the exit while avoiding AI-controlled Stalkers.

<img width="665" height="478" alt="Screenshot 2026-11-24 132601" src="https://github.com/user-attachments/assets/dd52ad20-3c5b-420f-8dd3-52a6fae0ef91" />


## 🎮 Controls

| Key | Action |
|---|---|
| `W` / ⬆️ | Move up |
| `A` / ⬅️ | Move left |
| `S` / ⬇️ | Move down |
| `D` / ➡️ | Move right |
| `Enter`   | Start or restart after a completed/failed run |
| `R`       | Restart when not playing |
| `Esc`     | Return to the start screen |

## 🎯 Objective

1. 🏺 Collect all relics in the level.
2. 🔑 Find and pick up the key that appears.
3. 🚪 Reach the exit to advance to the next level.
4. 🏆 Complete all three levels to win!

## 👻 Watch Out for the Stalkers!

You have **3 lives**. Stalkers are AI-controlled ghosts that use pathfinding and different behaviour states to:

- 🚶 Wander through the maze.
- 👂 Investigate sounds made by the player.
- 🏃 Chase the player when detected.
- 🔍 Search the area when the player disappears.

**Can you collect every relic, outsmart the Stalkers, and escape the maze?**

## Prerequisites
- **Java (JDK or JRE 8 or later; Java 17+ works seamlessly)**

## Compile and Run

From the project/source root, compile and execute it in the terminal:

```bash
javac HauntedMaze.java
java HauntedMaze
```
