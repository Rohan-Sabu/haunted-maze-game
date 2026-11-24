import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

public class HauntedMaze {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("HAUNTED MAZE - AI ESCAPE");
            GamePanel game = new GamePanel();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(game);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setResizable(false);
            frame.setVisible(true);

            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                if (!frame.isActive()) return false;
                boolean handled = game.handleKey(e.getKeyCode());
                if (handled) e.consume();
                return handled;
            });

            SwingUtilities.invokeLater(() -> game.requestFocusInWindow());
        });
    }

    @SuppressWarnings("serial")
    static class GamePanel extends JPanel {
        static final int TILE = 36;
        static final int COLS = 25;
        static final int ROWS = 16;
        static final int HUD = 72;
        static final int WIDTH = COLS * TILE;
        static final int HEIGHT = ROWS * TILE + HUD;
        static final int FLOOR = 0;
        static final int WALL = 1;

        final Random random = new Random();
        final MazeMap map = new MazeMap(COLS, ROWS, FLOOR, WALL, random);
        final Renderer renderer = new Renderer(this);
        final ArrayList<Ghost> ghosts = new ArrayList<>();
        final ArrayList<Particle> particles = new ArrayList<>();
        final ArrayList<Point> symbols = new ArrayList<>();
        ArrayList<Point> reachableCells = new ArrayList<>();
        final javax.swing.Timer timer;

        enum Screen { START, PLAYING, GAME_OVER, VICTORY }
        enum GhostState { WANDER, HEAR, CHASE, SEARCH }

        Screen screen = Screen.START;
        int level = 1;
        int lives = 3;
        int score = 0;
        int collected = 0;
        int targetSymbols = 3;
        int playerX = 1;
        int playerY = 1;
        int exitX = COLS - 2;
        int exitY = ROWS - 2;
        Point keyPos = null;
        boolean keyCollected = false;
        long levelStart;
        long accumulatedElapsedMs = 0;
        long lastPlayerMove;
        long lastFrame;
        long shakeUntil;
        long safeUntil;
        int noiseX = -1, noiseY = -1;
        long noiseUntil;
        double danger = 0;
        double pulse = 0;
        double gameTime = 0;

        GamePanel() {
            setPreferredSize(new Dimension(WIDTH, HEIGHT));
            setFocusable(true);
            setBackground(Color.BLACK);
            map.generate();
            timer = new javax.swing.Timer(30, e -> update());
            timer.start();
        }

        int[][] maze() {
            return map.grid;
        }

        boolean handleKey(int code) {
            switch (code) {
                case KeyEvent.VK_W:
                case KeyEvent.VK_UP:
                    movePlayer(0, -1);
                    return true;
                case KeyEvent.VK_S:
                case KeyEvent.VK_DOWN:
                    movePlayer(0, 1);
                    return true;
                case KeyEvent.VK_A:
                case KeyEvent.VK_LEFT:
                    movePlayer(-1, 0);
                    return true;
                case KeyEvent.VK_D:
                case KeyEvent.VK_RIGHT:
                    movePlayer(1, 0);
                    return true;
                case KeyEvent.VK_ENTER:
                    if (screen == Screen.START || screen == Screen.GAME_OVER || screen == Screen.VICTORY) {
                        startNewGame();
                        return true;
                    }
                    return true;
                case KeyEvent.VK_R:
                    if (screen != Screen.PLAYING) startNewGame();
                    return true;
                case KeyEvent.VK_ESCAPE:
                    if (screen == Screen.PLAYING) {
                        screen = Screen.START;
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        void startNewGame() {
            level = 1;
            lives = 3;
            score = 0;
            setupLevel();
            screen = Screen.PLAYING;
        }

        void setupLevel() {
            map.generate();
            reachableCells = map.reachableCells(1, 1);
            playerX = 1;
            playerY = 1;
            exitX = COLS - 2;
            exitY = ROWS - 2;
            collected = 0;
            keyPos = null;
            keyCollected = false;
            symbols.clear();
            ghosts.clear();
            particles.clear();
            placeSymbols();
            int count = level;
            for (int i = 0; i < count; i++) ghosts.add(createGhost(i));
            long now = System.currentTimeMillis();
            levelStart = now;
            accumulatedElapsedMs = 0;
            lastPlayerMove = 0;
            noiseUntil = 0;
            danger = 0;
            spawnParticles(playerX, playerY, 18);
            safeUntil = now + 1800;
        }

        void placeSymbols() {
            ArrayList<Point> candidates = new ArrayList<>();
            for (Point p : reachableCells) {
                if (manhattan(p.x, p.y, playerX, playerY) > 5
                        && manhattan(p.x, p.y, exitX, exitY) > 2) {
                    candidates.add(new Point(p));
                }
            }
            Collections.shuffle(candidates, random);
            for (Point p : candidates) {
                if (symbols.size() >= targetSymbols) break;
                symbols.add(p);
            }
        }

        Point findKeySpawn() {
            ArrayList<Point> candidates = new ArrayList<>();
            for (Point p : reachableCells) {
                if (p.x == playerX && p.y == playerY) continue;
                if (p.x == exitX && p.y == exitY) continue;
                if (contains(symbols, p.x, p.y)) continue;
                if (occupiedByGhost(p.x, p.y, null)) continue;
                if (manhattan(p.x, p.y, playerX, playerY) >= 4) candidates.add(new Point(p));
            }
            if (candidates.isEmpty()) {
                // Relax the distance rule only; keep all occupancy and objective exclusions.
                for (Point p : reachableCells) {
                    if (p.x == playerX && p.y == playerY) continue;
                    if (p.x == exitX && p.y == exitY) continue;
                    if (contains(symbols, p.x, p.y)) continue;
                    if (occupiedByGhost(p.x, p.y, null)) continue;
                    candidates.add(new Point(p));
                }
            }
            if (candidates.isEmpty()) return new Point(playerX, playerY);
            return candidates.get(random.nextInt(candidates.size()));
        }

        Ghost createGhost(int index) {
            Point p = randomFarPoint(10, null);
            Ghost g = new Ghost(p.x, p.y);
            g.name = index == 0 ? "STALKER" : "STALKER-" + (index + 1);
            g.colorShift = random.nextFloat();
            g.moveDelay = Math.max(280, 500 - level * 50 - index * 15);
            g.wanderTarget = new Point(p.x, p.y);
            return g;
        }

        Point randomFarPoint(int minDistance, Ghost except) {
            ArrayList<Point> candidates = new ArrayList<>();
            for (Point p : reachableCells) {
                if (manhattan(p.x, p.y, playerX, playerY) < minDistance) continue;
                if (p.x == exitX && p.y == exitY) continue;
                if (contains(symbols, p.x, p.y)) continue;
                if (keyPos != null && p.equals(keyPos)) continue;
                if (occupiedByGhost(p.x, p.y, except)) continue;
                candidates.add(new Point(p));
            }
            // If the distance rule leaves too few cells, relax only that rule.
            if (candidates.isEmpty()) {
                for (Point p : reachableCells) {
                    if ((p.x == playerX && p.y == playerY) || (p.x == exitX && p.y == exitY)) continue;
                    if (contains(symbols, p.x, p.y)) continue;
                    if (keyPos != null && p.equals(keyPos)) continue;
                    if (!occupiedByGhost(p.x, p.y, except)) candidates.add(new Point(p));
                }
            }
            if (candidates.isEmpty()) return new Point(1, 1);
            return candidates.get(random.nextInt(candidates.size()));
        }

        boolean contains(ArrayList<Point> list, int x, int y) {
            for (Point p : list) if (p.x == x && p.y == y) return true;
            return false;
        }

        void update() {
            long now = System.currentTimeMillis();
            double dt = lastFrame == 0 ? 0.03 : Math.min(0.08, (now - lastFrame) / 1000.0);
            lastFrame = now;
            pulse += dt * 3.0;
            gameTime += dt;
            if (screen == Screen.PLAYING) {
                updateGame(now);
            }
            updateParticles(dt);
            repaint();
        }

        long totalElapsedMs(long now) {
            return accumulatedElapsedMs + Math.max(0L, now - levelStart);
        }

        int getRemainingSeconds(long now) {
            double remaining = getTimeLimit() - totalElapsedMs(now) / 1000.0;
            return Math.max(0, (int) Math.ceil(remaining));
        }

        void updateGame(long now) {
            if (totalElapsedMs(now) >= getTimeLimit() * 1000L) {
                loseLife(now);
                return;
            }
            updateGhosts(now);
            checkCollectibles();
            checkExit();
            danger = 0;
            for (Ghost g : ghosts) {
                double d = manhattan(g.x, g.y, playerX, playerY);
                danger = Math.max(danger, Math.max(0, 1.0 - d / 8.0));
                if (d == 0 && now >= safeUntil) {
                    loseLife(now);
                    return;
                }
            }
            if (danger > 0.65 && random.nextInt(5) == 0) spawnParticles(playerX, playerY, 1);
        }

        int getTimeLimit() {
            if (level == 1) return 90;
            if (level == 2) return 65;
            return 45;
        }

        void movePlayer(int dx, int dy) {
            if (screen != Screen.PLAYING) return;
            long now = System.currentTimeMillis();
            if (now - lastPlayerMove < 110) return;
            int nx = playerX + dx;
            int ny = playerY + dy;
            if (walkable(nx, ny)) {
                playerX = nx;
                playerY = ny;
                lastPlayerMove = now;
                createNoise(playerX, playerY, 5, 650);
                spawnParticles(playerX, playerY, 2);
            }
        }

        void createNoise(int x, int y, int radius, long duration) {
            noiseX = x;
            noiseY = y;
            noiseUntil = System.currentTimeMillis() + duration;
            for (Ghost g : ghosts) {
                if (manhattan(g.x, g.y, x, y) <= radius && g.state != GhostState.CHASE) {
                    g.state = GhostState.HEAR;
                    g.target = new Point(x, y);
                }
            }
        }

        void updateGhosts(long now) {
            for (Ghost g : ghosts) {
                if (now - g.lastMove < g.moveDelay) continue;
                g.lastMove = now;
                int d = manhattan(g.x, g.y, playerX, playerY);
                boolean seesPlayer = d <= 6 && hasLineOfSight(g.x, g.y, playerX, playerY);

                if (seesPlayer) {
                    g.state = GhostState.CHASE;
                    g.lastSeen = new Point(playerX, playerY);
                    g.target = new Point(playerX, playerY);
                } else if (g.state == GhostState.CHASE) {
                    // Once line of sight is lost, investigate the last observed position.
                    g.state = GhostState.SEARCH;
                    g.target = new Point(g.lastSeen);
                    g.searchTicks = 0;
                } else if (noiseUntil > now && noiseX >= 0
                        && manhattan(g.x, g.y, noiseX, noiseY) <= 10) {
                    g.state = GhostState.HEAR;
                    g.target = new Point(noiseX, noiseY);
                }

                if (g.state == GhostState.WANDER) {
                    if (g.wanderTarget == null || (g.x == g.wanderTarget.x && g.y == g.wanderTarget.y))
                        g.wanderTarget = randomWalkablePoint();
                    moveGhostToward(g, g.wanderTarget);
                } else if (g.state == GhostState.HEAR) {
                    moveGhostToward(g, g.target);
                    if (g.x == g.target.x && g.y == g.target.y) {
                        g.state = GhostState.SEARCH;
                        g.searchTicks = 0;
                    }
                } else if (g.state == GhostState.CHASE) {
                    moveGhostToward(g, new Point(playerX, playerY));
                } else if (g.state == GhostState.SEARCH) {
                    moveGhostToward(g, g.target == null ? g.lastSeen : g.target);
                    g.searchTicks++;
                    if (g.searchTicks > 8 || (g.target != null && g.x == g.target.x && g.y == g.target.y)) {
                        g.state = GhostState.WANDER;
                        g.searchTicks = 0;
                    }
                }
            }
        }

        void moveGhostToward(Ghost g, Point target) {
            if (target == null) return;

            if (!walkable(g.x, g.y)) {
                Point repaired = map.nearestWalkable(g.x, g.y);
                if (repaired != null) {
                    g.x = repaired.x;
                    g.y = repaired.y;
                }
            }

            ArrayList<Point> path = aStar(new Point(g.x, g.y), target);
            if (path.size() > 1) {
                Point next = path.get(1);
                if (!occupiedByGhost(next.x, next.y, g)) {
                    g.x = next.x;
                    g.y = next.y;
                }
            }
        }

        boolean occupiedByGhost(int x, int y, Ghost except) {
            for (Ghost g : ghosts) if (g != except && g.x == x && g.y == y) return true;
            return false;
        }

        ArrayList<Point> aStar(Point start, Point goal) {
            ArrayList<Point> empty = new ArrayList<>();
            if (!walkable(goal.x, goal.y)) return empty;
            PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingInt(n -> n.f));
            HashMap<Point, Integer> best = new HashMap<>();
            HashMap<Point, Point> parent = new HashMap<>();
            Point s = new Point(start), g = new Point(goal);
            open.add(new Node(s, 0, manhattan(s.x, s.y, g.x, g.y)));
            best.put(s, 0);
            int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
            while (!open.isEmpty()) {
                Node cur = open.poll();
                // Ignore an older queue entry when a better route was found later.
                if (cur.g != best.getOrDefault(cur.p, Integer.MAX_VALUE)) continue;
                if (cur.p.equals(g)) {
                    ArrayList<Point> path = new ArrayList<>();
                    Point p = g;
                    path.add(new Point(p));
                    while (parent.containsKey(p)) {
                        p = parent.get(p);
                        path.add(new Point(p));
                    }
                    Collections.reverse(path);
                    return path;
                }
                for (int[] d : dirs) {
                    int nx = cur.p.x + d[0], ny = cur.p.y + d[1];
                    if (!walkable(nx, ny)) continue;
                    Point np = new Point(nx, ny);
                    int ng = cur.g + 1;
                    if (!best.containsKey(np) || ng < best.get(np)) {
                        best.put(np, ng);
                        parent.put(np, cur.p);
                        open.add(new Node(np, ng, ng + manhattan(nx, ny, g.x, g.y)));
                    }
                }
            }
            return empty;
        }

        Point randomWalkablePoint() {
            if (reachableCells.isEmpty()) return new Point(1, 1);
            Point p = reachableCells.get(random.nextInt(reachableCells.size()));
            return new Point(p);
        }

        boolean walkable(int x, int y) {
            return map.walkable(x, y);
        }

        int manhattan(int x1, int y1, int x2, int y2) {
            return Math.abs(x1 - x2) + Math.abs(y1 - y2);
        }

        boolean hasLineOfSight(int x1, int y1, int x2, int y2) {
            int dx = Math.abs(x2 - x1), sx = x1 < x2 ? 1 : -1;
            int dy = -Math.abs(y2 - y1), sy = y1 < y2 ? 1 : -1;
            int err = dx + dy, x = x1, y = y1;
            while (true) {
                if (maze()[y][x] == WALL) return false;
                if (x == x2 && y == y2) return true;
                int e2 = 2 * err;
                if (e2 >= dy) { err += dy; x += sx; }
                if (e2 <= dx) { err += dx; y += sy; }
                if (!walkable(x, y)) return false;
            }
        }

        void checkCollectibles() {
            for (int i = symbols.size() - 1; i >= 0; i--) {
                Point p = symbols.get(i);
                if (p.x == playerX && p.y == playerY) {
                    symbols.remove(i);
                    collected++;
                    score += 100;
                    spawnParticles(playerX, playerY, 18);
                }
            }
            if (collected >= targetSymbols && !keyCollected && keyPos == null) {
                keyPos = findKeySpawn();
                spawnParticles(keyPos.x, keyPos.y, 14);
            }
            if (!keyCollected && keyPos != null && playerX == keyPos.x && playerY == keyPos.y) {
                keyCollected = true;
                keyPos = null;
                score += 250;
                spawnParticles(playerX, playerY, 24);
            }
        }

        void checkExit() {
            if (playerX != exitX || playerY != exitY) return;
            if (!keyCollected) return;
            score += 500;
            if (level >= 3) screen = Screen.VICTORY;
            else {
                level++;
                setupLevel();
            }
        }

        void loseLife(long now) {
            lives--;
            // Each remaining life gets a fresh timer attempt. Keeping the expired
            // elapsed time here would trigger loseLife() on every timer tick.
            accumulatedElapsedMs = 0;
            levelStart = now;
            shakeUntil = now + 500;
            spawnParticles(playerX, playerY, 35);
            if (lives <= 0) {
                screen = Screen.GAME_OVER;
                return;
            }

            playerX = 1;
            playerY = 1;
            if (!keyCollected && keyPos != null && keyPos.x == playerX && keyPos.y == playerY) {
                keyPos = findKeySpawn();
            }
            for (int i = 0; i < ghosts.size(); i++) {
                Ghost gh = ghosts.get(i);
                Point p = randomFarPoint(12, gh);
                gh.x = p.x;
                gh.y = p.y;
                gh.state = GhostState.WANDER;
                gh.target = null;
                gh.lastSeen = new Point(playerX, playerY);
                gh.lastMove = now + 900 + i * 150;
                gh.searchTicks = 0;
            }
            noiseUntil = 0;
            lastPlayerMove = 0;
            safeUntil = now + 2200;
            danger = 0;
            spawnParticles(playerX, playerY, 20);
        }

        void spawnParticles(int tx, int ty, int count) {
            for (int i = 0; i < count; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double s = 20 + random.nextDouble() * 65;
                particles.add(new Particle((tx + .5) * TILE, (ty + .5) * TILE,
                        Math.cos(a) * s, Math.sin(a) * s, .35 + random.nextDouble() * .55));
            }
        }

        void updateParticles(double dt) {
            Iterator<Particle> it = particles.iterator();
            while (it.hasNext()) {
                Particle p = it.next();
                p.life -= dt;
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.vx *= .97;
                p.vy *= .97;
                if (p.life <= 0) it.remove();
            }
        }

        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (screen == Screen.START) renderer.drawStart(g);
            else if (screen == Screen.PLAYING) renderer.drawGame(g);
            else if (screen == Screen.GAME_OVER) { renderer.drawGame(g); renderer.drawOverlay(g, false); }
            else { renderer.drawGame(g); renderer.drawOverlay(g, true); }
            g.dispose();
        }
    }

    static class MazeMap {
        final int cols;
        final int rows;
        final int floor;
        final int wall;
        final int[][] grid;
        final Random random;

        MazeMap(int cols, int rows, int floor, int wall, Random random) {
            this.cols = cols;
            this.rows = rows;
            this.floor = floor;
            this.wall = wall;
            this.grid = new int[rows][cols];
            this.random = random;
        }

        void generate() {
            for (int y = 0; y < rows; y++) Arrays.fill(grid[y], wall);
            int x = 1, y = 1;
            grid[y][x] = floor;
            while (x < cols - 2 || y < rows - 2) {
                if (x < cols - 2 && (y >= rows - 2 || random.nextBoolean())) x++;
                else if (y < rows - 2) y++;
                grid[y][x] = floor;
                if (random.nextInt(100) < 35) {
                    if (x + 1 < cols - 1) grid[y][x + 1] = floor;
                    if (y + 1 < rows - 1) grid[y + 1][x] = floor;
                }
            }
            for (int i = 0; i < 70; i++) {
                int cx = 1 + random.nextInt(cols - 2);
                int cy = 1 + random.nextInt(rows - 2);
                int rw = 1 + random.nextInt(4);
                int rh = 1 + random.nextInt(3);
                for (int yy = cy; yy < Math.min(rows - 1, cy + rh); yy++)
                    for (int xx = cx; xx < Math.min(cols - 1, cx + rw); xx++)
                        grid[yy][xx] = floor;
            }
            for (int xx = 1; xx < cols - 1; xx++) grid[5][xx] = floor;
            for (int yy = 1; yy < rows - 1; yy++) grid[yy][12] = floor;
            for (int xx = 12; xx < cols - 1; xx++) grid[10][xx] = floor;
            for (int yy = 10; yy < rows - 1; yy++) grid[yy][20] = floor;
            grid[1][1] = floor;
            grid[1][2] = floor;
            grid[2][1] = floor;
            grid[rows - 2][cols - 2] = floor;
            grid[rows - 2][cols - 3] = floor;
            grid[rows - 3][cols - 2] = floor;
        }

        ArrayList<Point> reachableCells(int startX, int startY) {
            ArrayList<Point> result = new ArrayList<>();
            if (!walkable(startX, startY)) return result;
            boolean[][] visited = new boolean[rows][cols];
            ArrayDeque<Point> queue = new ArrayDeque<>();
            queue.add(new Point(startX, startY));
            visited[startY][startX] = true;
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!queue.isEmpty()) {
                Point current = queue.removeFirst();
                result.add(current);
                for (int[] dir : dirs) {
                    int nx = current.x + dir[0];
                    int ny = current.y + dir[1];
                    if (walkable(nx, ny) && !visited[ny][nx]) {
                        visited[ny][nx] = true;
                        queue.addLast(new Point(nx, ny));
                    }
                }
            }
            return result;
        }

        boolean walkable(int x, int y) {
            return x >= 0 && x < cols && y >= 0 && y < rows && grid[y][x] == floor;
        }

        Point nearestWalkable(int sx, int sy) {
            int bestDist = Integer.MAX_VALUE;
            Point best = null;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    if (!walkable(x, y)) continue;
                    int d = Math.abs(x - sx) + Math.abs(y - sy);
                    if (d < bestDist) {
                        bestDist = d;
                        best = new Point(x, y);
                    }
                }
            }
            return best;
        }
    }

    static class Renderer {
        final GamePanel gp;

        Renderer(GamePanel gp) {
            this.gp = gp;
        }

        void drawStart(Graphics2D g) {
            drawCinematicBackground(g, true);

            g.setFont(new Font("SansSerif", Font.BOLD, 58));
            drawGlowText(g, "HAUNTED", GamePanel.WIDTH / 2, 155, new Color(220, 230, 245), new Color(80, 150, 255, 90), 18);
            g.setFont(new Font("SansSerif", Font.BOLD, 58));
            drawGlowText(g, "MAZE", GamePanel.WIDTH / 2, 215, new Color(235, 238, 245), new Color(255, 35, 60, 80), 18);

            g.setFont(new Font("Monospaced", Font.BOLD, 16));
            g.setColor(new Color(255, 70, 85));
            center(g, "ESCAPE THE HAUNTED MAZE", 250);

            int cardY = 300;
            drawFeatureCard(g, 90, cardY, 210, 78, "◆", "COLLECT RELICS", new Color(80, 180, 255));
            drawStalkerFeatureCard(g, 345, cardY, 210, 78);
            drawFeatureCard(g, 600, cardY, 210, 78, "3", "ESCAPE LEVELS", new Color(255, 70, 80));

            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            g.setColor(new Color(185, 192, 205));
            center(g, "Collect relics → Pick up the key → Reach the exit.", 445);
            center(g, "Stalkers react to what they see and hear. Stay out of sight.", 472);

            int pulseAlpha = 150 + (int) (80 * (0.5 + 0.5 * Math.sin(gp.pulse * 2.0)));
            g.setColor(new Color(255, 255, 255, pulseAlpha));
            g.fillRoundRect(GamePanel.WIDTH / 2 - 145, 505, 290, 54, 14, 14);
            g.setColor(new Color(10, 12, 18));
            g.setFont(new Font("SansSerif", Font.BOLD, 19));
            center(g, "PRESS ENTER TO START", 539);

            g.setFont(new Font("Monospaced", Font.PLAIN, 13));
            g.setColor(new Color(125, 135, 150));
            center(g, "WASD / ARROWS   •   90 / 65 / 45 SEC", 590);
        }

        void drawCinematicBackground(Graphics2D g, boolean menu) {
            GradientPaint bg = new GradientPaint(0, 0, new Color(5, 7, 13), GamePanel.WIDTH, GamePanel.HEIGHT,
                    new Color(menu ? 32 : 9, 6, 15));
            g.setPaint(bg);
            g.fillRect(0, 0, GamePanel.WIDTH, GamePanel.HEIGHT);

            for (int i = 0; i < 7; i++) {
                int x = 40 + i * 150;
                int alpha = 12 + (i % 3) * 5;
                g.setColor(new Color(70, 100, 145, alpha));
                g.fillPolygon(new int[]{x, x + 70, x + 115, x + 30},
                        new int[]{0, 0, GamePanel.HEIGHT, GamePanel.HEIGHT}, 4);
            }

            for (int i = 0; i < 85; i++) {
                int x = (i * 83 + 31) % GamePanel.WIDTH;
                int y = (i * 47 + 17) % (GamePanel.HEIGHT - GamePanel.HUD);
                int a = 15 + (i % 4) * 8;
                g.setColor(new Color(180, 205, 235, a));
                int r = (i % 5 == 0) ? 2 : 1;
                g.fillOval(x, y, r, r);
            }

            RadialGradientPaint redGlow = new RadialGradientPaint(
                    new Point(GamePanel.WIDTH / 2, GamePanel.HEIGHT - 70), 430f,
                    new float[]{0f, .55f, 1f},
                    new Color[]{new Color(150, 10, 25, 35), new Color(60, 5, 15, 12), new Color(0, 0, 0, 0)});
            g.setPaint(redGlow);
            g.fillRect(0, 0, GamePanel.WIDTH, GamePanel.HEIGHT);
        }

        void drawGame(Graphics2D g) {
            int ox = 0, oy = 0;
            if (System.currentTimeMillis() < gp.shakeUntil) {
                ox = gp.random.nextInt(11) - 5;
                oy = gp.random.nextInt(11) - 5;
            }
            g.translate(ox, oy);

            drawMaze(g);
            drawWorldDetails(g);
            drawSymbols(g);
            drawKey(g);
            drawExit(g);
            drawGhosts(g);
            drawPlayer(g);
            drawParticles(g);
            drawLighting(g);
            drawVignette(g);
            drawHUD(g);

            g.translate(-ox, -oy);
        }

        void drawMaze(Graphics2D g) {
            for (int y = 0; y < GamePanel.ROWS; y++) {
                for (int x = 0; x < GamePanel.COLS; x++) {
                    int px = x * GamePanel.TILE, py = y * GamePanel.TILE;
                    if (gp.maze()[y][x] == GamePanel.WALL) {
                        g.setColor(new Color(24, 28, 36));
                        g.fillRect(px, py, GamePanel.TILE, GamePanel.TILE);

                        g.setColor(new Color(72, 82, 96));
                        g.drawLine(px + 1, py + 1, px + GamePanel.TILE - 2, py + 1);
                        g.drawLine(px + 1, py + 1, px + 1, py + GamePanel.TILE - 2);
                        g.setColor(new Color(10, 12, 17));
                        g.drawLine(px + 1, py + GamePanel.TILE - 2, px + GamePanel.TILE - 2, py + GamePanel.TILE - 2);
                        g.drawLine(px + GamePanel.TILE - 2, py + 1, px + GamePanel.TILE - 2, py + GamePanel.TILE - 2);

                        if ((x * 31 + y * 17) % 11 == 0) {
                            g.setColor(new Color(65, 70, 78, 55));
                            g.drawLine(px + 8, py + 7, px + 11, py + 27);
                            g.drawLine(px + 11, py + 27, px + 16, py + 31);
                        }
                    } else {
                        int base = 43 + ((x * 13 + y * 7) % 7);
                        g.setColor(new Color(base, base + 3, base + 9));
                        g.fillRect(px, py, GamePanel.TILE, GamePanel.TILE);

                        g.setColor(new Color(105, 112, 125, 45));
                        g.drawLine(px, py + GamePanel.TILE - 1, px + GamePanel.TILE, py + GamePanel.TILE - 1);
                        g.drawLine(px + GamePanel.TILE - 1, py, px + GamePanel.TILE - 1, py + GamePanel.TILE);
                    }
                }
            }
        }

        void drawWorldDetails(Graphics2D g) {
            for (int y = 1; y < GamePanel.ROWS - 1; y++) {
                for (int x = 1; x < GamePanel.COLS - 1; x++) {
                    if (gp.maze()[y][x] != GamePanel.FLOOR) continue;
                    int seed = x * 97 + y * 53;
                    if (seed % 23 == 0 && !isOccupiedImportant(x, y)) {
                        int px = x * GamePanel.TILE, py = y * GamePanel.TILE;
                        g.setColor(new Color(90, 100, 112, 30));
                        g.drawLine(px + 8, py + 24, px + 15, py + 17);
                        g.drawLine(px + 15, py + 17, px + 23, py + 23);
                    }
                    if (seed % 41 == 0 && !isOccupiedImportant(x, y)) {
                        int px = x * GamePanel.TILE + 8, py = y * GamePanel.TILE + 9;
                        g.setColor(new Color(105, 115, 125, 22));
                        g.fillOval(px, py, 4, 4);
                        g.fillOval(px + 8, py + 7, 3, 3);
                    }
                }
            }
        }

        boolean isOccupiedImportant(int x, int y) {
            return (x == gp.playerX && y == gp.playerY)
                    || (x == gp.exitX && y == gp.exitY)
                    || (gp.keyPos != null && x == gp.keyPos.x && y == gp.keyPos.y)
                    || gp.contains(gp.symbols, x, y);
        }

        void drawSymbols(Graphics2D g) {
            double t = gp.gameTime * 4.0;
            for (Point p : gp.symbols) {
                int cx = p.x * GamePanel.TILE + GamePanel.TILE / 2;
                int cy = p.y * GamePanel.TILE + GamePanel.TILE / 2;
                int r = 8 + (int) (2 * Math.sin(t + p.x * .7));

                for (int glow = 30; glow >= 10; glow -= 5) {
                    int a = 5 + (30 - glow) * 2;
                    g.setColor(new Color(50, 185, 255, a));
                    g.fillOval(cx - glow, cy - glow, glow * 2, glow * 2);
                }

                Polygon diamond = new Polygon(
                        new int[]{cx, cx + r, cx, cx - r},
                        new int[]{cy - r, cy, cy + r, cy}, 4);
                g.setColor(new Color(70, 205, 255));
                g.fillPolygon(diamond);
                g.setStroke(new BasicStroke(2f));
                g.setColor(new Color(225, 250, 255));
                g.drawPolygon(diamond);

                g.setColor(new Color(255, 255, 255, 170));
                g.fillOval(cx - 2, cy - 5, 4, 4);
            }
        }

        void drawKey(Graphics2D g) {
            if (gp.keyPos == null || gp.keyCollected) return;
            int cx = gp.keyPos.x * GamePanel.TILE + GamePanel.TILE / 2;
            int cy = gp.keyPos.y * GamePanel.TILE + GamePanel.TILE / 2 + (int) (Math.sin(gp.gameTime * 5.0) * 2.0);

            for (int r = 28; r >= 10; r -= 5) {
                int a = 8 + (28 - r) * 2;
                g.setColor(new Color(255, 205, 70, a));
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
            }

            g.setColor(new Color(255, 220, 95));
            g.setStroke(new BasicStroke(2.4f));
            g.drawOval(cx - 10, cy - 5, 9, 9);
            g.drawLine(cx - 1, cy, cx + 10, cy);
            g.drawLine(cx + 6, cy, cx + 6, cy + 4);
            g.drawLine(cx + 10, cy, cx + 10, cy + 4);
        }

        void drawExit(Graphics2D g) {
            int cx = gp.exitX * GamePanel.TILE + GamePanel.TILE / 2;
            int cy = gp.exitY * GamePanel.TILE + GamePanel.TILE / 2;
            boolean keySpawned = gp.keyPos != null;
            boolean escape = gp.keyCollected;
            int pulseSize = 8 + (int) (5 * (0.5 + 0.5 * Math.sin(gp.pulse * 1.5)));
            Color main = escape ? new Color(255, 205, 70) : keySpawned ? new Color(45, 230, 135) : new Color(205, 45, 60);

            for (int r = 36 + pulseSize; r >= 16; r -= 5) {
                int alpha = Math.max(4, 42 - (r - 16));
                g.setColor(new Color(main.getRed(), main.getGreen(), main.getBlue(), alpha));
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
            }

            g.setColor(new Color(8, 10, 15));
            g.fillRoundRect(cx - 13, cy - 20, 26, 40, 7, 7);
            g.setColor(main);
            g.setStroke(new BasicStroke(2.5f));
            g.drawRoundRect(cx - 13, cy - 20, 26, 40, 7, 7);
            g.fillRoundRect(cx - 8, cy - 15, 16, 30, 4, 4);

            g.setColor(new Color(8, 10, 15));
            g.fillOval(cx - 3, cy - 2, 6, 6);
            g.drawLine(cx, cy + 2, cx, cy + 9);

            if (escape) {
                g.setColor(new Color(255, 235, 120));
                g.fillOval(cx - 5, cy - 29, 10, 10);
                g.drawLine(cx + 4, cy - 24, cx + 13, cy - 24);
                g.drawLine(cx + 9, cy - 24, cx + 9, cy - 20);
            }
        }

        void drawGhosts(Graphics2D g) {
            for (Ghost ghost : gp.ghosts) {
                int cx = ghost.x * GamePanel.TILE + GamePanel.TILE / 2;
                int cy = ghost.y * GamePanel.TILE + GamePanel.TILE / 2;
                Color c = ghostColor(ghost.state);
                double bob = Math.sin(gp.gameTime * 4.0 + ghost.colorShift * 8.0) * 2.0;
                int by = (int) bob;
                int glow = ghost.state == GamePanel.GhostState.CHASE ? 32 : 22;

                for (int r = glow; r >= 9; r -= 5) {
                    int a = ghost.state == GamePanel.GhostState.CHASE ? 30 : 18;
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), a));
                    g.fillOval(cx - r, cy + by - r, r * 2, r * 2);
                }

                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 225));
                g.fillOval(cx - 13, cy - 15 + by, 26, 28);
                Polygon body = new Polygon(
                        new int[]{cx - 13, cx + 13, cx + 11, cx + 6, cx, cx - 6, cx - 11},
                        new int[]{cy - 2 + by, cy - 2 + by, cy + 14 + by, cy + 8 + by,
                                cy + 16 + by, cy + 8 + by, cy + 14 + by}, 7);
                g.fillPolygon(body);

                g.setColor(new Color(255, 255, 255, 235));
                g.fillOval(cx - 8, cy - 6 + by, 7, 9);
                g.fillOval(cx + 2, cy - 6 + by, 7, 9);
                g.setColor(new Color(12, 13, 18));
                g.fillOval(cx - 5, cy - 2 + by, 3, 4);
                g.fillOval(cx + 5, cy - 2 + by, 3, 4);

                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 130));
                g.setStroke(new BasicStroke(2f));
                g.drawArc(cx - 20, cy - 20 + by, 40, 40, 15, 120);

                if (ghost.state == GamePanel.GhostState.CHASE) {
                    g.setFont(new Font("Monospaced", Font.BOLD, 10));
                    g.setColor(new Color(255, 90, 100, 190));
                    g.drawString("CHASE", cx - 18, cy - 25 + by);
                }
            }
        }

        Color ghostColor(GamePanel.GhostState state) {
            if (state == GamePanel.GhostState.CHASE) return new Color(255, 45, 60);
            if (state == GamePanel.GhostState.HEAR) return new Color(255, 195, 45);
            if (state == GamePanel.GhostState.SEARCH) return new Color(185, 95, 255);
            return new Color(80, 175, 255);
        }

        void drawPlayer(Graphics2D g) {
            int cx = gp.playerX * GamePanel.TILE + GamePanel.TILE / 2;
            int cy = gp.playerY * GamePanel.TILE + GamePanel.TILE / 2;
            double bob = Math.sin(gp.gameTime * 8.0) * 1.5;
            int by = (int) bob;

            for (int r = 24; r >= 10; r -= 4) {
                int alpha = 8 + (24 - r) * 3;
                g.setColor(new Color(60, 155, 255, alpha));
                g.fillOval(cx - r, cy + by - r, r * 2, r * 2);
            }
            g.setColor(new Color(72, 165, 255));
            g.fillOval(cx - 10, cy - 10 + by, 20, 20);
            g.setColor(new Color(225, 245, 255));
            g.setStroke(new BasicStroke(2f));
            g.drawOval(cx - 10, cy - 10 + by, 20, 20);
            g.setColor(Color.WHITE);
            g.fillOval(cx - 4, cy - 5 + by, 5, 5);
        }

        void drawParticles(Graphics2D g) {
            for (Particle p : gp.particles) {
                int a = Math.max(0, Math.min(220, (int) (255 * Math.min(1, p.life * 2))));
                int size = p.life > .45 ? 3 : 2;
                g.setColor(new Color(125, 215, 255, a));
                g.fillOval((int) p.x - size / 2, (int) p.y - size / 2, size, size);
            }
        }

        void drawLighting(Graphics2D g) {
            int cx = gp.playerX * GamePanel.TILE + GamePanel.TILE / 2;
            int cy = gp.playerY * GamePanel.TILE + GamePanel.TILE / 2;

            g.setColor(new Color(0, 0, 0, 78));
            g.fillRect(0, 0, GamePanel.WIDTH, GamePanel.ROWS * GamePanel.TILE);

            RadialGradientPaint light = new RadialGradientPaint(
                    new Point(cx, cy), 285f,
                    new float[]{0f, .18f, .42f, .68f, .86f, 1f},
                    new Color[]{new Color(255, 250, 220, 180), new Color(255, 247, 215, 150),
                            new Color(255, 238, 200, 105), new Color(255, 220, 180, 52),
                            new Color(255, 205, 170, 18), new Color(0, 0, 0, 0)});
            g.setPaint(light);
            g.fillRect(0, 0, GamePanel.WIDTH, GamePanel.ROWS * GamePanel.TILE);

            RadialGradientPaint ambient = new RadialGradientPaint(
                    new Point(cx, cy), 125f,
                    new float[]{0f, .55f, 1f},
                    new Color[]{new Color(255, 248, 215, 42), new Color(255, 235, 195, 18), new Color(255, 220, 180, 0)});
            g.setPaint(ambient);
            g.fillRect(0, 0, GamePanel.WIDTH, GamePanel.ROWS * GamePanel.TILE);

            if (gp.noiseUntil > System.currentTimeMillis()) {
                double progress = 1.0 - (gp.noiseUntil - System.currentTimeMillis()) / 650.0;
                int r = (int) (progress * 150);
                int alpha = (int) (95 * (1 - progress));
                g.setColor(new Color(255, 190, 70, Math.max(0, alpha)));
                g.setStroke(new BasicStroke(2f));
                g.drawOval(cx - r, cy - r, r * 2, r * 2);
            }

            drawDangerEffect(g);
        }

        void drawDangerEffect(Graphics2D g) {
            if (gp.danger <= 0.03) return;
            int alpha = (int) Math.min(105, 105 * gp.danger);
            int band = 18 + (int) (8 * gp.danger);
            g.setColor(new Color(205, 0, 22, alpha));
            g.fillRect(0, 0, GamePanel.WIDTH, band);
            g.fillRect(0, GamePanel.ROWS * GamePanel.TILE - band, GamePanel.WIDTH, band);
            g.fillRect(0, 0, band, GamePanel.ROWS * GamePanel.TILE);
            g.fillRect(GamePanel.WIDTH - band, 0, band, GamePanel.ROWS * GamePanel.TILE);

            if (gp.danger > .7) {
                g.setFont(new Font("Monospaced", Font.BOLD, 12));
                g.setColor(new Color(255, 90, 100, 210));
                center(g, "THREAT DETECTED", 30);
            }
        }

        void drawVignette(Graphics2D g) {
            int cx = GamePanel.WIDTH / 2, cy = (GamePanel.ROWS * GamePanel.TILE) / 2;
            RadialGradientPaint vignette = new RadialGradientPaint(
                    new Point(cx, cy), 470f,
                    new float[]{0f, .60f, .86f, 1f},
                    new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 8),
                            new Color(0, 0, 0, 70), new Color(0, 0, 0, 165)});
            g.setPaint(vignette);
            g.fillRect(0, 0, GamePanel.WIDTH, GamePanel.ROWS * GamePanel.TILE);
        }

        void drawHUD(Graphics2D g) {
            int y = GamePanel.ROWS * GamePanel.TILE;
            g.setPaint(new GradientPaint(0, y, new Color(8, 10, 16), GamePanel.WIDTH, y + GamePanel.HUD, new Color(15, 8, 13)));
            g.fillRect(0, y, GamePanel.WIDTH, GamePanel.HUD);
            g.setColor(new Color(70, 78, 92));
            g.drawLine(0, y, GamePanel.WIDTH, y);

            int remaining = gp.getRemainingSeconds(System.currentTimeMillis());
            boolean urgent = remaining <= 15;

            drawHudStat(g, 18, y + 9, 105, "LEVEL", String.valueOf(gp.level), new Color(110, 185, 255));
            drawHudStat(g, 130, y + 9, 130, "RELICS", gp.collected + "/" + gp.targetSymbols, new Color(80, 220, 255));
            drawHudStat(g, 267, y + 9, 105, "LIVES", String.valueOf(gp.lives), new Color(255, 85, 105));
            drawHudStat(g, 378, y + 9, 125, "TIME", remaining + "s", urgent ? new Color(255, 75, 75) : new Color(255, 205, 80));
            drawHudStat(g, 509, y + 9, 130, "SCORE", String.valueOf(gp.score), new Color(175, 120, 255));

            boolean keySpawned = gp.keyPos != null;
            Color exitColor = gp.keyCollected ? new Color(255, 210, 70)
                    : keySpawned ? new Color(60, 230, 145)
                    : new Color(210, 70, 85);
            g.setColor(new Color(exitColor.getRed(), exitColor.getGreen(), exitColor.getBlue(), 28));
            g.fillRoundRect(648, y + 9, 230, 30, 10, 10);
            g.setColor(exitColor);
            g.setFont(new Font("Monospaced", Font.BOLD, 13));
            String status = gp.keyCollected ? "EXIT: ESCAPE NOW" : keySpawned ? "KEY: PICK UP KEY" : "EXIT: LOCKED";
            g.drawString(status, 663, y + 29);

            g.setFont(new Font("Monospaced", Font.BOLD, 12));
            g.setColor(new Color(150, 115, 175));
            g.drawString("STALKERS: " + gp.ghosts.size(), 18, y + 59);
            g.setColor(new Color(105, 115, 130));
            g.drawString("WASD / ARROWS", GamePanel.WIDTH - 125, y + 60);
        }

        void drawHudStat(Graphics2D g, int x, int y, int w, String label, String value, Color accent) {
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 18));
            g.fillRoundRect(x, y, w, 34, 9, 9);
            g.setFont(new Font("Monospaced", Font.BOLD, 9));
            g.setColor(new Color(145, 152, 165));
            g.drawString(label, x + 9, y + 12);
            g.setFont(new Font("Monospaced", Font.BOLD, 15));
            g.setColor(accent);
            g.drawString(value, x + 9, y + 28);
        }

        void drawStalkerFeatureCard(Graphics2D g, int x, int y, int w, int h) {
            Color stalkerBlue = new Color(80, 175, 255);
            g.setColor(new Color(10, 13, 20, 225));
            g.fillRoundRect(x, y, w, h, 14, 14);
            g.setColor(new Color(stalkerBlue.getRed(), stalkerBlue.getGreen(), stalkerBlue.getBlue(), 90));
            g.drawRoundRect(x, y, w, h, 14, 14);

            // Draw the same solid-blue ghost silhouette used by the in-game Stalker.
            int cx = x + 29;
            int cy = y + 26;
            g.setColor(stalkerBlue);
            g.fillOval(cx - 10, cy - 12, 20, 21);
            Polygon body = new Polygon(
                    new int[]{cx - 10, cx + 10, cx + 8, cx + 5, cx, cx - 5, cx - 8},
                    new int[]{cy - 2, cy - 2, cy + 10, cy + 6, cy + 12, cy + 6, cy + 10}, 7);
            g.fillPolygon(body);

            g.setColor(new Color(255, 255, 255, 235));
            g.fillOval(cx - 6, cy - 5, 5, 7);
            g.fillOval(cx + 1, cy - 5, 5, 7);
            g.setColor(new Color(12, 13, 18));
            g.fillOval(cx - 4, cy - 2, 2, 3);
            g.fillOval(cx + 3, cy - 2, 2, 3);

            g.setFont(new Font("Monospaced", Font.BOLD, 11));
            g.setColor(new Color(210, 215, 225));
            g.drawString("AVOID STALKERS", x + 16, y + 55);
        }

        void drawFeatureCard(Graphics2D g, int x, int y, int w, int h, String icon, String title, Color accent) {
            g.setColor(new Color(10, 13, 20, 225));
            g.fillRoundRect(x, y, w, h, 14, 14);
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90));
            g.drawRoundRect(x, y, w, h, 14, 14);
            g.setFont(new Font("Monospaced", Font.BOLD, 23));
            g.setColor(accent);
            g.drawString(icon, x + 16, y + 31);
            g.setFont(new Font("Monospaced", Font.BOLD, 11));
            g.setColor(new Color(210, 215, 225));
            g.drawString(title, x + 16, y + 55);
        }

        void drawGlowText(Graphics2D g, String text, int centerX, int baseline, Color main, Color glow, int spread) {
            FontMetrics fm = g.getFontMetrics();
            int x = centerX - fm.stringWidth(text) / 2;
            for (int i = spread; i >= 2; i -= 3) {
                int alpha = Math.max(3, glow.getAlpha() * (spread - i + 2) / spread / 2);
                g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), alpha));
                g.drawString(text, x + (i % 3 - 1), baseline + (i % 2 - 1));
            }
            g.setColor(main);
            g.drawString(text, x, baseline);
        }

        void drawOverlay(Graphics2D g, boolean victory) {
            g.setColor(new Color(0, 0, 0, 205));
            g.fillRect(0, 0, GamePanel.WIDTH, GamePanel.HEIGHT);

            Color accent = victory ? new Color(65, 235, 145) : new Color(245, 60, 75);
            RadialGradientPaint glow = new RadialGradientPaint(
                    new Point(GamePanel.WIDTH / 2, 235), 300f,
                    new float[]{0f, .55f, 1f},
                    new Color[]{new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 55),
                            new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 10),
                            new Color(0, 0, 0, 0)});
            g.setPaint(glow);
            g.fillRect(0, 0, GamePanel.WIDTH, GamePanel.HEIGHT);

            g.setFont(new Font("SansSerif", Font.BOLD, 52));
            drawGlowText(g, victory ? "YOU ESCAPED" : "YOU WERE CAUGHT",
                    GamePanel.WIDTH / 2, 215, Color.WHITE, accent, 20);

            g.setFont(new Font("Monospaced", Font.PLAIN, 17));
            g.setColor(new Color(205, 210, 220));
            center(g, victory ? "All three AI-controlled levels survived." : "The Stalkers won this run.", 265);

            g.setFont(new Font("Monospaced", Font.BOLD, 22));
            g.setColor(accent);
            center(g, "FINAL SCORE  " + gp.score, 315);

            g.setColor(new Color(12, 15, 22, 235));
            g.fillRoundRect(GamePanel.WIDTH / 2 - 190, 360, 380, 62, 14, 14);
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 150));
            g.drawRoundRect(GamePanel.WIDTH / 2 - 190, 360, 380, 62, 14, 14);
            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            g.setColor(Color.WHITE);
            center(g, "PRESS ENTER TO PLAY AGAIN", 398);

        }

        void center(Graphics2D g, String text, int y) {
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (GamePanel.WIDTH - fm.stringWidth(text)) / 2, y);
        }
    }

    static class Ghost {
        int x, y;
        String name;
        float colorShift;
        long lastMove = 0;
        long moveDelay = 400;
        GamePanel.GhostState state = GamePanel.GhostState.WANDER;
        Point target;
        Point lastSeen = new Point(1, 1);
        Point wanderTarget;
        int searchTicks = 0;

        Ghost(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    static class Node {
        Point p;
        int g, f;
        Node(Point p, int g, int f) { this.p = p; this.g = g; this.f = f; }
    }

    static class Particle {
        double x, y, vx, vy, life;
        Particle(double x, double y, double vx, double vy, double life) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.life = life;
        }
    }
}