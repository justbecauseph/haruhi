package town.lampas.haruhi.tools.bbmodel;

import town.lampas.haruhi.tools.bbmodel.BbProject.Element;
import town.lampas.haruhi.tools.bbmodel.BbProject.Group;

/**
 * Blockbench's {@code calculateVisibleBox} (bedrock.js): the bounding box of the exported cubes in their rest pose, with
 * inflate, cube and group rotations applied, turned into Bedrock visible bounds and merged with the project's stored
 * visible box.
 *
 * <p>Rotations use Blockbench's Euler order ZYX (ModelFormat {@code euler_order} default; three.js builds Rz * Ry * Rx),
 * in Blockbench's own axes, about each element's and group's origin.
 */
final class VisibleBounds {
    private final double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
    private final double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};

    void add(BbProject project, Element cube, Group group) {
        double[] from = project.cubeFrom(cube.json);
        double[] to = project.cubeTo(cube.json);
        double inflate = BbProject.number(cube.json, "inflate", 0);
        double[] origin = project.cubeVector(cube.json, "origin", new double[3]);
        double[] rotation = GeometryCompiler.rotation(cube.json);
        for (int corner = 0; corner < 8; corner++) {
            double[] point = {
                    (corner & 1) == 0 ? from[0] - inflate : to[0] + inflate,
                    (corner & 2) == 0 ? from[1] - inflate : to[1] + inflate,
                    (corner & 4) == 0 ? from[2] - inflate : to[2] + inflate};
            point = rotateAbout(point, origin, rotation);
            for (Group g = group; g != null; g = g.parent) {
                point = rotateAbout(point, g.origin, g.rotation);
            }
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], point[axis]);
                max[axis] = Math.max(max[axis], point[axis]);
            }
        }
    }

    /** [visible_bounds_width, visible_bounds_height, visible_bounds_offset y] from the cubes and the stored box. */
    double[] visibleBox(double[] stored) {
        // "var offset = new THREE.Vector3(8,8,8); visible_box.max.add(offset); visible_box.min.add(offset);"
        double[] lo = {min[0] + 8, min[1] + 8, min[2] + 8};
        double[] hi = {max[0] + 8, max[1] + 8, max[2] + 8};
        double radius = Math.max(Math.max(hi[0], hi[2]), Math.max(-lo[0], -lo[2]));
        if (Double.isInfinite(radius)) {
            radius = 0;
        }
        double width = Math.ceil((radius * 2) / 16);
        width = Math.max(width, stored[0]);
        double yMin = Math.floor(lo[1] / 16);
        double yMax = Math.ceil(hi[1] / 16);
        if (Double.isInfinite(yMin)) {
            yMin = 0;
        }
        if (Double.isInfinite(yMax)) {
            yMax = 0;
        }
        yMin = Math.min(yMin, stored[2] - stored[1] / 2);
        yMax = Math.max(yMax, stored[2] + stored[1] / 2);
        return new double[] {width, yMax - yMin, (yMax + yMin) / 2};
    }

    private static double[] rotateAbout(double[] point, double[] origin, double[] degrees) {
        if (degrees[0] == 0 && degrees[1] == 0 && degrees[2] == 0) {
            return point;
        }
        double x = point[0] - origin[0];
        double y = point[1] - origin[1];
        double z = point[2] - origin[2];
        // Rx, then Ry, then Rz (the matrix Rz * Ry * Rx).
        double a = Math.toRadians(degrees[0]);
        double y1 = y * Math.cos(a) - z * Math.sin(a);
        double z1 = y * Math.sin(a) + z * Math.cos(a);
        double b = Math.toRadians(degrees[1]);
        double x2 = x * Math.cos(b) + z1 * Math.sin(b);
        double z2 = -x * Math.sin(b) + z1 * Math.cos(b);
        double c = Math.toRadians(degrees[2]);
        double x3 = x2 * Math.cos(c) - y1 * Math.sin(c);
        double y3 = x2 * Math.sin(c) + y1 * Math.cos(c);
        return new double[] {x3 + origin[0], y3 + origin[1], z2 + origin[2]};
    }
}
