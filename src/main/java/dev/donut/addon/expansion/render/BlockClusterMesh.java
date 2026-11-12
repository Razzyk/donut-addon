package dev.donut.addon.expansion.render;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable exposed-surface mesh for a face-connected set of unit blocks. */
public final class BlockClusterMesh {
    private static final int[][] AXIS = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

    private BlockClusterMesh() { }

    /** Returns deterministic, immutable components using six-face adjacency. */
    public static List<List<BlockPos>> connectedComponents(Collection<BlockPos> positions) {
        Objects.requireNonNull(positions, "positions");
        Set<Cell> remaining = new HashSet<>();
        for (BlockPos pos : positions) remaining.add(Cell.of(Objects.requireNonNull(pos, "position")));
        List<Cell> seeds = new ArrayList<>(remaining);
        seeds.sort(Cell.ORDER);
        List<List<BlockPos>> components = new ArrayList<>();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        for (Cell seed : seeds) {
            if (!remaining.remove(seed)) continue;
            queue.add(seed);
            List<Cell> component = new ArrayList<>();
            while (!queue.isEmpty()) {
                Cell current = queue.removeFirst();
                component.add(current);
                for (int[] axis : AXIS) for (int sign : new int[]{-1, 1}) {
                    Cell next = current.offset(axis[0] * sign, axis[1] * sign, axis[2] * sign);
                    if (remaining.remove(next)) queue.addLast(next);
                }
            }
            component.sort(Cell.ORDER);
            List<BlockPos> immutable = component.stream().map(Cell::toBlockPos).toList();
            components.add(immutable);
        }
        components.sort((a, b) -> Cell.ORDER.compare(Cell.of(a.getFirst()), Cell.of(b.getFirst())));
        return List.copyOf(components);
    }

    /**
     * Builds merged exposed quads and deduplicated boundary edges. Vertex coordinates
 * are doubles relative to the mesh origin; rendering must translate by that origin
 * in double precision before converting these small local offsets to floats.
     */
    public static Mesh build(Collection<BlockPos> positions) {
        Objects.requireNonNull(positions, "positions");
        Set<Cell> blocks = new HashSet<>();
        for (BlockPos pos : positions) blocks.add(Cell.of(Objects.requireNonNull(pos, "position")));
        if (blocks.isEmpty()) return new Mesh(0, 0, 0, 0, 0, 0, 0, List.of(), List.of());

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (Cell p : blocks) {
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
        }
        double count = blocks.size();
        double localCenterX = 0, localCenterY = 0, localCenterZ = 0;
        for (Cell p : blocks) {
            localCenterX += (long) p.x - minX + 0.5;
            localCenterY += (long) p.y - minY + 0.5;
            localCenterZ += (long) p.z - minZ + 0.5;
        }
        localCenterX /= count; localCenterY /= count; localCenterZ /= count;

        Map<Plane, Set<GridCell>> facesByPlane = new HashMap<>();
        for (Cell p : blocks) for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            for (int sign : new int[]{-1, 1}) {
                if (blocks.contains(p.offset(AXIS[axis][0] * sign, AXIS[axis][1] * sign, AXIS[axis][2] * sign))) continue;
                long[] xyz = {p.x, p.y, p.z};
                long plane = xyz[axis] + (sign > 0 ? 1 : 0);
                facesByPlane.computeIfAbsent(new Plane(axis, sign, plane), ignored -> new HashSet<>())
                    .add(new GridCell(xyz[u], xyz[v]));
            }
        }

        List<Face> faces = new ArrayList<>();
        List<Plane> planes = new ArrayList<>(facesByPlane.keySet());
        planes.sort(Plane.ORDER);
        for (Plane plane : planes) mergeFaces(plane, facesByPlane.get(plane), minX, minY, minZ, faces);

        Map<Line, TreeSet<Long>> edgeStarts = new HashMap<>();
        for (Cell p : blocks) addBoundaryEdges(p, blocks, edgeStarts);
        List<Line> lines = new ArrayList<>(edgeStarts.keySet());
        lines.sort(Line.ORDER);
        List<Segment> edges = new ArrayList<>();
        for (Line line : lines) {
            TreeSet<Long> starts = edgeStarts.get(line);
            Long start = null, end = null;
            for (long cellStart : starts) {
                if (start == null) { start = cellStart; end = cellStart + 1; }
                else if (cellStart == end) end++;
                else {
                    edges.add(segment(line, start, end, minX, minY, minZ));
                    start = cellStart; end = cellStart + 1;
                }
            }
            if (start != null) edges.add(segment(line, start, end, minX, minY, minZ));
        }
        return new Mesh(minX, minY, minZ, localCenterX, localCenterY, localCenterZ,
            blocks.size(), List.copyOf(faces), List.copyOf(edges));
    }

    private static void mergeFaces(Plane plane, Set<GridCell> cells, int minX, int minY, int minZ, List<Face> out) {
        Comparator<GridCell> order = Comparator.comparingLong(GridCell::v).thenComparingLong(GridCell::u);
        TreeSet<GridCell> remaining = new TreeSet<>(order);
        remaining.addAll(cells);
        int u = (plane.axis + 1) % 3, v = (plane.axis + 2) % 3;
        while (!remaining.isEmpty()) {
            GridCell first = remaining.first();
            long width = 1;
            while (remaining.contains(new GridCell(first.u + width, first.v))) width++;
            long height = 1;
            while (true) {
                long row = first.v + height;
                boolean complete = true;
                for (long du = 0; du < width; du++) if (!remaining.contains(new GridCell(first.u + du, row))) { complete = false; break; }
                if (!complete) break;
                height++;
            }
            for (long dv = 0; dv < height; dv++) for (long du = 0; du < width; du++) remaining.remove(new GridCell(first.u + du, first.v + dv));
            long[] base = new long[3];
            base[plane.axis] = plane.coordinate;
            base[u] = first.u; base[v] = first.v;
            Vertex a = vertex(base, u, 0, v, 0, minX, minY, minZ);
            Vertex b = vertex(base, u, width, v, 0, minX, minY, minZ);
            Vertex c = vertex(base, u, width, v, height, minX, minY, minZ);
            Vertex d = vertex(base, u, 0, v, height, minX, minY, minZ);
            out.add(plane.sign > 0 ? new Face(a, b, c, d) : new Face(a, d, c, b));
        }
    }

    // Preserve convex outside corners and reentrant (hole) corners, matching voxel-surface topology.
    private static void addBoundaryEdges(Cell p, Set<Cell> blocks, Map<Line, TreeSet<Long>> out) {
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            for (int su : new int[]{-1, 1}) for (int sv : new int[]{-1, 1}) {
                Cell nu = p.offsetOn(u, su), nv = p.offsetOn(v, sv), diagonal = nu.offsetOn(v, sv);
                boolean outsideU = !blocks.contains(nu), outsideV = !blocks.contains(nv);
                boolean convex = outsideU && outsideV;
                boolean concave = !outsideU && !outsideV && !blocks.contains(diagonal);
                if (!convex && !concave) continue;
                long[] xyz = {p.x, p.y, p.z};
                if (su > 0) xyz[u]++;
                if (sv > 0) xyz[v]++;
                Line line = new Line(axis, xyz[u], xyz[v]);
                out.computeIfAbsent(line, ignored -> new TreeSet<>()).add(xyz[axis]);
            }
        }
    }

    private static Segment segment(Line line, long start, long end, int minX, int minY, int minZ) {
        long[] a = new long[3], b = new long[3];
        int u = (line.axis + 1) % 3, v = (line.axis + 2) % 3;
        a[u] = b[u] = line.u; a[v] = b[v] = line.v;
        a[line.axis] = start; b[line.axis] = end;
        return new Segment(vertex(a, minX, minY, minZ), vertex(b, minX, minY, minZ));
    }

    private static Vertex vertex(long[] p, int minX, int minY, int minZ) {
        return new Vertex((double) p[0] - minX, (double) p[1] - minY, (double) p[2] - minZ);
    }

    private static Vertex vertex(long[] base, int u, long du, int v, long dv, int minX, int minY, int minZ) {
        long[] p = base.clone(); p[u] += du; p[v] += dv;
        return vertex(p, minX, minY, minZ);
    }

    private record Cell(int x, int y, int z) {
        static final Comparator<Cell> ORDER = Comparator.comparingInt(Cell::x).thenComparingInt(Cell::y).thenComparingInt(Cell::z);
        static Cell of(BlockPos p) { return new Cell(p.getX(), p.getY(), p.getZ()); }
        Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
        Cell offsetOn(int axis, int amount) { return axis == 0 ? new Cell(x + amount, y, z) : axis == 1 ? new Cell(x, y + amount, z) : new Cell(x, y, z + amount); }
        BlockPos toBlockPos() { return new BlockPos(x, y, z); }
    }
    private record GridCell(long u, long v) { }
    private record Plane(int axis, int sign, long coordinate) {
        static final Comparator<Plane> ORDER = Comparator.comparingInt(Plane::axis).thenComparingLong(Plane::coordinate).thenComparingInt(Plane::sign);
    }
    private record Line(int axis, long u, long v) {
        static final Comparator<Line> ORDER = Comparator.comparingInt(Line::axis).thenComparingLong(Line::u).thenComparingLong(Line::v);
    }

    public record Vertex(double x, double y, double z) { }
    public record Face(Vertex a, Vertex b, Vertex c, Vertex d) { }
    public record Segment(Vertex a, Vertex b) { }
    public record Mesh(int originX, int originY, int originZ, double centerX, double centerY, double centerZ,
                       int blockCount, List<Face> faces, List<Segment> edges) {
        public Mesh { faces = List.copyOf(faces); edges = List.copyOf(edges); }
        public boolean isEmpty() { return blockCount == 0; }
    }
}
