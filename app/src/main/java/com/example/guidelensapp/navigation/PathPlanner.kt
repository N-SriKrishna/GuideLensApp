package com.example.guidelensapp.navigation

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.example.guidelensapp.Config
import java.util.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import androidx.core.graphics.scale
import androidx.core.graphics.get

private data class Node(
    val x: Int,
    val y: Int,
    var g: Int = Int.MAX_VALUE,
    var h: Int = 0,
    var parent: Node? = null
) {
    val f: Int get() = g + h
}

data class NavigationOutput(
    val command: String,
    val path: List<PointF>? = null
)

class PathPlanner {
    companion object {
        private const val TAG = "PathPlanner"
        private const val STRAIGHT_COST = 10
        private const val DIAGONAL_COST = 14
        private const val MAX_ITERATIONS = 10000

        fun getNavigationCommand(
            floorMask: Bitmap,
            targetPosition: PointF?,
            imageWidth: Int,
            imageHeight: Int
        ): NavigationOutput {
            Log.d(TAG, "⚡ getNavigationCommand called")

            if (targetPosition == null) {
                return NavigationOutput("Searching for target...")
            }

            val userPosition = PointF(imageWidth / 2f, imageHeight * 0.8f)

            // Create navigation grid
            val grid = createGridFromMask(floorMask, imageWidth, imageHeight)

            val gridWidth = grid.size
            val gridHeight = if (gridWidth > 0) grid[0].size else 0

            if (gridWidth == 0 || gridHeight == 0) {
                Log.e(TAG, "❌ Grid creation failed!")
                return NavigationOutput("Floor analysis failed")
            }

            // COUNT WALKABLE CELLS
            val walkableCount = grid.sumOf { row -> row.count { it } }
            val totalCells = gridWidth * gridHeight
            val walkablePercent = (walkableCount * 100) / totalCells

            Log.d(TAG, "📊 Grid: ${gridWidth}x${gridHeight}, walkable: $walkableCount/$totalCells ($walkablePercent%)")

            // Rest of path planning logic
            val path = findPath(grid, userPosition, targetPosition, imageWidth, imageHeight)

            if (path.isEmpty()) {
                Log.w(TAG, "⚠️ No path found to target")
                return NavigationOutput("No path to target", path)
            }

            Log.d(TAG, "✅ Path found: ${path.size} points")

            // Generate turn command
            val command = generateTurnCommand(userPosition, path)
            Log.d(TAG, "📢 Command: $command")

            return NavigationOutput(command, path)
        }

        // FIXED: Read green channel from floor mask and proper inflation
        private fun createGridFromMask(
            mask: Bitmap,
            width: Int,
            height: Int
        ): Array<BooleanArray> {
            val gridWidth = width / Config.PATHFINDING_GRID_SCALE
            val gridHeight = height / Config.PATHFINDING_GRID_SCALE

            if (gridWidth <= 0 || gridHeight <= 0) {
                return emptyArray()
            }

            val grid = Array(gridWidth) { BooleanArray(gridHeight) }
            val scaledMask = mask.scale(gridWidth, gridHeight, false)

            // FIXED: Check for green channel (floor mask is green overlay)
            for (x in 0 until gridWidth) {
                for (y in 0 until gridHeight) {
                    val pixel = scaledMask[x, y]
                    val alpha = (pixel ushr 24) and 0xFF
                    val green = (pixel ushr 8) and 0xFF
                    // Walkable if has transparency AND green component
                    grid[x][y] = (alpha > 128 && green > 128)
                }
            }

            scaledMask.recycle()

            // FIXED: Proper obstacle inflation
            val inflatedGrid = Array(gridWidth) { x ->
                BooleanArray(gridHeight) { y -> grid[x][y] }
            }

            for (x in 0 until gridWidth) {
                for (y in 0 until gridHeight) {
                    if (!grid[x][y]) {  // If this cell is NOT walkable (obstacle)
                        // Mark surrounding cells as non-walkable for safety
                        for (dx in -Config.INFLATION_RADIUS_GRID..Config.INFLATION_RADIUS_GRID) {
                            for (dy in -Config.INFLATION_RADIUS_GRID..Config.INFLATION_RADIUS_GRID) {
                                val nx = x + dx
                                val ny = y + dy
                                if (nx in 0 until gridWidth && ny in 0 until gridHeight) {
                                    inflatedGrid[nx][ny] = false
                                }
                            }
                        }
                    }
                }
            }

            return inflatedGrid
        }

        private fun findPath(
            grid: Array<BooleanArray>,
            userPosition: PointF,
            targetPosition: PointF,
            imageWidth: Int,
            imageHeight: Int
        ): List<PointF> {
            val gridWidth = grid.size
            val gridHeight = if (gridWidth > 0) grid[0].size else 0

            if (gridWidth == 0 || gridHeight == 0) return emptyList()

            // Convert pixel coordinates to grid coordinates
            val startX = ((userPosition.x / imageWidth) * gridWidth).toInt().coerceIn(0, gridWidth - 1)
            val startY = ((userPosition.y / imageHeight) * gridHeight).toInt().coerceIn(0, gridHeight - 1)
            val endX = ((targetPosition.x / imageWidth) * gridWidth).toInt().coerceIn(0, gridWidth - 1)
            val endY = ((targetPosition.y / imageHeight) * gridHeight).toInt().coerceIn(0, gridHeight - 1)

            val start = Node(startX, startY)
            val end = Node(endX, endY)

            val nodePath = findPathAStar(start, end, grid)

            if (nodePath.isNullOrEmpty()) {
                return emptyList()
            }

            // Convert grid path back to pixel coordinates
            return nodePath.map { node ->
                PointF(
                    (node.x.toFloat() / gridWidth) * imageWidth,
                    (node.y.toFloat() / gridHeight) * imageHeight
                )
            }
        }

        // Helpers: pack/unpack coords into Int key for maps/sets
        private fun key(x: Int, y: Int) = (x shl 16) or (y and 0xFFFF)

        /**
         * Find the nearest walkable cell to (x,y) using BFS on grid. Returns Pair(x,y) or null.
         */
        private fun findNearestWalkable(startX: Int, startY: Int, grid: Array<BooleanArray>): Pair<Int, Int>? {
            val w = grid.size
            val h = if (w > 0) grid[0].size else 0
            if (w == 0 || h == 0) return null
            if (startX in 0 until w && startY in 0 until h && grid[startX][startY]) return Pair(startX, startY)

            val q: ArrayDeque<Pair<Int, Int>> = ArrayDeque()
            val visited = Array(w) { BooleanArray(h) }
            q.add(Pair(startX.coerceIn(0, w-1), startY.coerceIn(0, h-1)))
            visited[q.first().first][q.first().second] = true

            val dirs = listOf(Pair(1,0), Pair(-1,0), Pair(0,1), Pair(0,-1), Pair(1,1), Pair(1,-1), Pair(-1,1), Pair(-1,-1))

            while (q.isNotEmpty()) {
                val (x, y) = q.removeFirst()
                for ((dx, dy) in dirs) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h && !visited[nx][ny]) {
                        if (grid[nx][ny]) return Pair(nx, ny)
                        visited[nx][ny] = true
                        q.add(Pair(nx, ny))
                    }
                }
            }
            return null
        }

        /**
         * A* with openMap optimization and bounds checks.
         */
        private fun findPathAStar(
            start: Node,
            end: Node,
            grid: Array<BooleanArray>
        ): List<Node>? {
            val gridWidth = grid.size
            val gridHeight = if (gridWidth > 0) grid[0].size else 0
            if (gridWidth == 0 || gridHeight == 0) return null

            // Ensure start / end are on walkable cells (find nearest if not).
            val validStart = if (start.x in 0 until gridWidth && start.y in 0 until gridHeight && grid[start.x][start.y]) {
                Pair(start.x, start.y)
            } else findNearestWalkable(start.x, start.y, grid)

            val validEnd = if (end.x in 0 until gridWidth && end.y in 0 until gridHeight && grid[end.x][end.y]) {
                Pair(end.x, end.y)
            } else findNearestWalkable(end.x, end.y, grid)

            if (validStart == null || validEnd == null) {
                Log.w(TAG, "No reachable start/end on walkable grid")
                return null
            }

            val (sX, sY) = validStart
            val (eX, eY) = validEnd

            // Re-init start/end nodes with corrected coords
            val sNode = Node(sX, sY)
            val eNode = Node(eX, eY)

            // open list (min-heap) and map for O(1) lookup and updates
            val openList = PriorityQueue(compareBy<Node> { it.f }.thenBy { it.h })
            val openMap = mutableMapOf<Int, Node>() // key->node
            val closed = Array(gridWidth) { BooleanArray(gridHeight) }

            sNode.g = 0
            sNode.h = heuristic(sNode, eNode)
            openList.add(sNode)
            openMap[key(sNode.x, sNode.y)] = sNode

            var iterations = 0

            val dirs = listOf(
                Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1),
                Pair(1, 1), Pair(1, -1), Pair(-1, 1), Pair(-1, -1)
            )

            while (openList.isNotEmpty() && iterations < MAX_ITERATIONS) {
                iterations++
                val current = openList.poll()
                val currentKey = key(current.x, current.y)
                openMap.remove(currentKey)

                if (closed[current.x][current.y]) continue
                if (current.x == eNode.x && current.y == eNode.y) {
                    Log.d(TAG, "✓ Path found in $iterations iterations")
                    return reconstructPath(current)
                }

                if (current != null) {
                    closed[current.x][current.y] = true
                }

                for ((dx, dy) in dirs) {
                    val nx = current.x + dx
                    val ny = current.y + dy

                    if (nx !in 0 until gridWidth || ny !in 0 until gridHeight) continue
                    if (!grid[nx][ny]) continue
                    if (closed[nx][ny]) continue

                    val moveCost = if (dx == 0 || dy == 0) STRAIGHT_COST else DIAGONAL_COST
                    val tentativeG = current.g + moveCost

                    val posKey = key(nx, ny)
                    val existing = openMap[posKey]

                    if (existing == null || tentativeG < existing.g) {
                        val neighbor = existing ?: Node(nx, ny)
                        neighbor.g = tentativeG
                        neighbor.h = heuristic(neighbor, eNode)
                        neighbor.parent = current

                        if (existing != null) {
                            // reinsert with updated priority
                            openList.remove(existing)
                        }
                        openList.add(neighbor)
                        openMap[posKey] = neighbor
                    }
                }
            }

            Log.w(TAG, "⚠️ No path found after $iterations iterations")
            return null
        }

        /**
         * Reconstruct path nodes from end node.
         */
        private fun reconstructPath(endNode: Node): List<Node> {
            val path = mutableListOf<Node>()
            var curr: Node? = endNode
            while (curr != null) {
                path.add(curr)
                curr = curr.parent
            }
            return path.reversed()
        }


        private fun heuristic(a: Node, b: Node): Int {
            return (abs(a.x - b.x) + abs(a.y - b.y)) * STRAIGHT_COST
        }

        private fun generateTurnCommand(
            userPos: PointF,
            path: List<PointF>
        ): String {
            if (path.isEmpty()) return "No path available"

            // Pick lookahead point
            val lookaheadPt = pickLookaheadPoint(path, userPos, Config.PP_LOOKAHEAD_DISTANCE_PX)

            // Calculate curvature-based command
            return generateCurvatureCommand(userPos, lookaheadPt)
        }

        private fun pickLookaheadPoint(
            path: List<PointF>,
            userPosition: PointF,
            lookaheadDistance: Float
        ): PointF {
            if (path.isEmpty()) return userPosition

            var lastPoint = path.first()

            for (i in 1 until path.size) {
                val currentPoint = path[i]
                val dist = hypot(
                    currentPoint.x - userPosition.x,
                    currentPoint.y - userPosition.y
                )

                if (dist >= lookaheadDistance) {
                    return currentPoint
                }

                lastPoint = currentPoint
            }

            return lastPoint
        }

        private fun generateCurvatureCommand(
            userPos: PointF,
            lookaheadPt: PointF
        ): String {
            val dx = lookaheadPt.x - userPos.x
            val dy = lookaheadPt.y - userPos.y

            // Transform to body frame (camera pointing forward)
            val yBody = -dy
            val xBody = dx

            if (yBody <= 1e-6) return "Stop - Target behind you"

            val L = hypot(xBody, yBody)
            val kappa = (2.0f * xBody) / (L.pow(2) + 1e-6f)
            val absKappa = abs(kappa)

            return when {
                absKappa > Config.PP_SHARP_TURN_K -> {
                    if (kappa > 0) "↗️ Turn sharp right" else "↖️ Turn sharp left"
                }
                absKappa > Config.PP_SLIGHT_TURN_K -> {
                    if (kappa > 0) "→ Turn slightly right" else "← Turn slightly left"
                }
                else -> "⬆️ Move straight forward"
            }
        }
    }
}
