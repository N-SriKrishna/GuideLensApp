// D:/WorkSpace/androidapps/GuideLensApp/app/src/main/java/com/example/guidelensapp/navigation/PathPlanner.kt
package com.example.guidelensapp.navigation

import android.graphics.Bitmap
import android.graphics.PointF
import com.example.guidelensapp.Config // FIX: Import Config from the correct root package
import java.util.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

// ... (rest of the file is unchanged and correct)
private data class Node(
    val x: Int,
    val y: Int,
    var g: Int = Int.MAX_VALUE, // Cost from start
    var h: Int = 0,             // Heuristic cost to end
    var parent: Node? = null
) {
    val f: Int get() = g + h // Total cost
}

data class NavigationOutput(
    val command: String,
    val path: List<PointF>? = null
)

class PathPlanner {
    // ... (rest of the file is unchanged)
    fun getNavigationCommand(
        floorMask: Bitmap,
        targetPosition: PointF?,
        imageWidth: Int,
        imageHeight: Int
    ): NavigationOutput {
        if (targetPosition == null) {
            return NavigationOutput("Searching for target...")
        }

        val userPosition = PointF(imageWidth / 2f, imageHeight.toFloat() - 40f)
        val grid = createGridFromMask(floorMask, imageWidth, imageHeight)

        val startNode = Node(
            (userPosition.x / Config.PATHFINDING_GRID_SCALE).toInt(),
            (userPosition.y / Config.PATHFINDING_GRID_SCALE).toInt()
        )
        val endNode = Node(
            (targetPosition.x / Config.PATHFINDING_GRID_SCALE).toInt(),
            (targetPosition.y / Config.PATHFINDING_GRID_SCALE).toInt()
        )

        val path = findPath(startNode, endNode, grid)

        if (path == null || path.size < 2) {
            return NavigationOutput("Path blocked or too short")
        }

        val pathInPixels = path.map {
            PointF(
                it.x.toFloat() * Config.PATHFINDING_GRID_SCALE,
                it.y.toFloat() * Config.PATHFINDING_GRID_SCALE
            )
        }

        val lookaheadPoint = pickLookaheadPoint(pathInPixels, userPosition, Config.PP_LOOKAHEAD_DISTANCE_PX)
        val command = generateCurvatureCommand(userPosition, lookaheadPoint)

        val distToTarget = hypot(targetPosition.x - userPosition.x, targetPosition.y - userPosition.y)
        if (distToTarget < Config.TARGET_REACHED_RADIUS_PX) {
            return NavigationOutput("Target Reached", pathInPixels)
        }

        return NavigationOutput(command, pathInPixels)
    }

    // ... (rest of the class functions)
    private fun createGridFromMask(mask: Bitmap, width: Int, height: Int): Array<BooleanArray> {
        val gridWidth = width / Config.PATHFINDING_GRID_SCALE
        val gridHeight = height / Config.PATHFINDING_GRID_SCALE
        val grid = Array(gridWidth) { BooleanArray(gridHeight) }
        val scaledMask = Bitmap.createScaledBitmap(mask, gridWidth, gridHeight, true)

        for (x in 0 until gridWidth) {
            for (y in 0 until gridHeight) {
                grid[x][y] = scaledMask.getPixel(x, y) ushr 24 != 0
            }
        }

        val inflatedGrid = Array(gridWidth) { BooleanArray(gridHeight) { true } }
        for (x in 0 until gridWidth) {
            for (y in 0 until gridHeight) {
                if (!grid[x][y]) { // If it's an obstacle
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

    private fun findPath(start: Node, end: Node, grid: Array<BooleanArray>): List<Node>? {
        val openList = PriorityQueue<Node>(compareBy { it.f })
        val closedList = mutableSetOf<Pair<Int, Int>>()
        val gridWidth = grid.size
        val gridHeight = grid[0].size

        start.g = 0
        start.h = heuristic(start, end)
        openList.add(start)

        while (openList.isNotEmpty()) {
            val currentNode = openList.poll() ?: break

            if (currentNode.x == end.x && currentNode.y == end.y) {
                return reconstructPath(currentNode)
            }

            closedList.add(Pair(currentNode.x, currentNode.y))

            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue

                    val newX = currentNode.x + dx
                    val newY = currentNode.y + dy

                    if (newX < 0 || newX >= gridWidth || newY < 0 || newY >= gridHeight || !grid[newX][newY] || closedList.contains(Pair(newX, newY))) {
                        continue
                    }

                    val neighbor = Node(newX, newY)
                    val newG = currentNode.g + if (dx == 0 || dy == 0) 10 else 14 // Cost for straight/diagonal moves

                    if (newG < neighbor.g) {
                        neighbor.g = newG
                        neighbor.h = heuristic(neighbor, end)
                        neighbor.parent = currentNode
                        if (!openList.any { it.x == newX && it.y == newY }) {
                            openList.add(neighbor)
                        }
                    }
                }
            }
        }
        return null // No path found
    }

    private fun reconstructPath(endNode: Node): List<Node> {
        val path = mutableListOf<Node>()
        var currentNode: Node? = endNode
        while (currentNode != null) {
            path.add(currentNode)
            currentNode = currentNode.parent
        }
        return path.reversed()
    }

    private fun heuristic(a: Node, b: Node): Int {
        return (abs(a.x - b.x) + abs(a.y - b.y)) * 10
    }

    private fun pickLookaheadPoint(path: List<PointF>, userPosition: PointF, lookaheadDistance: Float): PointF {
        var lastPoint = path.first()
        for (i in 1 until path.size) {
            val currentPoint = path[i]
            val dist = hypot(currentPoint.x - userPosition.x, currentPoint.y - userPosition.y)
            if (dist >= lookaheadDistance) {
                return currentPoint
            }
            lastPoint = currentPoint
        }
        return lastPoint
    }

    private fun generateCurvatureCommand(userPos: PointF, lookaheadPt: PointF): String {
        val dx = lookaheadPt.x - userPos.x
        val dy = lookaheadPt.y - userPos.y
        val yBody = -dy
        val xBody = dx
        if (yBody <= 1e-6) return "Stop"

        val L = hypot(xBody, yBody)
        val kappa = (2.0f * xBody) / (L.pow(2) + 1e-6f)
        val absKappa = abs(kappa)

        return when {
            absKappa > Config.PP_SHARP_TURN_K -> if (kappa > 0) "Turn sharp right" else "Turn sharp left"
            absKappa > Config.PP_SLIGHT_TURN_K -> if (kappa > 0) "Turn slightly right" else "Turn slightly left"
            else -> "Move Forward"
        }
    }
}
